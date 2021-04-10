// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#include "yb/master/master_snapshot_coordinator.h"

#include <unordered_map>

#include <boost/multi_index/composite_key.hpp>
#include <boost/multi_index/mem_fun.hpp>

#include "yb/common/snapshot.h"
#include "yb/common/transaction_error.h"

#include "yb/docdb/doc_key.h"

#include "yb/master/catalog_entity_info.h"
#include "yb/master/master_error.h"
#include "yb/master/restoration_state.h"
#include "yb/master/snapshot_coordinator_context.h"
#include "yb/master/snapshot_schedule_state.h"
#include "yb/master/snapshot_state.h"
#include "yb/master/sys_catalog_writer.h"

#include "yb/rpc/poller.h"

#include "yb/tablet/tablet.h"
#include "yb/tablet/tablet_snapshots.h"
#include "yb/tablet/operations/snapshot_operation.h"
#include "yb/tablet/operations/write_operation.h"

#include "yb/tserver/tserver_error.h"

#include "yb/util/pb_util.h"

using namespace std::literals;
using namespace std::placeholders;

DECLARE_int32(sys_catalog_write_timeout_ms);

DEFINE_uint64(snapshot_coordinator_poll_interval_ms, 5000,
              "Poll interval for snapshot coordinator in milliseconds.");

namespace yb {
namespace master {

namespace {

YB_DEFINE_ENUM(Bound, (kFirst)(kLast));
YB_DEFINE_ENUM(RestorePhase, (kInitial)(kPostSysCatalogLoad));

void SubmitWrite(
    docdb::KeyValueWriteBatchPB&& write_batch, SnapshotCoordinatorContext* context,
    const std::shared_ptr<Synchronizer>& synchronizer = nullptr) {
  tserver::WriteRequestPB empty_write_request;
  auto state = std::make_unique<tablet::WriteOperationState>(
      /* tablet= */ nullptr, &empty_write_request);
  if (synchronizer) {
    state->set_completion_callback(std::make_unique<
        tablet::WeakSynchronizerOperationCompletionCallback>(synchronizer));
  }
  auto& request = *state->mutable_request();
  *request.mutable_write_batch() = std::move(write_batch);
  auto operation = std::make_unique<tablet::WriteOperation>(
      std::move(state), yb::OpId::kUnknownTerm, ScopedOperation(),
      CoarseMonoClock::now() + FLAGS_sys_catalog_write_timeout_ms * 1ms, /* context */ nullptr);
  context->Submit(std::move(operation));
}

struct NoOp {
  template <class... Args>
  void operator()(Args&&... args) const {}
};

// Utility to create callback that is invoked when operation done.
// Finds appropriate entry in passed collection and invokes Done on it.
template <class Collection, class PostProcess = NoOp>
auto MakeDoneCallback(
    std::mutex* mutex, const Collection& collection, const typename Collection::key_type& key,
    const TabletId& tablet_id, const PostProcess& post_process = PostProcess()) {
  struct DoneFunctor {
    std::mutex& mutex;
    const Collection& collection;
    typename Collection::key_type key;
    TabletId tablet_id;
    PostProcess post_process;

    void operator()(Result<const tserver::TabletSnapshotOpResponsePB&> resp) const {
      std::unique_lock<std::mutex> lock(mutex);
      auto it = collection.find(key);
      if (it == collection.end()) {
        LOG(DFATAL) << "Received reply for unknown " << key;
        return;
      }

      (**it).Done(tablet_id, ResultToStatus(resp));
      post_process(it->get(), &lock);
    }
  };

  return DoneFunctor {
    .mutex = *mutex,
    .collection = collection,
    .key = key,
    .tablet_id = tablet_id,
    .post_process = post_process,
  };
}

} // namespace

class MasterSnapshotCoordinator::Impl {
 public:
  explicit Impl(SnapshotCoordinatorContext* context)
      : context_(*context), poller_(std::bind(&Impl::Poll, this)) {}

  Result<TxnSnapshotId> Create(
      const SysRowEntries& entries, bool imported, CoarseTimePoint deadline) {
    auto synchronizer = std::make_shared<Synchronizer>();
    auto snapshot_id = VERIFY_RESULT(SubmitCreate(
        entries, imported, SnapshotScheduleId::Nil(), HybridTime::kInvalid, TxnSnapshotId::Nil(),
        std::make_unique<tablet::WeakSynchronizerOperationCompletionCallback>(synchronizer)));
    RETURN_NOT_OK(synchronizer->WaitUntil(ToSteady(deadline)));

    return snapshot_id;
  }

  CHECKED_STATUS CreateReplicated(
      int64_t leader_term, const tablet::SnapshotOperationState& state) {
    // TODO(txn_backup) retain logs with this operation while doing snapshot
    auto id = VERIFY_RESULT(FullyDecodeTxnSnapshotId(state.request()->snapshot_id()));

    VLOG(1) << __func__ << "(" << id << ", " << state.ToString() << ")";

    auto snapshot = std::make_unique<SnapshotState>(&context_, id, *state.request());

    TabletSnapshotOperations operations;
    docdb::KeyValueWriteBatchPB write_batch;
    RETURN_NOT_OK(snapshot->StoreToWriteBatch(&write_batch));
    boost::optional<tablet::CreateSnapshotData> sys_catalog_snapshot_data;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto emplace_result = snapshots_.emplace(std::move(snapshot));
      if (!emplace_result.second) {
        return STATUS_FORMAT(IllegalState, "Duplicate snapshot id: $0", id);
      }

      if (leader_term >= 0) {
        (**emplace_result.first).PrepareOperations(&operations);
      }
      auto temp = (**emplace_result.first).SysCatalogSnapshotData(state);
      if (temp.ok()) {
        sys_catalog_snapshot_data = *temp;
      } else if (!temp.status().IsUninitialized()) {
        return temp.status();
      }
    }

    RETURN_NOT_OK(state.tablet()->ApplyOperationState(state, /* batch_idx= */ -1, write_batch));
    if (sys_catalog_snapshot_data) {
      RETURN_NOT_OK(context_.CreateSysCatalogSnapshot(*sys_catalog_snapshot_data));
    }

    ExecuteOperations(operations);

    return Status::OK();
  }

  CHECKED_STATUS Load(tablet::Tablet* tablet) {
    std::lock_guard<std::mutex> lock(mutex_);
    RETURN_NOT_OK(EnumerateSysCatalog(tablet, context_.schema(), SysRowEntry::SNAPSHOT,
        [this](const Slice& id, const Slice& data) NO_THREAD_SAFETY_ANALYSIS -> Status {
      return LoadEntry<SysSnapshotEntryPB>(id, data, &snapshots_);
    }));
    return EnumerateSysCatalog(tablet, context_.schema(), SysRowEntry::SNAPSHOT_SCHEDULE,
        [this](const Slice& id, const Slice& data) NO_THREAD_SAFETY_ANALYSIS -> Status {
      return LoadEntry<SnapshotScheduleOptionsPB>(id, data, &schedules_);
    });
  }

  CHECKED_STATUS ApplyWritePair(Slice key, const Slice& value) {
    docdb::SubDocKey sub_doc_key;
    RETURN_NOT_OK(sub_doc_key.FullyDecodeFrom(key, docdb::HybridTimeRequired::kFalse));

    if (sub_doc_key.doc_key().has_cotable_id()) {
      return Status::OK();
    }

    if (sub_doc_key.doc_key().range_group().size() != 2) {
      LOG(DFATAL) << "Unexpected size of range group in sys catalog entry (2 expected): "
                  << AsString(sub_doc_key.doc_key().range_group()) << "(" << sub_doc_key.ToString()
                  << ")";
      return Status::OK();
    }

    auto first_key = sub_doc_key.doc_key().range_group().front();
    if (first_key.value_type() != docdb::ValueType::kInt32) {
      LOG(DFATAL) << "Unexpected value type for the first range component of sys catalgo entry "
                  << "(kInt32 expected): "
                  << AsString(sub_doc_key.doc_key().range_group());;
    }

    if (first_key.GetInt32() == SysRowEntry::SNAPSHOT) {
      return DoApplyWrite<SysSnapshotEntryPB>(
          sub_doc_key.doc_key().range_group()[1].GetString(), value, &snapshots_);
    }

    if (first_key.GetInt32() == SysRowEntry::SNAPSHOT_SCHEDULE) {
      return DoApplyWrite<SnapshotScheduleOptionsPB>(
          sub_doc_key.doc_key().range_group()[1].GetString(), value, &schedules_);
    }

    return Status::OK();
  }

  template <class Pb, class Map>
  CHECKED_STATUS DoApplyWrite(const std::string& id_str, const Slice& value, Map* map) {
    docdb::Value decoded_value;
    RETURN_NOT_OK(decoded_value.Decode(value));

    auto value_type = decoded_value.primitive_value().value_type();

    if (value_type == docdb::ValueType::kTombstone) {
      std::lock_guard<std::mutex> lock(mutex_);
      auto id = TryFullyDecodeUuid(id_str);
      if (id.is_nil()) {
        LOG(WARNING) << "Unable to decode id: " << id_str;
        return Status::OK();
      }
      bool erased = map->erase(typename Map::key_type(id)) != 0;
      LOG_IF(DFATAL, !erased) << "Unknown entry tombstoned: " << id;
      return Status::OK();
    }

    if (value_type != docdb::ValueType::kString) {
      return STATUS_FORMAT(
          Corruption,
          "Bad value type: $0, expected kString while replaying write for sys catalog",
          decoded_value.primitive_value().value_type());
    }

    std::lock_guard<std::mutex> lock(mutex_);
    return LoadEntry<Pb>(id_str, decoded_value.primitive_value().GetString(), map);
  }

  CHECKED_STATUS ListSnapshots(
      const TxnSnapshotId& snapshot_id, bool list_deleted, ListSnapshotsResponsePB* resp) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (snapshot_id.IsNil()) {
      for (const auto& p : snapshots_) {
        if (!list_deleted) {
          auto aggreaged_state = p->AggregatedState();
          if (aggreaged_state.ok() && *aggreaged_state == SysSnapshotEntryPB::DELETED) {
            continue;
          }
        }
        RETURN_NOT_OK(p->ToPB(resp->add_snapshots()));
      }
      return Status::OK();
    }

    SnapshotState& snapshot = VERIFY_RESULT(FindSnapshot(snapshot_id));
    return snapshot.ToPB(resp->add_snapshots());
  }

  CHECKED_STATUS Delete(const TxnSnapshotId& snapshot_id, CoarseTimePoint deadline) {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      SnapshotState& snapshot = VERIFY_RESULT(FindSnapshot(snapshot_id));
      RETURN_NOT_OK(snapshot.TryStartDelete());
    }

    auto synchronizer = std::make_shared<Synchronizer>();
    SubmitDelete(snapshot_id, synchronizer);
    return synchronizer->WaitUntil(ToSteady(deadline));
  }

  CHECKED_STATUS DeleteReplicated(
      int64_t leader_term, const tablet::SnapshotOperationState& state) {
    auto snapshot_id = VERIFY_RESULT(FullyDecodeTxnSnapshotId(state.request()->snapshot_id()));
    docdb::KeyValueWriteBatchPB write_batch;
    TabletSnapshotOperations operations;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      SnapshotState& snapshot = VERIFY_RESULT(FindSnapshot(snapshot_id));
      snapshot.SetInitialTabletsState(SysSnapshotEntryPB::DELETING);
      RETURN_NOT_OK(snapshot.StoreToWriteBatch(&write_batch));
      if (leader_term >= 0) {
        snapshot.PrepareOperations(&operations);
      }
    }

    RETURN_NOT_OK(state.tablet()->ApplyOperationState(state, /* batch_idx= */ -1, write_batch));

    ExecuteOperations(operations);

    return Status::OK();
  }

  CHECKED_STATUS RestoreSysCatalogReplicated(
      int64_t leader_term, const tablet::SnapshotOperationState& state) {
    auto snapshot_id = VERIFY_RESULT(FullyDecodeTxnSnapshotId(state.request()->snapshot_id()));
    auto restore_at = HybridTime::FromPB(state.request()->snapshot_hybrid_time());
    SnapshotScheduleFilterPB filter;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      SnapshotState& snapshot = VERIFY_RESULT(FindSnapshot(snapshot_id));
      SnapshotScheduleState& schedule_state = VERIFY_RESULT(
          FindSnapshotSchedule(snapshot.schedule_id()));
      LOG(INFO) << "Restore sys catalog from snapshot: " << snapshot.ToString() << ", schedule: "
                << schedule_state.ToString() << " at " << restore_at;
      filter = schedule_state.options().filter();
      // Postpone restore on the leader.
      if (leader_term != OpId::kUnknownTerm) {
        postponed_restores_.push_back(PostponedRestore {
          .term = leader_term,
          .snapshot_id = snapshot_id,
          .restore_at = restore_at,
          .restoration_id = VERIFY_RESULT(FullyDecodeTxnSnapshotRestorationId(
              state.request()->restoration_id()))
        });
      }
    }
    return context_.RestoreSysCatalog(
        snapshot_id, restore_at, OpId::FromPB(state.op_id()), state.hybrid_time(), filter);
  }

  CHECKED_STATUS ListRestorations(
      const TxnSnapshotRestorationId& restoration_id, const TxnSnapshotId& snapshot_id,
      ListSnapshotRestorationsResponsePB* resp) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!restoration_id) {
      for (const auto& p : restorations_) {
        if (!snapshot_id || p->snapshot_id() == snapshot_id) {
          RETURN_NOT_OK(p->ToPB(resp->add_restorations()));
        }
      }
      return Status::OK();
    }

    RestorationState& restoration = VERIFY_RESULT(FindRestoration(restoration_id));
    return restoration.ToPB(resp->add_restorations());
  }

  Result<TxnSnapshotRestorationId> Restore(
      const TxnSnapshotId& snapshot_id, HybridTime restore_at) {
    auto restoration_id = TxnSnapshotRestorationId::GenerateRandom();
    RETURN_NOT_OK(DoRestore(snapshot_id, restore_at, restoration_id, RestorePhase::kInitial));
    return restoration_id;
  }

  Result<SnapshotScheduleId> CreateSchedule(
      const CreateSnapshotScheduleRequestPB& req, CoarseTimePoint deadline) {
    auto synchronizer = std::make_shared<Synchronizer>();

    SnapshotScheduleState schedule(&context_, req);

    docdb::KeyValueWriteBatchPB write_batch;
    RETURN_NOT_OK(schedule.StoreToWriteBatch(&write_batch));
    SubmitWrite(std::move(write_batch), &context_, synchronizer);
    RETURN_NOT_OK(synchronizer->WaitUntil(ToSteady(deadline)));

    return schedule.id();
  }

  CHECKED_STATUS ListSnapshotSchedules(
      const SnapshotScheduleId& snapshot_schedule_id, ListSnapshotSchedulesResponsePB* resp) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (snapshot_schedule_id.IsNil()) {
      for (const auto& p : schedules_) {
        RETURN_NOT_OK(FillSchedule(*p, resp->add_schedules()));
      }
      return Status::OK();
    }

    SnapshotScheduleState& schedule = VERIFY_RESULT(FindSnapshotSchedule(snapshot_schedule_id));
    return FillSchedule(schedule, resp->add_schedules());
  }

  CHECKED_STATUS FillHeartbeatResponse(TSHeartbeatResponsePB* resp) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (schedules_.empty()) {
      return Status::OK();
    }
    auto* out = resp->mutable_snapshot_schedules();
    for (const auto& schedule : schedules_) {
      const auto& id = schedule->id();
      auto* out_schedule = out->add_schedules();
      out_schedule->set_id(id.data(), id.size());
      auto time = LastSnapshotTime(id);
      if (time) {
        out_schedule->set_last_snapshot_hybrid_time(time.ToUint64());
      }
    }
    return Status::OK();
  }

  void SysCatalogLoaded(int64_t term) {
    if (term == OpId::kUnknownTerm) {
      // Do nothing on follower.
      return;
    }
    std::vector<PostponedRestore> postponed_restores;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto filter = [term, &postponed_restores](const PostponedRestore& postponed_restore) {
        if (postponed_restore.term == term) {
          postponed_restores.push_back(postponed_restore);
        }
        // TODO(pitr) cancel restorations
        return postponed_restore.term <= term;
      };
      postponed_restores_.erase(
          std::remove_if(postponed_restores_.begin(), postponed_restores_.end(), filter),
          postponed_restores_.end());
    }
    for (const auto& postponed_restore : postponed_restores) {
      WARN_NOT_OK(
          DoRestore(postponed_restore.snapshot_id, postponed_restore.restore_at,
                    postponed_restore.restoration_id, RestorePhase::kPostSysCatalogLoad),
          Format("Failed to restore tablets for restoration $0", postponed_restore.restoration_id));
    }
  }

  Result<SnapshotSchedulesToTabletsMap> MakeSnapshotSchedulesToTabletsMap() {
    std::vector<std::pair<SnapshotScheduleId, SnapshotScheduleFilterPB>> schedules;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      for (const auto& schedule : schedules_) {
        schedules.emplace_back(schedule->id(), schedule->options().filter());
      }
    }
    SnapshotSchedulesToTabletsMap result;
    for (const auto& id_and_filter : schedules) {
      auto entries = VERIFY_RESULT(CollectEntries(id_and_filter.second));
      auto& tablets = result[id_and_filter.first];
      for (const auto& entry : entries.entries()) {
        if (entry.type() == SysRowEntry::TABLET) {
          tablets.push_back(entry.id());
        }
      }
      std::sort(tablets.begin(), tablets.end());
    }
    return result;
  }

  void Start() {
    poller_.Start(&context_.Scheduler(), FLAGS_snapshot_coordinator_poll_interval_ms * 1ms);
  }

  void Shutdown() {
    poller_.Shutdown();
  }

 private:
  template <class Pb, class Map>
  CHECKED_STATUS LoadEntry(const Slice& id_slice, const Slice& data, Map* map) REQUIRES(mutex_) {
    VLOG(2) << __func__ << "(" << id_slice.ToDebugString() << ", " << data.ToDebugString() << ")";

    auto id = TryFullyDecodeUuid(id_slice);
    if (id.is_nil()) {
      return Status::OK();
    }
    auto metadata = VERIFY_RESULT(pb_util::ParseFromSlice<Pb>(data));
    return LoadEntry(typename Map::key_type(id), metadata, map);
  }

  template <class Pb, class Map>
  CHECKED_STATUS LoadEntry(
      const typename Map::key_type& id, const Pb& data, Map* map)
      REQUIRES(mutex_) {
    VLOG(1) << __func__ << "(" << id << ", " << data.ShortDebugString() << ")";

    auto new_entry = std::make_unique<typename Map::value_type::element_type>(&context_, id, data);

    auto it = map->find(id);
    if (it == map->end()) {
      map->emplace(std::move(new_entry));
    } else if ((**it).ShouldUpdate(*new_entry)) {
      map->replace(it, std::move(new_entry));
    } else {
      LOG(INFO) << __func__ << " ignore because of version check, existing: "
                << (**it).ToString() << ", loaded: " << new_entry->ToString();
    }

    return Status::OK();
  }

  Result<SnapshotState&> FindSnapshot(const TxnSnapshotId& snapshot_id) REQUIRES(mutex_) {
    auto it = snapshots_.find(snapshot_id);
    if (it == snapshots_.end()) {
      return STATUS(NotFound, "Could not find snapshot", snapshot_id.ToString(),
                    MasterError(MasterErrorPB::SNAPSHOT_NOT_FOUND));
    }
    return **it;
  }

  Result<RestorationState&> FindRestoration(
      const TxnSnapshotRestorationId& restoration_id) REQUIRES(mutex_) {
    auto it = restorations_.find(restoration_id);
    if (it == restorations_.end()) {
      return STATUS(NotFound, "Could not find restoration", restoration_id.ToString(),
                    MasterError(MasterErrorPB::OBJECT_NOT_FOUND));
    }
    return **it;
  }

  Result<SnapshotScheduleState&> FindSnapshotSchedule(
      const SnapshotScheduleId& id) REQUIRES(mutex_) {
    auto it = schedules_.find(id);
    if (it == schedules_.end()) {
      return STATUS(NotFound, "Could not find snapshot schedule", id.ToString(),
                    MasterError(MasterErrorPB::SNAPSHOT_NOT_FOUND));
    }
    return **it;
  }

  void ExecuteOperations(const TabletSnapshotOperations& operations) {
    if (operations.empty()) {
      return;
    }
    VLOG(4) << __func__ << "(" << AsString(operations) << ")";

    size_t num_operations = operations.size();
    std::vector<TabletId> tablet_ids;
    tablet_ids.reserve(num_operations);
    for (const auto& operation : operations) {
      tablet_ids.push_back(operation.tablet_id);
    }
    auto tablet_infos = context_.GetTabletInfos(tablet_ids);
    for (size_t i = 0; i != num_operations; ++i) {
      ExecuteOperation(operations[i], tablet_infos[i]);
    }
  }

  void ExecuteOperation(
      const TabletSnapshotOperation& operation, const TabletInfoPtr& tablet_info) {
    auto callback = MakeDoneCallback(
        &mutex_, snapshots_, operation.snapshot_id, operation.tablet_id,
        std::bind(&Impl::UpdateSnapshot, this, _1, _2));
    if (!tablet_info) {
      callback(STATUS_FORMAT(NotFound, "Tablet info not found for $0", operation.tablet_id));
      return;
    }
    auto snapshot_id_str = operation.snapshot_id.AsSlice().ToBuffer();

    if (operation.state == SysSnapshotEntryPB::DELETING) {
      context_.SendDeleteTabletSnapshotRequest(
          tablet_info, snapshot_id_str, callback);
    } else if (operation.state == SysSnapshotEntryPB::CREATING) {
      context_.SendCreateTabletSnapshotRequest(
          tablet_info, snapshot_id_str, operation.schedule_id, operation.snapshot_hybrid_time,
          callback);
    } else {
      LOG(DFATAL) << "Unsupported snapshot operation: " << operation.ToString();
    }
  }

  struct PollSchedulesData {
    std::vector<TxnSnapshotId> delete_snapshots;
    SnapshotScheduleOperations schedule_operations;
  };

  void Poll() {
    if (!context_.IsLeader()) {
      return;
    }
    VLOG(4) << __func__ << "()";
    std::vector<TxnSnapshotId> cleanup_snapshots;
    TabletSnapshotOperations operations;
    PollSchedulesData schedules_data;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      for (const auto& p : snapshots_) {
        if (p->NeedCleanup()) {
          cleanup_snapshots.push_back(p->id());
        } else {
          p->PrepareOperations(&operations);
        }
      }
      PollSchedulesPrepare(&schedules_data);
    }
    for (const auto& id : cleanup_snapshots) {
      DeleteSnapshot(id);
    }
    ExecuteOperations(operations);
    PollSchedulesComplete(schedules_data);
  }

  void PollSchedulesPrepare(PollSchedulesData* data) REQUIRES(mutex_) {
    auto now = context_.Clock()->Now();
    for (const auto& p : schedules_) {
      auto* first_snapshot = BoundingSnapshot(p->id(), Bound::kFirst);
      auto* last_snapshot = BoundingSnapshot(p->id(), Bound::kLast);
      if (first_snapshot && first_snapshot != last_snapshot) {
        auto gc_limit = now.AddSeconds(-p->options().retention_duration_sec());
        if (first_snapshot->snapshot_hybrid_time() < gc_limit) {
          auto delete_status = first_snapshot->TryStartDelete();
          if (delete_status.ok()) {
            data->delete_snapshots.push_back(first_snapshot->id());
          } else {
            VLOG(1) << "Unable to delete snapshot " << first_snapshot->id() << ": "
                    << delete_status;
          }
        }
      }
      auto last_snapshot_time = last_snapshot ? last_snapshot->snapshot_hybrid_time()
                                              : HybridTime::kInvalid;
      p->PrepareOperations(last_snapshot_time, now, &data->schedule_operations);
    }
  }

  void PollSchedulesComplete(const PollSchedulesData& data) EXCLUDES(mutex_) {
    for (const auto& id : data.delete_snapshots) {
      SubmitDelete(id, nullptr);
    }
    for (const auto& operation : data.schedule_operations) {
      WARN_NOT_OK(ExecuteScheduleOperation(operation),
                  Format("Failed to execute operation on $0", operation.schedule_id));
    }
  }

  SnapshotState* BoundingSnapshot(const SnapshotScheduleId& schedule_id, Bound bound)
      REQUIRES(mutex_) {
    auto& index = snapshots_.get<ScheduleTag>();
    decltype(index.begin()) it;
    if (bound == Bound::kFirst) {
      it = index.lower_bound(schedule_id);
      if (it == index.end()) {
        return nullptr;
      }
    } else {
      it = index.upper_bound(schedule_id);
      if (it == index.begin()) {
        return nullptr;
      }
      --it;
    }
    return (**it).schedule_id() == schedule_id ? it->get() : nullptr;
  }

  HybridTime LastSnapshotTime(const SnapshotScheduleId& schedule_id) REQUIRES(mutex_) {
    auto snapshot = BoundingSnapshot(schedule_id, Bound::kLast);
    return snapshot ? snapshot->snapshot_hybrid_time() : HybridTime::kInvalid;
  }

  void DeleteSnapshot(const TxnSnapshotId& snapshot_id) {
    docdb::KeyValueWriteBatchPB write_batch;

    auto encoded_key = EncodedSnapshotKey(snapshot_id, &context_);
    if (!encoded_key.ok()) {
      LOG(DFATAL) << "Failed to encode id for deletion: " << encoded_key.status();
      return;
    }
    auto pair = write_batch.add_write_pairs();
    pair->set_key(encoded_key->AsSlice().cdata(), encoded_key->size());
    char value = { docdb::ValueTypeAsChar::kTombstone };
    pair->set_value(&value, 1);

    SubmitWrite(std::move(write_batch), &context_);
  }

  CHECKED_STATUS ExecuteScheduleOperation(const SnapshotScheduleOperation& operation) {
    auto entries = VERIFY_RESULT(CollectEntries(operation.filter));
    RETURN_NOT_OK(SubmitCreate(
        entries, false, operation.schedule_id, operation.previous_snapshot_hybrid_time,
        operation.snapshot_id,
        tablet::MakeFunctorOperationCompletionCallback(
            [this, schedule_id = operation.schedule_id, snapshot_id = operation.snapshot_id](
                const Status& status) {
          if (!status.ok()) {
            CreateSnapshotAborted(status, schedule_id, snapshot_id);
          }
        })));
    return Status::OK();
  }

  void CreateSnapshotAborted(
      const Status& status, const SnapshotScheduleId& schedule_id,
      const TxnSnapshotId& snapshot_id) {
    LOG(INFO) << __func__ << " for " << schedule_id << ", snapshot: " << snapshot_id
              << ", status: " << status;
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = schedules_.find(schedule_id);
    if (it == schedules_.end()) {
      return;
    }
    (**it).SnapshotFinished(snapshot_id, status);
  }

  Result<TxnSnapshotId> SubmitCreate(
      const SysRowEntries& entries, bool imported, const SnapshotScheduleId& schedule_id,
      HybridTime previous_snapshot_hybrid_time, TxnSnapshotId snapshot_id,
      std::unique_ptr<tablet::OperationCompletionCallback> completion_clbk) {
    auto operation_state = std::make_unique<tablet::SnapshotOperationState>(/* tablet= */ nullptr);
    auto request = operation_state->AllocateRequest();

    VLOG(1) << __func__ << "(" << AsString(entries) << ", " << imported << ", " << schedule_id
            << ", " << snapshot_id << ")";
    for (const auto& entry : entries.entries()) {
      if (entry.type() == SysRowEntry::TABLET) {
        request->add_tablet_id(entry.id());
      }
    }

    request->set_snapshot_hybrid_time(context_.Clock()->MaxGlobalNow().ToUint64());
    request->set_operation(tserver::TabletSnapshotOpRequestPB::CREATE_ON_MASTER);
    if (!snapshot_id) {
      snapshot_id = TxnSnapshotId::GenerateRandom();
    }
    request->set_snapshot_id(snapshot_id.data(), snapshot_id.size());
    request->set_imported(imported);
    if (schedule_id) {
      request->set_schedule_id(schedule_id.data(), schedule_id.size());
    }
    if (previous_snapshot_hybrid_time) {
      request->set_previous_snapshot_hybrid_time(previous_snapshot_hybrid_time.ToUint64());
    }

    request->mutable_extra_data()->PackFrom(entries);

    operation_state->set_completion_callback(std::move(completion_clbk));
    auto operation = std::make_unique<tablet::SnapshotOperation>(std::move(operation_state));

    context_.Submit(std::move(operation));

    return snapshot_id;
  }

  void SubmitDelete(const TxnSnapshotId& snapshot_id,
                    const std::shared_ptr<Synchronizer>& synchronizer) {
    auto operation_state = std::make_unique<tablet::SnapshotOperationState>(nullptr);
    auto request = operation_state->AllocateRequest();

    request->set_operation(tserver::TabletSnapshotOpRequestPB::DELETE_ON_MASTER);
    request->set_snapshot_id(snapshot_id.data(), snapshot_id.size());

    operation_state->set_completion_callback(tablet::MakeFunctorOperationCompletionCallback(
        [this, wsynchronizer = std::weak_ptr<Synchronizer>(synchronizer), snapshot_id]
        (const Status& status) {
          auto synchronizer = wsynchronizer.lock();
          if (synchronizer) {
            synchronizer->StatusCB(status);
          }
          if (!status.ok()) {
            DeleteSnapshotAborted(status, snapshot_id);
          }
        }));
    auto operation = std::make_unique<tablet::SnapshotOperation>(std::move(operation_state));

    context_.Submit(std::move(operation));
  }

  void SubmitRestore(const TxnSnapshotId& snapshot_id, HybridTime restore_at,
                     const TxnSnapshotRestorationId& restoration_id,
                     const std::shared_ptr<Synchronizer>& synchronizer) {
    auto operation_state = std::make_unique<tablet::SnapshotOperationState>(nullptr);
    auto request = operation_state->AllocateRequest();

    request->set_operation(tserver::TabletSnapshotOpRequestPB::RESTORE_SYS_CATALOG);
    request->set_snapshot_id(snapshot_id.data(), snapshot_id.size());
    request->set_snapshot_hybrid_time(restore_at.ToUint64());
    if (restoration_id) {
      request->set_restoration_id(restoration_id.data(), restoration_id.size());
    }

    operation_state->set_completion_callback(std::make_unique<
        tablet::WeakSynchronizerOperationCompletionCallback>(synchronizer));
    auto operation = std::make_unique<tablet::SnapshotOperation>(std::move(operation_state));

    context_.Submit(std::move(operation));
  }

  void DeleteSnapshotAborted(
      const Status& status, const TxnSnapshotId& snapshot_id) {
    LOG(INFO) << __func__ << ", snapshot: " << snapshot_id << ", status: " << status;
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = snapshots_.find(snapshot_id);
    if (it == snapshots_.end()) {
      return;
    }
    (**it).DeleteAborted(status);
  }

  void UpdateSnapshot(SnapshotState* snapshot, std::unique_lock<std::mutex>* lock)
      REQUIRES(mutex_) {
    if (!snapshot->AllTabletsDone()) {
      return;
    }

    if (snapshot->schedule_id()) {
      UpdateSchedule(*snapshot);
    }

    docdb::KeyValueWriteBatchPB write_batch;
    auto status = snapshot->StoreToWriteBatch(&write_batch);
    if (!status.ok()) {
      LOG(DFATAL) << "Failed to prepare write batch for snapshot: " << status;
      return;
    }
    lock->unlock();

    SubmitWrite(std::move(write_batch), &context_);
  };

  void UpdateSchedule(const SnapshotState& snapshot) REQUIRES(mutex_) {
    auto it = schedules_.find(snapshot.schedule_id());
    if (it == schedules_.end()) {
      return;
    }

    auto state = snapshot.AggregatedState();
    Status status;
    if (!state.ok()) {
      status = state.status();
    } else {
      switch (*state) {
        case SysSnapshotEntryPB::COMPLETE:
          status = Status::OK();
          break;
        case SysSnapshotEntryPB::FAILED:
          status = snapshot.AnyFailure();
          break;
        case SysSnapshotEntryPB::DELETED:
          return;
        default:
          LOG(DFATAL) << "Unexpected snapshot state: " << *state << " for " << snapshot.id();
          return;
      }
    }
    (**it).SnapshotFinished(snapshot.id(), status);
  }

  CHECKED_STATUS FillSchedule(const SnapshotScheduleState& schedule, SnapshotScheduleInfoPB* out)
      REQUIRES(mutex_) {
    RETURN_NOT_OK(schedule.ToPB(out));
    const auto& index = snapshots_.get<ScheduleTag>();
    auto p = index.equal_range(boost::make_tuple(schedule.id()));
    for (auto i = p.first; i != p.second; ++i) {
      RETURN_NOT_OK((**i).ToPB(out->add_snapshots()));
    }
    return Status::OK();
  }

  Result<SysRowEntries> CollectEntries(const SnapshotScheduleFilterPB& filter) {
    return context_.CollectEntries(filter.tables().tables(), true, true, true);
  }

  CHECKED_STATUS DoRestore(
      const TxnSnapshotId& snapshot_id, HybridTime restore_at,
      const TxnSnapshotRestorationId& restoration_id, RestorePhase phase) {
    TabletInfos tablet_infos;
    bool restore_sys_catalog;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      SnapshotState& snapshot = VERIFY_RESULT(FindSnapshot(snapshot_id));
      if (!VERIFY_RESULT(snapshot.Complete())) {
        return STATUS(IllegalState, "The snapshot state is not complete", snapshot_id.ToString(),
                      MasterError(MasterErrorPB::SNAPSHOT_IS_NOT_READY));
      }
      restore_sys_catalog = phase == RestorePhase::kInitial && !snapshot.schedule_id().IsNil();
      RestorationState* restoration_ptr;
      if (phase == RestorePhase::kInitial) {
        auto restoration = std::make_unique<RestorationState>(&context_, restoration_id, &snapshot);
        restoration_ptr = restorations_.emplace(std::move(restoration)).first->get();
      } else {
        restoration_ptr = &VERIFY_RESULT(FindRestoration(restoration_id)).get();
      }
      if (!restore_sys_catalog) {
        tablet_infos = restoration_ptr->PrepareOperations();
      }
    }

    // If sys catalog is restored, then tablets data will be restored after that using postponed
    // restores.
    if (restore_sys_catalog) {
      SubmitRestore(snapshot_id, restore_at, restoration_id, nullptr);
    } else {
      auto snapshot_id_str = snapshot_id.AsSlice().ToBuffer();
      SendMetadata send_metadata(phase == RestorePhase::kPostSysCatalogLoad);
      for (const auto& tablet : tablet_infos) {
        context_.SendRestoreTabletSnapshotRequest(
            tablet, snapshot_id_str, restore_at, send_metadata,
            MakeDoneCallback(&mutex_, restorations_, restoration_id, tablet->tablet_id()));
      }
    }

    return Status::OK();
  }

  SnapshotCoordinatorContext& context_;
  std::mutex mutex_;
  class ScheduleTag;
  using Snapshots = boost::multi_index_container<
      std::unique_ptr<SnapshotState>,
      boost::multi_index::indexed_by<
          // Access snapshots by id.
          boost::multi_index::hashed_unique<
              boost::multi_index::const_mem_fun<
                  SnapshotState, const TxnSnapshotId&, &SnapshotState::id>
          >,
          // Group snapshots by schedule id. Ordered by hybrid time for the same schedule.
          boost::multi_index::ordered_non_unique<
              boost::multi_index::tag<ScheduleTag>,
              boost::multi_index::composite_key<
                  SnapshotState,
                  boost::multi_index::const_mem_fun<
                      SnapshotState, const SnapshotScheduleId&, &SnapshotState::schedule_id>,
                  boost::multi_index::const_mem_fun<
                      SnapshotState, HybridTime, &SnapshotState::snapshot_hybrid_time>
              >
          >
      >
  >;
  // For restorations and schedules we have to use multi_index since there are template
  // functions that expect same interface for those collections.
  using Restorations = boost::multi_index_container<
      std::unique_ptr<RestorationState>,
      boost::multi_index::indexed_by<
          boost::multi_index::hashed_unique<
              boost::multi_index::const_mem_fun<
                  RestorationState, const TxnSnapshotRestorationId&,
                  &RestorationState::restoration_id>
          >
      >
  >;
  using Schedules = boost::multi_index_container<
      std::unique_ptr<SnapshotScheduleState>,
      boost::multi_index::indexed_by<
          boost::multi_index::hashed_unique<
              boost::multi_index::const_mem_fun<
                  SnapshotScheduleState, const SnapshotScheduleId&, &SnapshotScheduleState::id>
          >
      >
  >;

  Snapshots snapshots_ GUARDED_BY(mutex_);
  Restorations restorations_ GUARDED_BY(mutex_);
  Schedules schedules_ GUARDED_BY(mutex_);
  rpc::Poller poller_;

  // Restores postponed until sys catalog is reloaed.
  struct PostponedRestore {
    int64_t term = OpId::kUnknownTerm;
    TxnSnapshotId snapshot_id = TxnSnapshotId::Nil();
    HybridTime restore_at;
    TxnSnapshotRestorationId restoration_id = TxnSnapshotRestorationId::Nil();
  };
  std::vector<PostponedRestore> postponed_restores_ GUARDED_BY(mutex_);
};

MasterSnapshotCoordinator::MasterSnapshotCoordinator(SnapshotCoordinatorContext* context)
    : impl_(new Impl(context)) {}

MasterSnapshotCoordinator::~MasterSnapshotCoordinator() {}

Result<TxnSnapshotId> MasterSnapshotCoordinator::Create(
    const SysRowEntries& entries, bool imported, CoarseTimePoint deadline) {
  return impl_->Create(entries, imported, deadline);
}

Status MasterSnapshotCoordinator::CreateReplicated(
    int64_t leader_term, const tablet::SnapshotOperationState& state) {
  return impl_->CreateReplicated(leader_term, state);
}

Status MasterSnapshotCoordinator::DeleteReplicated(
    int64_t leader_term, const tablet::SnapshotOperationState& state) {
  return impl_->DeleteReplicated(leader_term, state);
}

Status MasterSnapshotCoordinator::RestoreSysCatalogReplicated(
    int64_t leader_term, const tablet::SnapshotOperationState& state) {
  return impl_->RestoreSysCatalogReplicated(leader_term, state);
}

Status MasterSnapshotCoordinator::ListSnapshots(
    const TxnSnapshotId& snapshot_id, bool list_deleted, ListSnapshotsResponsePB* resp) {
  return impl_->ListSnapshots(snapshot_id, list_deleted, resp);
}

Status MasterSnapshotCoordinator::Delete(
    const TxnSnapshotId& snapshot_id, CoarseTimePoint deadline) {
  return impl_->Delete(snapshot_id, deadline);
}

Result<TxnSnapshotRestorationId> MasterSnapshotCoordinator::Restore(
    const TxnSnapshotId& snapshot_id, HybridTime restore_at) {
  return impl_->Restore(snapshot_id, restore_at);
}

Status MasterSnapshotCoordinator::ListRestorations(
    const TxnSnapshotRestorationId& restoration_id, const TxnSnapshotId& snapshot_id,
    ListSnapshotRestorationsResponsePB* resp) {
  return impl_->ListRestorations(restoration_id, snapshot_id, resp);
}

Result<SnapshotScheduleId> MasterSnapshotCoordinator::CreateSchedule(
    const CreateSnapshotScheduleRequestPB& request, CoarseTimePoint deadline) {
  return impl_->CreateSchedule(request, deadline);
}

Status MasterSnapshotCoordinator::ListSnapshotSchedules(
    const SnapshotScheduleId& snapshot_schedule_id, ListSnapshotSchedulesResponsePB* resp) {
  return impl_->ListSnapshotSchedules(snapshot_schedule_id, resp);
}

Status MasterSnapshotCoordinator::Load(tablet::Tablet* tablet) {
  return impl_->Load(tablet);
}

void MasterSnapshotCoordinator::Start() {
  impl_->Start();
}

void MasterSnapshotCoordinator::Shutdown() {
  impl_->Shutdown();
}

Status MasterSnapshotCoordinator::ApplyWritePair(const Slice& key, const Slice& value) {
  return impl_->ApplyWritePair(key, value);
}

Status MasterSnapshotCoordinator::FillHeartbeatResponse(TSHeartbeatResponsePB* resp) {
  return impl_->FillHeartbeatResponse(resp);
}

Result<SnapshotSchedulesToTabletsMap>
    MasterSnapshotCoordinator::MakeSnapshotSchedulesToTabletsMap() {
  return impl_->MakeSnapshotSchedulesToTabletsMap();
}

void MasterSnapshotCoordinator::SysCatalogLoaded(int64_t term) {
  impl_->SysCatalogLoaded(term);
}

} // namespace master
} // namespace yb
