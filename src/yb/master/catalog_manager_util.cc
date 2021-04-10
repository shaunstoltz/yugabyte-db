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

#include "yb/master/catalog_manager_util.h"

#include <gflags/gflags.h>

#include "yb/util/flag_tags.h"
#include "yb/util/logging.h"
#include "yb/util/math_util.h"
#include "yb/util/string_util.h"

DEFINE_double(balancer_load_max_standard_deviation, 2.0,
              "The standard deviation among the tserver load, above which that distribution "
              "is considered not balanced.");
TAG_FLAG(balancer_load_max_standard_deviation, advanced);

namespace yb {
namespace master {

using strings::Substitute;

Status CatalogManagerUtil::IsLoadBalanced(const master::TSDescriptorVector& ts_descs) {
  ZoneToDescMap zone_to_ts;
  RETURN_NOT_OK(GetPerZoneTSDesc(ts_descs, &zone_to_ts));

  for (const auto& zone : zone_to_ts) {
    if (zone.second.size() <= 1) {
      continue;
    }

    // Map from placement uuid to tserver load vector.
    std::map<string, vector<double>> load;
    for (const auto &ts_desc : zone.second) {
      (load[ts_desc->placement_uuid()]).push_back(ts_desc->num_live_replicas());
    }

    for (const auto& entry : load) {
      double std_dev = yb::standard_deviation(entry.second);
      LOG(INFO) << "Load standard deviation is " << std_dev << " for "
                << entry.second.size() << " tservers in placement " << zone.first
                << " for placement uuid " << entry.first;

      if (std_dev >= FLAGS_balancer_load_max_standard_deviation) {
        return STATUS(IllegalState, Substitute("Load not balanced: deviation=$0 in $1 for "
                                               "placement uuid $2.",
                                               std_dev, zone.first, entry.first));
      }
    }
  }
  return Status::OK();
}

Status CatalogManagerUtil::AreLeadersOnPreferredOnly(
    const TSDescriptorVector& ts_descs,
    const ReplicationInfoPB& replication_info,
    const vector<scoped_refptr<TableInfo>>& tables) {
  if (PREDICT_FALSE(ts_descs.empty())) {
    return Status::OK();
  }

  // Variables for checking transaction leader spread.
  auto num_servers = ts_descs.size();
  std::map<std::string, int> txn_map;
  int num_txn_tablets = 0;
  int max_txn_leaders_per_node = 0;
  int min_txn_leaders_per_node = 0;

  if (!FLAGS_transaction_tables_use_preferred_zones) {
    CalculateTxnLeaderMap(&txn_map, &num_txn_tablets, tables);
    max_txn_leaders_per_node = num_txn_tablets / num_servers;
    min_txn_leaders_per_node = max_txn_leaders_per_node;
    if (num_txn_tablets % num_servers) {
      ++max_txn_leaders_per_node;
    }
  }

  // If transaction_tables_use_preferred_zones = true, don't check for transaction leader spread.
  // This results in txn_map being empty, num_txn_tablets = 0, max_txn_leaders_per_node = 0, and
  // system_tablets_leaders = 0.
  // Thus all comparisons for transaction leader spread will be ignored (all 0 < 0, etc).

  for (const auto& ts_desc : ts_descs) {
    auto tserver = txn_map.find(ts_desc->permanent_uuid());
    int system_tablets_leaders = 0;
    if (!(tserver == txn_map.end())) {
      system_tablets_leaders = tserver->second;
    }

    // If enabled, check if transaction tablet leaders are evenly spread.
    if (system_tablets_leaders > max_txn_leaders_per_node) {
      return STATUS(
          IllegalState,
          Substitute("Too many txn status leaders found on tserver $0. Found $1, Expected $2.",
                      ts_desc->permanent_uuid(),
                      system_tablets_leaders,
                      max_txn_leaders_per_node));
    }
    if (system_tablets_leaders < min_txn_leaders_per_node) {
      return STATUS(
          IllegalState,
          Substitute("Tserver $0 expected to have at least $1 txn status leader(s), but has $2.",
                      ts_desc->permanent_uuid(),
                      min_txn_leaders_per_node,
                      system_tablets_leaders));
    }

    // Check that leaders are on preferred ts only.
    // If transaction tables follow preferred nodes, then we verify that there are 0 leaders.
    // Otherwise, we need to check that there are 0 non-txn leaders on the ts.
    if (!ts_desc->IsAcceptingLeaderLoad(replication_info) &&
        ts_desc->leader_count() > system_tablets_leaders) {
      // This is a ts that shouldn't have leader load (asides from txn leaders) but does.
      return STATUS(
          IllegalState,
          Substitute("Expected no leader load on tserver $0, found $1.",
                     ts_desc->permanent_uuid(), ts_desc->leader_count() - system_tablets_leaders));
    }
  }
  return Status::OK();
}

void CatalogManagerUtil::CalculateTxnLeaderMap(std::map<std::string, int>* txn_map,
                                               int* num_txn_tablets,
                                               vector<scoped_refptr<TableInfo>> tables) {
  for (const auto& table : tables) {
    bool is_txn_table = table->GetTableType() == TRANSACTION_STATUS_TABLE_TYPE;
    if (!is_txn_table) {
      continue;
    }
    TabletInfos tablets;
    table->GetAllTablets(&tablets);
    (*num_txn_tablets) += tablets.size();
    for (const auto& tablet : tablets) {
      auto replication_locations = tablet->GetReplicaLocations();
      for (const auto& replica : *replication_locations) {
        if (replica.second.role == consensus::RaftPeerPB_Role_LEADER) {
          (*txn_map)[replica.first]++;
        }
      }
    }
  }
}

Status CatalogManagerUtil::GetPerZoneTSDesc(const TSDescriptorVector& ts_descs,
                                            ZoneToDescMap* zone_to_ts) {
  if (zone_to_ts == nullptr) {
    return STATUS(InvalidArgument, "Need a non-null zone to tsdesc map that will be filled in.");
  }
  zone_to_ts->clear();
  for (const auto& ts_desc : ts_descs) {
    string placement_id = ts_desc->placement_id();
    auto iter = zone_to_ts->find(placement_id);
    if (iter == zone_to_ts->end()) {
      (*zone_to_ts)[placement_id] = {ts_desc};
    } else {
      iter->second.push_back(ts_desc);
    }
  }
  return Status::OK();
}

bool CatalogManagerUtil::IsCloudInfoEqual(const CloudInfoPB& lhs, const CloudInfoPB& rhs) {
  return (lhs.placement_cloud() == rhs.placement_cloud() &&
          lhs.placement_region() == rhs.placement_region() &&
          lhs.placement_zone() == rhs.placement_zone());
}

Status CatalogManagerUtil::DoesPlacementInfoContainCloudInfo(const PlacementInfoPB& placement_info,
                                                             const CloudInfoPB& cloud_info) {
  for (const auto& placement_block : placement_info.placement_blocks()) {
    if (IsCloudInfoEqual(placement_block.cloud_info(), cloud_info)) {
      return Status::OK();
    }
  }
  return STATUS_SUBSTITUTE(InvalidArgument, "Placement info $0 does not contain cloud info $1",
                           placement_info.DebugString(),
                           TSDescriptor::generate_placement_id(cloud_info));
}

Result<std::string> CatalogManagerUtil::GetPlacementUuidFromRaftPeer(
    const ReplicationInfoPB& replication_info, const consensus::RaftPeerPB& peer) {
  switch (peer.member_type()) {
    case consensus::RaftPeerPB::PRE_VOTER:
    case consensus::RaftPeerPB::VOTER: {
      // This peer is a live replica.
      return replication_info.live_replicas().placement_uuid();
    }
    case consensus::RaftPeerPB::PRE_OBSERVER:
    case consensus::RaftPeerPB::OBSERVER: {
      // This peer is a read replica.
      std::vector<std::string> placement_uuid_matches;
      for (const auto& placement_info : replication_info.read_replicas()) {
        if (CatalogManagerUtil::DoesPlacementInfoContainCloudInfo(
            placement_info, peer.cloud_info()).ok()) {
          placement_uuid_matches.push_back(placement_info.placement_uuid());
        }
      }

      if (placement_uuid_matches.size() != 1) {
        return STATUS(IllegalState, Format("Expect 1 placement match for peer $0, found $1: $2",
                                           peer.ShortDebugString(), placement_uuid_matches.size(),
                                           VectorToString(placement_uuid_matches)));
      }

      return placement_uuid_matches.front();
    }
    case consensus::RaftPeerPB::UNKNOWN_MEMBER_TYPE: {
      return STATUS(IllegalState, Format("Member type unknown for peer $0",
                                         peer.ShortDebugString()));
    }
    default:
      return STATUS(IllegalState, "Unhandled raft state for peer $0", peer.ShortDebugString());
  }
}

CHECKED_STATUS CatalogManagerUtil::CheckIfCanDeleteSingleTablet(
    const scoped_refptr<TabletInfo>& tablet) {
  const auto& tablet_id = tablet->tablet_id();

  const auto tablet_lock = tablet->LockForRead();
  const auto tablet_pb = tablet_lock->data().pb;
  if (tablet_pb.state() == SysTabletsEntryPB::DELETED) {
    return STATUS_FORMAT(NotFound, "Tablet $0 has been already deleted", tablet_id);
  }
  const auto partition = tablet_pb.partition();

  TabletInfos tablets_in_range;
  VLOG(3) << "Tablet " << tablet_id << " " << AsString(partition);
  tablet->table()->GetTabletsInRange(
      partition.partition_key_start(), partition.partition_key_end(), &tablets_in_range);

  std::string partition_key = partition.partition_key_start();
  for (const auto& inner_tablet : tablets_in_range) {
    if (inner_tablet->tablet_id() == tablet_id) {
      continue;
    }
    PartitionPB inner_partition;
    SysTabletsEntryPB::State inner_tablet_state;
    {
      const auto inner_tablet_lock = inner_tablet->LockForRead();
      const auto& pb = inner_tablet_lock->data().pb;
      inner_partition = pb.partition();
      inner_tablet_state = pb.state();
    }
    VLOG(3) << "Inner tablet " << inner_tablet->tablet_id()
            << " partition: " << AsString(inner_partition)
            << " state: " << SysTabletsEntryPB_State_Name(inner_tablet_state);
    if (inner_tablet_state != SysTabletsEntryPB::RUNNING) {
      continue;
    }
    if (partition_key != inner_partition.partition_key_start()) {
      return STATUS_FORMAT(
          IllegalState,
          "Can't delete tablet $0 not covered by child tablets. Partition gap: $1 ... $2",
          tablet_id, Slice(partition_key).ToDebugString(),
          Slice(inner_partition.partition_key_start()).ToDebugString());
    }
    partition_key = inner_partition.partition_key_end();
    if (!partition.partition_key_end().empty() && partition_key >= partition.partition_key_end()) {
      break;
    }
  }
  if (partition_key != partition.partition_key_end()) {
    return STATUS_FORMAT(
        IllegalState,
        "Can't delete tablet $0 not covered by child tablets. Partition gap: $1 ... $2",
        tablet_id, Slice(partition_key).ToDebugString(),
        Slice(partition.partition_key_end()).ToDebugString());
  }
  return Status::OK();
}

bool CatalogManagerUtil::IsCloudInfoPrefix(const CloudInfoPB& ci1, const CloudInfoPB& ci2) {
  bool is_cloud_same = true, is_region_same = true, is_zone_same = true;
  if (ci1.has_placement_cloud() && ci2.has_placement_cloud()) {
    is_cloud_same = ci1.placement_cloud() == ci2.placement_cloud();
  }

  if (ci1.has_placement_region() && ci2.has_placement_region()) {
    is_region_same = ci1.placement_region() == ci2.placement_region();
  }

  if (ci1.has_placement_zone() && ci2.has_placement_zone()) {
    is_zone_same = ci1.placement_zone() == ci2.placement_zone();
  }

  return is_cloud_same && is_region_same && is_zone_same;
}

CHECKED_STATUS CatalogManagerUtil::IsPlacementInfoValid(const PlacementInfoPB& placement_info) {
  // Check for duplicates.
  unordered_set<string> cloud_info_string;

  for (int i = 0; i < placement_info.placement_blocks_size(); i++) {
    if (!placement_info.placement_blocks(i).has_cloud_info()) {
      continue;
    }

    const CloudInfoPB& ci = placement_info.placement_blocks(i).cloud_info();
    string ci_string = TSDescriptor::generate_placement_id(ci);

    if (!cloud_info_string.count(ci_string)) {
      cloud_info_string.insert(ci_string);
    } else {
      return STATUS(IllegalState,
                    Substitute("Placement information specified should not contain duplicates."
                    "Given placement block: $0 isn't a prefix", ci.ShortDebugString()));
    }
  }

  // Validate the placement blocks to be prefixes.
  for (int i = 0; i < placement_info.placement_blocks_size(); i++) {
    if (!placement_info.placement_blocks(i).has_cloud_info()) {
      continue;
    }

    const CloudInfoPB& pb = placement_info.placement_blocks(i).cloud_info();

    // Four cases for pb to be a prefix.
    bool contains_cloud = pb.has_placement_cloud();
    bool contains_region = pb.has_placement_region();
    bool contains_zone = pb.has_placement_zone();
    // *.*.*
    bool star_star_star = !contains_cloud && !contains_region && !contains_zone;
    // C.*.*
    bool c_star_star = contains_cloud && !contains_region && !contains_zone;
    // C.R.*
    bool c_r_star = contains_cloud && contains_region && !contains_zone;
    // C.R.Z
    bool c_r_z = contains_cloud && contains_region && contains_zone;

    if (!star_star_star && !c_star_star && !c_r_star && !c_r_z) {
      return STATUS(IllegalState,
                        Substitute("Placement information specified should be prefixes."
                        "Given placement block: $0 isn't a prefix", pb.ShortDebugString()));
    }
  }

  // No two prefixes should overlap.
  for (int i = 0; i < placement_info.placement_blocks_size(); i++) {
    for (int j = 0; j < placement_info.placement_blocks_size(); j++) {
      if (i == j) {
        continue;
      } else {
        if (!placement_info.placement_blocks(i).has_cloud_info() ||
        !placement_info.placement_blocks(j).has_cloud_info()) {
          continue;
        }

        const CloudInfoPB& pb1 = placement_info.placement_blocks(i).cloud_info();
        const CloudInfoPB& pb2 = placement_info.placement_blocks(j).cloud_info();
        // pb1 shouldn't be prefix of pb2.
        if (CatalogManagerUtil::IsCloudInfoPrefix(pb1, pb2)) {
          return STATUS(IllegalState,
                        Substitute("Placement information specified should not overlap. $0 and"
                        " $1 overlap. For instance, c1.r1.z1,c1.r1 is invalid while "
                        "c1.r1.z1,c1.r1.z2 is valid. Also note that c1.r1,c1.r1 is valid.",
                        pb1.ShortDebugString(), pb2.ShortDebugString()));
        }
      }
    }
  }
  return Status::OK();
}

} // namespace master
} // namespace yb
