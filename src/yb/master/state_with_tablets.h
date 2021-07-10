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

#ifndef YB_MASTER_STATE_WITH_TABLETS_H
#define YB_MASTER_STATE_WITH_TABLETS_H

#include <boost/iterator/transform_iterator.hpp>

#include <boost/multi_index_container.hpp>
#include <boost/multi_index/hashed_index.hpp>
#include <boost/multi_index/member.hpp>
#include <boost/multi_index/ordered_index.hpp>

#include "yb/common/entity_ids.h"

#include "yb/master/master_fwd.h"
#include "yb/master/master_backup.pb.h"

#include "yb/util/monotime.h"
#include "yb/util/result.h"

namespace yb {
namespace master {

class StateWithTablets {
 public:
  StateWithTablets(
      SnapshotCoordinatorContext* context, SysSnapshotEntryPB::State initial_state);

  virtual ~StateWithTablets() = default;

  StateWithTablets(const StateWithTablets&) = delete;
  void operator=(const StateWithTablets&) = delete;

  SnapshotCoordinatorContext& context() const {
    return context_;
  }

  SysSnapshotEntryPB::State initial_state() const {
    return initial_state_;
  }

  // If any of tablets failed returns this failure.
  // Otherwise if any of tablets is in initial state returns initial state.
  // Otherwise all tablets should be in the same state, which is returned.
  Result<SysSnapshotEntryPB::State> AggregatedState() const;

  CHECKED_STATUS AnyFailure() const;
  Result<bool> Complete() const;
  bool AllTabletsDone() const;
  bool PassedSinceCompletion(const MonoDelta& duration) const;
  std::vector<TabletId> TabletIdsInState(SysSnapshotEntryPB::State state);
  void Done(const TabletId& tablet_id, const Status& status);
  bool AllInState(SysSnapshotEntryPB::State state);
  bool HasInState(SysSnapshotEntryPB::State state);
  void SetInitialTabletsState(SysSnapshotEntryPB::State state);

  // Initialize tablet states from serialized data.
  void InitTablets(
      const google::protobuf::RepeatedPtrField<SysSnapshotEntryPB::TabletSnapshotPB>& tablets);

  template <class TabletIds>
  void InitTabletIds(const TabletIds& tablet_ids, SysSnapshotEntryPB::State state) {
    tablets_.clear();
    for (const auto& id : tablet_ids) {
      tablets_.emplace(id, state);
    }
    num_tablets_in_initial_state_ = state == initial_state_ ? tablet_ids.size() : 0;
    CheckCompleteness();
  }

  // Initialize tablet states using tablet ids, i.e. put all tablets in initial state.
  template <class TabletIds>
  void InitTabletIds(const TabletIds& tablet_ids) {
    InitTabletIds(tablet_ids, initial_state_);
  }

  template <class PB>
  void TabletsToPB(google::protobuf::RepeatedPtrField<PB>* out) {
    out->Reserve(tablets_.size());
    for (const auto& tablet : tablets_) {
      auto* tablet_state = out->Add();
      tablet_state->set_id(tablet.id);
      tablet_state->set_state(tablet.state);
    }
  }

  // Invoking callback for all operations that are not running and are still in the initial state.
  // Marking such operations as running.
  template <class Functor>
  void DoPrepareOperations(const Functor& functor) {
    auto& running_index = tablets_.get<RunningTag>();
    for (auto it = running_index.begin(); it != running_index.end();) {
      if (it->running) {
        // Could exit here, because we have already iterated over all non-running operations.
        break;
      }
      bool should_run = it->state == initial_state_;
      if (should_run) {
        VLOG(4) << "Prepare operation for " << it->ToString();
        functor(*it);

        // Here we modify indexed value, so iterator could be advanced to the next element.
        // Taking next before modify.
        auto new_it = it;
        ++new_it;
        running_index.modify(it, [](TabletData& data) { data.running = true; });
        it = new_it;
      } else {
        ++it;
      }
    }
  }

  void RemoveTablets(const std::vector<std::string>& tablet_ids);

  virtual bool IsTerminalFailure(const Status& status) = 0;

  auto tablet_ids() const {
    auto lambda = [](const TabletData& data) { return data.id; };
    return boost::make_iterator_range(
        boost::make_transform_iterator(tablets_.begin(), lambda),
        boost::make_transform_iterator(tablets_.end(), lambda));
  }

 protected:
  struct TabletData {
    TabletId id;
    SysSnapshotEntryPB::State state;
    Status last_error;
    bool running = false;

    TabletData(const TabletId& id_, SysSnapshotEntryPB::State state_)
        : id(id_), state(state_) {
    }

    std::string ToString() const {
      return YB_STRUCT_TO_STRING(id, state, last_error, running);
    }
  };

  const std::string& InitialStateName() const;

  class RunningTag;

  typedef boost::multi_index_container<
    TabletData,
    boost::multi_index::indexed_by<
      boost::multi_index::hashed_unique<
        boost::multi_index::member<TabletData, TabletId, &TabletData::id>
      >,
      boost::multi_index::ordered_non_unique<
        boost::multi_index::tag<RunningTag>,
        boost::multi_index::member<TabletData, bool, &TabletData::running>
      >
    >
  > Tablets;

  const Tablets& tablets() const {
    return tablets_;
  }

 private:
  void CheckCompleteness();

  SnapshotCoordinatorContext& context_;
  SysSnapshotEntryPB::State initial_state_;

  Tablets tablets_;

  size_t num_tablets_in_initial_state_ = 0;
  // Time when last tablet were transferred from initial state.
  CoarseTimePoint complete_at_;
};

} // namespace master
} // namespace yb

#endif  // YB_MASTER_STATE_WITH_TABLETS_H
