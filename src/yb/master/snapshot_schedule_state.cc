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

#include "yb/master/snapshot_schedule_state.h"

#include "yb/docdb/docdb.pb.h"
#include "yb/docdb/key_bytes.h"

#include "yb/master/catalog_entity_info.h"
#include "yb/master/snapshot_coordinator_context.h"

#include "yb/util/pb_util.h"

DECLARE_uint64(snapshot_coordinator_cleanup_delay_ms);

namespace yb {
namespace master {

SnapshotScheduleState::SnapshotScheduleState(
    SnapshotCoordinatorContext* context, const CreateSnapshotScheduleRequestPB &req)
    : context_(*context), id_(SnapshotScheduleId::GenerateRandom()), options_(req.options()) {
}

SnapshotScheduleState::SnapshotScheduleState(
    SnapshotCoordinatorContext* context, const SnapshotScheduleId& id,
    const SnapshotScheduleOptionsPB& options)
    : context_(*context), id_(id), options_(options) {
}

Result<docdb::KeyBytes> SnapshotScheduleState::EncodedKey(
    const SnapshotScheduleId& schedule_id, SnapshotCoordinatorContext* context) {
  return master::EncodedKey(SysRowEntry::SNAPSHOT_SCHEDULE, schedule_id.AsSlice(), context);
}

Result<docdb::KeyBytes> SnapshotScheduleState::EncodedKey() const {
  return EncodedKey(id_, &context_);
}

Status SnapshotScheduleState::StoreToWriteBatch(docdb::KeyValueWriteBatchPB* out) const {
  auto encoded_key = VERIFY_RESULT(EncodedKey());
  auto pair = out->add_write_pairs();
  pair->set_key(encoded_key.AsSlice().cdata(), encoded_key.size());
  auto* value = pair->mutable_value();
  value->push_back(docdb::ValueTypeAsChar::kString);
  pb_util::AppendPartialToString(options_, value);
  return Status::OK();
}

Status SnapshotScheduleState::ToPB(SnapshotScheduleInfoPB* pb) const {
  pb->set_id(id_.data(), id_.size());
  *pb->mutable_options() = options_;
  return Status::OK();
}

std::string SnapshotScheduleState::ToString() const {
  return YB_CLASS_TO_STRING(id, options);
}

bool SnapshotScheduleState::deleted() const {
  return HybridTime::FromPB(options_.delete_time()).is_valid();
}

void SnapshotScheduleState::PrepareOperations(
    HybridTime last_snapshot_time, HybridTime now, SnapshotScheduleOperations* operations) {
  if (creating_snapshot_id_) {
    return;
  }
  auto delete_time = HybridTime::FromPB(options_.delete_time());
  if (delete_time) {
    // Check whether we are ready to cleanup deleted schedule.
    if (now > delete_time.AddMilliseconds(FLAGS_snapshot_coordinator_cleanup_delay_ms)) {
      operations->push_back(SnapshotScheduleOperation {
        .type = SnapshotScheduleOperationType::kCleanup,
        .schedule_id = id_,
        .snapshot_id = TxnSnapshotId::Nil(),
      });
    }
    return;
  }
  if (last_snapshot_time && last_snapshot_time.AddSeconds(options_.interval_sec()) > now) {
    // Time from the last snapshot did not passed yet.
    return;
  }
  operations->push_back(MakeCreateSnapshotOperation(last_snapshot_time));
}

SnapshotScheduleOperation SnapshotScheduleState::MakeCreateSnapshotOperation(
    HybridTime last_snapshot_time) {
  creating_snapshot_id_ = TxnSnapshotId::GenerateRandom();
  return SnapshotScheduleOperation {
    .type = SnapshotScheduleOperationType::kCreateSnapshot,
    .schedule_id = id_,
    .snapshot_id = creating_snapshot_id_,
    .filter = options_.filter(),
    .previous_snapshot_hybrid_time = last_snapshot_time,
  };
}

Result<SnapshotScheduleOperation> SnapshotScheduleState::ForceCreateSnapshot(
    HybridTime last_snapshot_time) {
  if (creating_snapshot_id_) {
    return STATUS_EC_FORMAT(
        IllegalState, MasterError(MasterErrorPB::PARALLEL_SNAPSHOT_OPERATION),
        "Creating snapshot in progress: $0", creating_snapshot_id_);
  }
  return MakeCreateSnapshotOperation(last_snapshot_time);
}

void SnapshotScheduleState::SnapshotFinished(
    const TxnSnapshotId& snapshot_id, const Status& status) {
  if (creating_snapshot_id_ != snapshot_id) {
    return;
  }
  creating_snapshot_id_ = TxnSnapshotId::Nil();
}

} // namespace master
} // namespace yb
