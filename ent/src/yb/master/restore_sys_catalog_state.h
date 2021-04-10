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

#ifndef ENT_SRC_YB_MASTER_RESTORE_SYS_CATALOG_STATE_H
#define ENT_SRC_YB_MASTER_RESTORE_SYS_CATALOG_STATE_H

#include <unordered_map>

#include "yb/common/entity_ids.h"

#include "yb/master/master_fwd.h"
#include "yb/master/master.pb.h"

#include "yb/util/result.h"

namespace yb {
namespace master {

// Utility class to restore sys catalog.
// Initially we load tables and tablets into it, then match schedule filter.
class RestoreSysCatalogState {
 public:
  CHECKED_STATUS LoadTable(const Slice& id, const Slice& data);
  CHECKED_STATUS LoadTablet(const Slice& id, const Slice& data);
  Result<SysRowEntries> FilterEntries(const SnapshotScheduleFilterPB& filter);

 private:
  Result<bool> MatchTable(
      const SnapshotScheduleFilterPB& filter, const TableId& id, const SysTablesEntryPB& table);

  std::unordered_map<TableId, SysTablesEntryPB> tables_;
  std::unordered_map<TabletId, SysTabletsEntryPB> tablets_;
};

}  // namespace master
}  // namespace yb

#endif // ENT_SRC_YB_MASTER_RESTORE_SYS_CATALOG_STATE_H
