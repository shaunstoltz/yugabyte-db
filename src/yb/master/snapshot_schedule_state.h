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

#ifndef YB_MASTER_SNAPSHOT_SCHEDULE_STATE_H
#define YB_MASTER_SNAPSHOT_SCHEDULE_STATE_H

#include "yb/common/hybrid_time.h"
#include "yb/common/snapshot.h"

#include "yb/docdb/docdb_fwd.h"

#include "yb/master/master_fwd.h"
#include "yb/master/master_backup.pb.h"

namespace yb {
namespace master {

struct SnapshotScheduleOperation {
  SnapshotScheduleId schedule_id;
  SnapshotScheduleFilterPB filter;
  TxnSnapshotId snapshot_id;
  HybridTime previous_snapshot_hybrid_time;
};

using SnapshotScheduleOperations = std::vector<SnapshotScheduleOperation>;

class SnapshotScheduleState {
 public:
  SnapshotScheduleState(
      SnapshotCoordinatorContext* context, const CreateSnapshotScheduleRequestPB& req);

  SnapshotScheduleState(
      SnapshotCoordinatorContext* context, const SnapshotScheduleId& id,
      const SnapshotScheduleOptionsPB& options);

  const SnapshotScheduleId& id() const {
    return id_;
  }

  bool ShouldUpdate(const SnapshotScheduleState& other) const {
    return true;
  }

  const SnapshotScheduleOptionsPB& options() const {
    return options_;
  }

  void PrepareOperations(
      HybridTime last_snapshot_time, HybridTime now, SnapshotScheduleOperations* operations);
  void SnapshotFinished(const TxnSnapshotId& snapshot_id, const Status& status);

  CHECKED_STATUS StoreToWriteBatch(docdb::KeyValueWriteBatchPB* write_batch);
  CHECKED_STATUS ToPB(SnapshotScheduleInfoPB* pb) const;
  std::string ToString() const;

 private:
  SnapshotCoordinatorContext& context_;
  SnapshotScheduleId id_;
  SnapshotScheduleOptionsPB options_;

  // When snapshot is being created for this schedule, this field contains id of this snapshot.
  // To prevent creating other snapshots during that time.
  TxnSnapshotId creating_snapshot_id_ = TxnSnapshotId::Nil();
};

} // namespace master
} // namespace yb

#endif  // YB_MASTER_SNAPSHOT_SCHEDULE_STATE_H
