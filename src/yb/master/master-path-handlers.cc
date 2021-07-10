// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
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

#include "yb/master/master-path-handlers.h"

#include <algorithm>
#include <array>
#include <functional>
#include <map>
#include <iomanip>
#include <sstream>
#include <unordered_set>

#include "yb/common/hybrid_time.h"
#include "yb/common/partition.h"
#include "yb/common/schema.h"
#include "yb/consensus/consensus.pb.h"
#include "yb/gutil/map-util.h"
#include "yb/gutil/stringprintf.h"
#include "yb/gutil/strings/join.h"
#include "yb/gutil/strings/numbers.h"
#include "yb/gutil/strings/substitute.h"
#include "yb/master/catalog_entity_info.h"
#include "yb/master/cluster_balance.h"
#include "yb/master/master.h"
#include "yb/master/master.pb.h"
#include "yb/master/master_fwd.h"
#include "yb/master/master_util.h"
#include "yb/master/sys_catalog.h"
#include "yb/master/ts_descriptor.h"
#include "yb/master/ts_manager.h"
#include "yb/server/webserver.h"
#include "yb/server/webui_util.h"
#include "yb/util/curl_util.h"
#include "yb/util/string_case.h"
#include "yb/util/timestamp.h"
#include "yb/util/url-coding.h"
#include "yb/util/version_info.h"
#include "yb/util/version_info.pb.h"

DEFINE_int32(
    hide_dead_node_threshold_mins, 60 * 24,
    "After this many minutes of no heartbeat from a node, hide it from the UI "
    "(we presume it has been removed from the cluster). If -1, this flag is ignored and node is "
    "never hidden from the UI");

DECLARE_int32(ysql_tablespace_info_refresh_secs);

namespace yb {

namespace {
static constexpr const char* kDBTypeNameUnknown = "unknown";
static constexpr const char* kDBTypeNameCql = "ycql";
static constexpr const char* kDBTypeNamePgsql = "ysql";
static constexpr const char* kDBTypeNameRedis = "yedis";

const int64_t kCurlTimeoutSec = 180;

const char* DatabaseTypeName(YQLDatabase db) {
  switch (db) {
    case YQL_DATABASE_UNKNOWN: break;
    case YQL_DATABASE_CQL: return kDBTypeNameCql;
    case YQL_DATABASE_PGSQL: return kDBTypeNamePgsql;
    case YQL_DATABASE_REDIS: return kDBTypeNameRedis;
  }
  CHECK(false) << "Unexpected db type " << db;
  return kDBTypeNameUnknown;
}

YQLDatabase DatabaseTypeByName(const string& db_type_name) {
  static const std::array<pair<const char*, YQLDatabase>, 3> db_types{
      make_pair(kDBTypeNameCql, YQLDatabase::YQL_DATABASE_CQL),
      make_pair(kDBTypeNamePgsql, YQLDatabase::YQL_DATABASE_PGSQL),
      make_pair(kDBTypeNameRedis, YQLDatabase::YQL_DATABASE_REDIS)};
  for (const auto& db : db_types) {
    if (db_type_name == db.first) {
      return db.second;
    }
  }
  return YQLDatabase::YQL_DATABASE_UNKNOWN;
}

} // namespace

using consensus::RaftPeerPB;
using std::vector;
using std::map;
using std::string;
using std::stringstream;
using std::unique_ptr;
using strings::Substitute;

using namespace std::placeholders;

namespace master {

MasterPathHandlers::~MasterPathHandlers() {
}

void MasterPathHandlers::TabletCounts::operator+=(const TabletCounts& other) {
  user_tablet_leaders += other.user_tablet_leaders;
  user_tablet_followers += other.user_tablet_followers;
  system_tablet_leaders += other.system_tablet_leaders;
  system_tablet_followers += other.system_tablet_followers;
}

MasterPathHandlers::ZoneTabletCounts::ZoneTabletCounts(
  const TabletCounts& tablet_counts,
  uint32_t active_tablets_count) : tablet_counts(tablet_counts),
                                   active_tablets_count(active_tablets_count) {
}

void MasterPathHandlers::ZoneTabletCounts::operator+=(const ZoneTabletCounts& other) {
  tablet_counts += other.tablet_counts;
  node_count += other.node_count;
  active_tablets_count += other.active_tablets_count;
}

// Retrieve the specified URL response from the leader master
void MasterPathHandlers::RedirectToLeader(const Webserver::WebRequest& req,
                                          Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  vector<ServerEntryPB> masters;
  Status s = master_->ListMasters(&masters);
  if (!s.ok()) {
    s = s.CloneAndPrepend("Unable to list masters during web request handling");
    LOG(WARNING) << s.ToString();
    *output << "<h2>" << s.ToString() << "</h2>\n";
    return;
  }

  string redirect;
  for (const ServerEntryPB& master : masters) {
    if (master.has_error()) {
      continue;
    }

    if (master.role() == consensus::RaftPeerPB::LEADER) {
      // URI already starts with a /, so none is needed between $1 and $2.
      if (master.registration().http_addresses().size() > 0) {
        redirect = Substitute(
            "http://$0$1$2",
            HostPortPBToString(master.registration().http_addresses(0)),
            req.redirect_uri,
            req.query_string.empty() ? "?raw" : "?" + req.query_string + "&raw");
      }
      break;
    }
  }

  if (redirect.empty()) {
    string error = "Unable to locate leader master to redirect this request: " + redirect;
    LOG(WARNING) << error;
    *output << error << "<br>";
    return;
  }

  EasyCurl curl;
  faststring buf;
  s = curl.FetchURL(redirect, &buf, kCurlTimeoutSec);
  if (!s.ok()) {
    LOG(WARNING) << "Error retrieving leader master URL: " << redirect
                 << ", error :" << s.ToString();
    *output << "Error retrieving leader master URL: <a href=\"" << redirect
            << "\">" + redirect + "</a><br> Error: " << s.ToString() << ".<br>";
    return;
  }

  *output << buf.ToString();
}

void MasterPathHandlers::CallIfLeaderOrPrintRedirect(
    const Webserver::WebRequest& req, Webserver::WebResponse* resp,
    const Webserver::PathHandlerCallback& callback) {
  string redirect;
  // Lock the CatalogManager in a self-contained block, to prevent double-locking on callbacks.
  {
    SCOPED_LEADER_SHARED_LOCK(l, master_->catalog_manager());

    // If we are not the master leader, redirect the URL.
    if (!l.first_failed_status().ok()) {
      RedirectToLeader(req, resp);
      return;
    }

    // Handle the request as a leader master.
    callback(req, resp);
    return;
  }
}

inline void MasterPathHandlers::TServerTable(std::stringstream* output,
                                             TServersViewType viewType) {
  *output << "<table class='table table-striped'>\n";
  *output << "    <tr>\n"
          << "      <th>Server</th>\n"
          << "      <th>Time since </br>heartbeat</th>\n"
          << "      <th>Status & Uptime</th>\n";

  if (viewType == TServersViewType::kTServersClocksView) {
    *output << "      <th>Physical Time (UTC)</th>\n"
            << "      <th>Hybrid Time (UTC)</th>\n"
            << "      <th>Heartbeat RTT</th>\n";
  } else {
    DCHECK_EQ(viewType, TServersViewType::kTServersDefaultView);
    *output << "      <th>User Tablet-Peers / Leaders</th>\n"
            << "      <th>RAM Used</th>\n"
            << "      <th>Num SST Files</th>\n"
            << "      <th>Total SST Files Size</th>\n"
            << "      <th>Uncompressed SST </br>Files Size</th>\n"
            << "      <th>Read ops/sec</th>\n"
            << "      <th>Write ops/sec</th>\n";
  }

  *output << "      <th>Cloud</th>\n"
          << "      <th>Region</th>\n"
          << "      <th>Zone</th>\n";

  if (viewType == TServersViewType::kTServersDefaultView) {
    *output << "      <th>System Tablet-Peers / Leaders</th>\n"
            << "      <th>Active Tablet-Peers</th>\n";
  }

  *output << "    </tr>\n";
}

namespace {

constexpr int kHoursPerDay = 24;
constexpr int kSecondsPerMinute = 60;
constexpr int kMinutesPerHour = 60;
constexpr int kSecondsPerHour = kSecondsPerMinute * kMinutesPerHour;
constexpr int kMinutesPerDay = kMinutesPerHour * kHoursPerDay;
constexpr int kSecondsPerDay = kSecondsPerHour * kHoursPerDay;

string UptimeString(uint64_t seconds) {
  int days = seconds / kSecondsPerDay;
  int hours = (seconds / kSecondsPerHour) - (days * kHoursPerDay);
  int mins = (seconds / kSecondsPerMinute) - (days * kMinutesPerDay) - (hours * kMinutesPerHour);

  std::ostringstream uptime_string_stream;
  uptime_string_stream << " ";
  if (days > 0) {
    uptime_string_stream << days << "days, ";
  }
  uptime_string_stream << hours << ":" << std::setw(2) << std::setfill('0') << mins <<
      ":" << std::setw(2) << std::setfill('0') << (seconds % 60);

  return uptime_string_stream.str();
}

bool ShouldHideTserverNodeFromDisplay(
    const TSDescriptor* ts, int hide_dead_node_threshold_mins) {
  return hide_dead_node_threshold_mins > 0
      && !ts->IsLive()
      && ts->TimeSinceHeartbeat().ToMinutes() > hide_dead_node_threshold_mins;
}

int GetTserverCountForDisplay(const TSManager* ts_manager) {
  int count = 0;
  for (const auto& tserver : ts_manager->GetAllDescriptors()) {
    if (!ShouldHideTserverNodeFromDisplay(tserver.get(), FLAGS_hide_dead_node_threshold_mins)) {
      count++;
    }
  }
  return count;
}

} // anonymous namespace

string MasterPathHandlers::GetHttpHostPortFromServerRegistration(
    const ServerRegistrationPB& reg) const {
  if (reg.http_addresses().size() > 0) {
    return HostPortPBToString(reg.http_addresses(0));
  }
  return "";
}

namespace {

bool TabletServerComparator(
    const std::shared_ptr<TSDescriptor>& a, const std::shared_ptr<TSDescriptor>& b) {
  auto a_cloud_info = a->GetRegistration().common().cloud_info();
  auto b_cloud_info = b->GetRegistration().common().cloud_info();

  if (a_cloud_info.placement_cloud() == b_cloud_info.placement_cloud()) {
    if (a_cloud_info.placement_region() == b_cloud_info.placement_region()) {
      if (a_cloud_info.placement_zone() == b_cloud_info.placement_zone()) {
        return a->permanent_uuid() < b->permanent_uuid();
      }
      return a_cloud_info.placement_zone() < b_cloud_info.placement_zone();
    }
    return a_cloud_info.placement_region() < b_cloud_info.placement_region();
  }
  return a_cloud_info.placement_cloud() < b_cloud_info.placement_cloud();
}

} // anonymous namespace

void MasterPathHandlers::TServerDisplay(const std::string& current_uuid,
                                        std::vector<std::shared_ptr<TSDescriptor>>* descs,
                                        TabletCountMap* tablet_map,
                                        std::stringstream* output,
                                        const int hide_dead_node_threshold_mins,
                                        TServersViewType viewType) {
  // Copy vector to avoid changes to the reference descs passed
  std::vector<std::shared_ptr<TSDescriptor>> local_descs(*descs);

  // Comparator orders by cloud, region, zone and uuid fields.
  std::sort(local_descs.begin(), local_descs.end(), &TabletServerComparator);

  for (auto desc : local_descs) {
    if (desc->placement_uuid() == current_uuid) {
      if (ShouldHideTserverNodeFromDisplay(desc.get(), hide_dead_node_threshold_mins)) {
        continue;
      }
      const string time_since_hb = StringPrintf("%.1fs", desc->TimeSinceHeartbeat().ToSeconds());
      TSRegistrationPB reg = desc->GetRegistration();
      string host_port = GetHttpHostPortFromServerRegistration(reg.common());
      *output << "  <tr>\n";
      *output << "  <td>" << RegistrationToHtml(reg.common(), host_port) << "</br>";
      *output << "  " << desc->permanent_uuid() << "</td>";
      *output << "<td>" << time_since_hb << "</td>";
      if (desc->IsLive()) {
        *output << "    <td style=\"color:Green\">" << kTserverAlive << ":" <<
                UptimeString(desc->uptime_seconds()) << "</td>";
      } else {
        *output << "    <td style=\"color:Red\">" << kTserverDead << "</td>";
      }

      auto tserver = tablet_map->find(desc->permanent_uuid());
      bool no_tablets = tserver == tablet_map->end();

      if (viewType == TServersViewType::kTServersClocksView) {
        // Render physical time.
        const Timestamp p_ts(desc->physical_time());
        *output << "    <td>" << p_ts.ToHumanReadableTime() << "</td>";

        // Render the physical and logical components of the hybrid time.
        const HybridTime ht = desc->hybrid_time();
        const Timestamp h_ts(ht.GetPhysicalValueMicros());
        *output << "    <td>" << h_ts.ToHumanReadableTime();
        if (ht.GetLogicalValue()) {
          *output << " / Logical: " << ht.GetLogicalValue();
        }
        *output << "</td>";
        // Render the roundtrip time of previous heartbeat.
        double rtt_ms = desc->heartbeat_rtt().ToMicroseconds()/1000.0;
        *output << "    <td>" <<  StringPrintf("%.2fms", rtt_ms) << "</td>";
      } else {
        DCHECK_EQ(viewType, TServersViewType::kTServersDefaultView);
        *output << "    <td>" << (no_tablets ? 0
                : tserver->second.user_tablet_leaders + tserver->second.user_tablet_followers)
                << " / " << (no_tablets ? 0 : tserver->second.user_tablet_leaders) << "</td>";
        *output << "    <td>" << HumanizeBytes(desc->total_memory_usage()) << "</td>";
        *output << "    <td>" << desc->num_sst_files() << "</td>";
        *output << "    <td>" << HumanizeBytes(desc->total_sst_file_size()) << "</td>";
        *output << "    <td>" << HumanizeBytes(desc->uncompressed_sst_file_size()) << "</td>";
        *output << "    <td>" << desc->read_ops_per_sec() << "</td>";
        *output << "    <td>" << desc->write_ops_per_sec() << "</td>";
      }

      *output << "    <td>" << reg.common().cloud_info().placement_cloud() << "</td>";
      *output << "    <td>" << reg.common().cloud_info().placement_region() << "</td>";
      *output << "    <td>" << reg.common().cloud_info().placement_zone() << "</td>";

      if (viewType == TServersViewType::kTServersDefaultView) {
        *output << "    <td>" << (no_tablets ? 0
                : tserver->second.system_tablet_leaders + tserver->second.system_tablet_followers)
                << " / " << (no_tablets ? 0 : tserver->second.system_tablet_leaders) << "</td>";
        *output << "    <td>" << (no_tablets ? 0 : desc->num_live_replicas()) << "</td>";
      }

      *output << "  </tr>\n";
    }
  }
  *output << "</table>\n";
}

void MasterPathHandlers::DisplayTabletZonesTable(
  const ZoneTabletCounts::CloudTree& cloud_tree,
  std::stringstream* output
) {
  *output << "<h3>Tablet-Peers by Availability Zone</h3>\n"
          << "<table class='table table-striped'>\n"
          << "  <tr>\n"
          << "    <th>Cloud</th>\n"
          << "    <th>Region</th>\n"
          << "    <th>Zone</th>\n"
          << "    <th>Total Nodes</th>\n"
          << "    <th>User Tablet-Peers / Leaders</th>\n"
          << "    <th>System Tablet-Peers / Leaders</th>\n"
          << "    <th>Active Tablet-Peers</th>\n"
          << "  </tr>\n";

  for (const auto& cloud_iter : cloud_tree) {
    const auto& region_tree = cloud_iter.second;
    bool needs_new_row = false;

    int total_size_rows = 0;
    for (const auto& region_iter : region_tree) {
      total_size_rows += region_iter.second.size();
    }

    *output << "<tr>\n"
            << "  <td rowspan=\"" << total_size_rows <<"\">" << cloud_iter.first << "</td>\n";

    for (const auto& region_iter : region_tree) {
      const auto& zone_tree = region_iter.second;

      if (needs_new_row) {
        *output << "<tr>\n";
        needs_new_row = false;
      }

      *output << "  <td rowspan=\"" << zone_tree.size() <<"\">" << region_iter.first
              << "</td>\n";

      for (const auto& zone_iter : zone_tree) {
        const auto& counts = zone_iter.second;

        if (needs_new_row) {
          *output << "<tr>\n";
        }

        *output << "  <td>" << zone_iter.first << "</td>\n";

        uint32_t user_leaders = counts.tablet_counts.user_tablet_leaders;
        uint32_t user_total = user_leaders + counts.tablet_counts.user_tablet_followers;
        uint32_t system_leaders = counts.tablet_counts.system_tablet_leaders;
        uint32_t system_total = system_leaders + counts.tablet_counts.system_tablet_followers;

        *output << "  <td>" << counts.node_count << "</td>\n"
                << "  <td>" << user_total << " / " << user_leaders << "</td>\n"
                << "  <td>" << system_total << " / " << system_leaders << "</td>\n"
                << "  <td>" << counts.active_tablets_count << "</td>\n"
                << "</tr>\n";

        needs_new_row = true;
      }
    }
  }

  *output << "</table>\n";
}

MasterPathHandlers::ZoneTabletCounts::CloudTree MasterPathHandlers::CalculateTabletCountsTree(
  const std::vector<std::shared_ptr<TSDescriptor>>& descriptors,
  const TabletCountMap& tablet_count_map
) {
  ZoneTabletCounts::CloudTree cloud_tree;

  for (const auto& descriptor : descriptors) {
    CloudInfoPB cloud_info = descriptor->GetRegistration().common().cloud_info();
    std::string cloud = cloud_info.placement_cloud();
    std::string region = cloud_info.placement_region();
    std::string zone = cloud_info.placement_zone();

    auto tablet_count_search = tablet_count_map.find(descriptor->permanent_uuid());
    ZoneTabletCounts counts = tablet_count_search == tablet_count_map.end()
        ? ZoneTabletCounts()
        : ZoneTabletCounts(tablet_count_search->second, descriptor->num_live_replicas());

    auto cloud_iter = cloud_tree.find(cloud);
    if (cloud_iter == cloud_tree.end()) {
      ZoneTabletCounts::RegionTree region_tree;
      ZoneTabletCounts::ZoneTree zone_tree;

      zone_tree.emplace(zone, std::move(counts));
      region_tree.emplace(region, std::move(zone_tree));
      cloud_tree.emplace(cloud, std::move(region_tree));
    } else {
      ZoneTabletCounts::RegionTree& region_tree = cloud_iter->second;

      auto region_iter = region_tree.find(region);
      if (region_iter == region_tree.end()) {
        ZoneTabletCounts::ZoneTree zone_tree;

        zone_tree.emplace(zone, std::move(counts));
        region_tree.emplace(region, std::move(zone_tree));
      } else {
        ZoneTabletCounts::ZoneTree& zone_tree = region_iter->second;

        auto zone_iter = zone_tree.find(zone);
        if (zone_iter == zone_tree.end()) {
          zone_tree.emplace(zone, std::move(counts));
        } else {
          zone_iter->second += counts;
        }
      }
    }
  }

  return cloud_tree;
}

void MasterPathHandlers::HandleTabletServers(const Webserver::WebRequest& req,
                                             Webserver::WebResponse* resp,
                                             TServersViewType viewType) {
  std::stringstream *output = &resp->output;
  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  auto threshold_arg = req.parsed_args.find("live_threshold_mins");
  int hide_dead_node_threshold_override = FLAGS_hide_dead_node_threshold_mins;
  if (threshold_arg != req.parsed_args.end()) {
    hide_dead_node_threshold_override = atoi(threshold_arg->second.c_str());
  }

  SysClusterConfigEntryPB config;
  Status s = master_->catalog_manager()->GetClusterConfig(&config);
  if (!s.ok()) {
    *output << "<div class=\"alert alert-warning\">" << s.ToString() << "</div>";
    return;
  }

  auto live_id = config.replication_info().live_replicas().placement_uuid();

  vector<std::shared_ptr<TSDescriptor> > descs;
  const auto& ts_manager = master_->ts_manager();
  ts_manager->GetAllDescriptors(&descs);

  // Get user and system tablet leader and follower counts for each TabletServer
  TabletCountMap tablet_map;
  CalculateTabletMap(&tablet_map);

  unordered_set<string> read_replica_uuids;
  for (auto desc : descs) {
    if (!read_replica_uuids.count(desc->placement_uuid()) && desc->placement_uuid() != live_id) {
      read_replica_uuids.insert(desc->placement_uuid());
    }
  }

  *output << std::setprecision(output_precision_);
  *output << "<h2>Tablet Servers</h2>\n";

  if (!live_id.empty()) {
    *output << "<h3 style=\"color:" << kYBDarkBlue << "\">Primary Cluster UUID: "
            << live_id << "</h3>\n";
  }

  TServerTable(output, viewType);
  TServerDisplay(live_id, &descs, &tablet_map, output, hide_dead_node_threshold_override,
                 viewType);

  for (const auto& read_replica_uuid : read_replica_uuids) {
    *output << "<h3 style=\"color:" << kYBDarkBlue << "\">Read Replica UUID: "
            << (read_replica_uuid.empty() ? kNoPlacementUUID : read_replica_uuid) << "</h3>\n";
    TServerTable(output, viewType);
    TServerDisplay(read_replica_uuid, &descs, &tablet_map, output,
                   hide_dead_node_threshold_override, viewType);
  }

  ZoneTabletCounts::CloudTree counts_tree = CalculateTabletCountsTree(descs, tablet_map);
  DisplayTabletZonesTable(counts_tree, output);
}

void MasterPathHandlers::HandleGetTserverStatus(const Webserver::WebRequest& req,
                                             Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  JsonWriter jw(output, JsonWriter::COMPACT);

  SysClusterConfigEntryPB config;
  Status s = master_->catalog_manager()->GetClusterConfig(&config);
  if (!s.ok()) {
    jw.StartObject();
    jw.String("error");
    jw.String(s.ToString());
    return;
  }

  vector<std::shared_ptr<TSDescriptor> > descs;
  const auto& ts_manager = master_->ts_manager();
  ts_manager->GetAllDescriptors(&descs);

  // Get user and system tablet leader and follower counts for each TabletServer.
  TabletCountMap tablet_map;
  CalculateTabletMap(&tablet_map);

  unordered_set<string> cluster_uuids;
  auto primary_uuid = config.replication_info().live_replicas().placement_uuid();
  cluster_uuids.insert(primary_uuid);
  for (auto desc : descs) {
    cluster_uuids.insert(desc->placement_uuid());
  }

  jw.StartObject();
  for (const auto& cur_uuid : cluster_uuids) {
    jw.String(cur_uuid);
    jw.StartObject();
    for (auto desc : descs) {
      if (desc->placement_uuid() == cur_uuid) {
        TSRegistrationPB reg = desc->GetRegistration();
        string host_port = GetHttpHostPortFromServerRegistration(reg.common());
        jw.String(host_port);

        jw.StartObject();

        // Some stats may be repeated as strings due to backwards compatability.
        jw.String("time_since_hb");
        jw.String(StringPrintf("%.1fs", desc->TimeSinceHeartbeat().ToSeconds()));
        jw.String("time_since_hb_sec");
        jw.Double(desc->TimeSinceHeartbeat().ToSeconds());

        if (desc->IsLive()) {
          jw.String("status");
          jw.String(kTserverAlive);

          jw.String("uptime_seconds");
          jw.Uint64(desc->uptime_seconds());
        } else {
          jw.String("status");
          jw.String(kTserverDead);

          jw.String("uptime_seconds");
          jw.Uint(0);
        }

        jw.String("ram_used");
        jw.String(HumanizeBytes(desc->total_memory_usage()));
        jw.String("ram_used_bytes");
        jw.Uint64(desc->total_memory_usage());

        jw.String("num_sst_files");
        jw.Uint64(desc->num_sst_files());

        jw.String("total_sst_file_size");
        jw.String(HumanizeBytes(desc->total_sst_file_size()));
        jw.String("total_sst_file_size_bytes");
        jw.Uint64(desc->total_sst_file_size());

        jw.String("uncompressed_sst_file_size");
        jw.String(HumanizeBytes(desc->uncompressed_sst_file_size()));
        jw.String("uncompressed_sst_file_size_bytes");
        jw.Uint64(desc->uncompressed_sst_file_size());

        jw.String("path_metrics");
        jw.StartArray();
        for(const auto& path_metric : desc->path_metrics()) {
          jw.StartObject();
          jw.String("path");
          jw.String(path_metric.first);
          jw.String("space_used");
          jw.Uint64(path_metric.second.used_space);
          jw.String("total_space_size");
          jw.Uint64(path_metric.second.total_space);
          jw.EndObject();
        }
        jw.EndArray();

        jw.String("read_ops_per_sec");
        jw.Double(desc->read_ops_per_sec());

        jw.String("write_ops_per_sec");
        jw.Double(desc->write_ops_per_sec());

        auto tserver = tablet_map.find(desc->permanent_uuid());
        uint user_tablets_total = 0;
        uint user_tablets_leaders = 0;
        uint system_tablets_total = 0;
        uint system_tablets_leaders = 0;
        int active_tablets = 0;
        if (!(tserver == tablet_map.end())) {
          user_tablets_total = tserver->second.user_tablet_leaders +
            tserver->second.user_tablet_followers;
          user_tablets_leaders = tserver->second.user_tablet_leaders;
          system_tablets_total = tserver->second.system_tablet_leaders +
            tserver->second.system_tablet_followers;
          system_tablets_leaders = tserver->second.system_tablet_leaders;
          active_tablets = desc->num_live_replicas();
        }
        jw.String("user_tablets_total");
        jw.Uint(user_tablets_total);

        jw.String("user_tablets_leaders");
        jw.Uint(user_tablets_leaders);

        jw.String("system_tablets_total");
        jw.Uint(system_tablets_total);

        jw.String("system_tablets_leaders");
        jw.Uint(system_tablets_leaders);

        jw.String("active_tablets");
        jw.Int(active_tablets);

        jw.EndObject();
      }
    }
    jw.EndObject();
  }
  jw.EndObject();
}

void MasterPathHandlers::HandleHealthCheck(
    const Webserver::WebRequest& req, Webserver::WebResponse* resp) {
  // TODO: Lock not needed since other APIs handle it.  Refactor other functions accordingly
  std::stringstream *output = &resp->output;
  JsonWriter jw(output, JsonWriter::COMPACT);

  SysClusterConfigEntryPB config;
  Status s = master_->catalog_manager()->GetClusterConfig(&config);
  if (!s.ok()) {
    jw.StartObject();
    jw.String("error");
    jw.String(s.ToString());
    return;
  }
  int replication_factor;
  s = master_->catalog_manager()->GetReplicationFactor(&replication_factor);
  if (!s.ok()) {
    jw.StartObject();
    jw.String("error");
    jw.String(s.ToString());
    return;
  }

  vector<std::shared_ptr<TSDescriptor> > descs;
  const auto* ts_manager = master_->ts_manager();
  ts_manager->GetAllDescriptors(&descs);

  const auto& live_placement_uuid = config.replication_info().live_replicas().placement_uuid();
  // Ignore read replica health for V1.

  vector<std::shared_ptr<TSDescriptor> > dead_nodes;
  uint64_t most_recent_uptime = std::numeric_limits<uint64_t>::max();

  jw.StartObject();
  {
    // Iterate TabletServers, looking for health anomalies.
    for (const auto & desc : descs) {
      if (desc->placement_uuid() == live_placement_uuid) {
        if (!desc->IsLive()) {
          // 1. Are any of the TS marked dead in the master?
          dead_nodes.push_back(desc);
        } else {
          // 2. Have any of the servers restarted lately?
          most_recent_uptime = min(most_recent_uptime, desc->uptime_seconds());
        }
      }
    }

    jw.String("dead_nodes");
    jw.StartArray();
    for (auto const & ts_desc : dead_nodes) {
      jw.String(ts_desc->permanent_uuid());
    }
    jw.EndArray();

    jw.String("most_recent_uptime");
    jw.Uint(most_recent_uptime);

    auto time_arg = req.parsed_args.find("tserver_death_interval_msecs");
    int64 death_interval_msecs = 0;
    if (time_arg != req.parsed_args.end()) {
      death_interval_msecs = atoi(time_arg->second.c_str());
    }

    // Get all the tablets and add the tablet id for each tablet that has
    // replication locations lesser than 'replication_factor'.
    jw.String("under_replicated_tablets");
    jw.StartArray();

    auto tables = master_->catalog_manager()->GetTables(GetTablesMode::kRunning);
    for (const auto& table : tables) {
      // Ignore tables that are neither user tables nor user indexes.
      // However there are a bunch of system tables that still need to be investigated:
      // 1. Redis system table.
      // 2. Transaction status table.
      // 3. Metrics table.
      if (!master_->catalog_manager()->IsUserTable(*table) &&
          table->GetTableType() != REDIS_TABLE_TYPE &&
          table->GetTableType() != TRANSACTION_STATUS_TABLE_TYPE &&
          !(table->namespace_id() == kSystemNamespaceId &&
            table->name() == kMetricsSnapshotsTableName)) {
        continue;
      }

      TabletInfos tablets;
      table->GetAllTablets(&tablets);

      for (const auto& tablet : tablets) {
        auto replication_locations = tablet->GetReplicaLocations();

        if (replication_locations->size() < replication_factor) {
          // These tablets don't have the required replication locations needed.
          jw.String(tablet->tablet_id());
          continue;
        }

        // Check if we have tablets that have replicas on the dead node.
        if (dead_nodes.size() == 0) {
          continue;
        }
        int recent_replica_count = 0;
        for (const auto& iter : *replication_locations) {
          if (std::find_if(dead_nodes.begin(),
                           dead_nodes.end(),
                           [iter, death_interval_msecs] (const auto& ts) {
                               return (ts->permanent_uuid() == iter.first &&
                                       ts->TimeSinceHeartbeat().ToMilliseconds() >
                                           death_interval_msecs); }) ==
                  dead_nodes.end()) {
            ++recent_replica_count;
          }
        }
        if (recent_replica_count < replication_factor) {
          jw.String(tablet->tablet_id());
        }
      }
    }
    jw.EndArray();

    // TODO: Add these health checks in a subsequent diff
    //
    // 4. is the load balancer busy moving tablets/leaders around
    /* Use: CHECKED_STATUS IsLoadBalancerIdle(const IsLoadBalancerIdleRequestPB* req,
                                              IsLoadBalancerIdleResponsePB* resp);
     */
    // 5. do any of the TS have tablets they were not able to start up
  }
  jw.EndObject();
}

string MasterPathHandlers::GetParentTableOid(scoped_refptr<TableInfo> parent_table) {
  TableId t_id = parent_table->id();;
  if (master_->catalog_manager()->IsColocatedParentTable(*parent_table)) {
    // No YSQL parent id for colocated database parent table
    return "";
  }
  const auto parent_result = GetPgsqlTablegroupOidByTableId(t_id);
  RETURN_NOT_OK_RET(ResultToStatus(parent_result), "");
  return std::to_string(*parent_result);
}

void MasterPathHandlers::HandleCatalogManager(const Webserver::WebRequest& req,
                                              Webserver::WebResponse* resp,
                                              bool only_user_tables) {
  std::stringstream *output = &resp->output;
  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  auto tables = master_->catalog_manager()->GetTables(GetTablesMode::kAll);

  bool has_tablegroups = master_->catalog_manager()->HasTablegroups();

  typedef map<string, string> StringMap;

  // The first stores user tables, the second index tables, and the third system tables.
  std::unique_ptr<StringMap> ordered_tables[kNumTypes];
  for (int i = 0; i < kNumTypes; ++i) {
    ordered_tables[i] = std::make_unique<StringMap>();
  }

  for (const auto& table : tables) {
    auto l = table->LockForRead();
    if (!l->is_running()) {
      continue;
    }

    string keyspace = master_->catalog_manager()->GetNamespaceName(table->namespace_id());
    bool is_platform = keyspace.compare(kSystemPlatformNamespace) == 0;

    TableType table_cat;
    // Determine the table category. YugaWare tables should be displayed as system tables.
    if (is_platform) {
      table_cat = kSystemTable;
    } else if (master_->catalog_manager()->IsUserIndex(*table)) {
      table_cat = kUserIndex;
    } else if (master_->catalog_manager()->IsUserTable(*table)) {
      table_cat = kUserTable;
    } else if (master_->catalog_manager()->IsTablegroupParentTable(*table) ||
               master_->catalog_manager()->IsColocatedParentTable(*table)) {
      table_cat = kColocatedParentTable;
    } else {
      table_cat = kSystemTable;
    }
    // Skip non-user tables if we should.
    if (only_user_tables && (table_cat != kUserIndex && table_cat != kUserTable)) {
      continue;
    }

    string table_uuid = table->id();
    string state = SysTablesEntryPB_State_Name(l->pb.state());
    Capitalize(&state);
    string ysql_table_oid;
    string ysql_parent_oid;

    string display_info = Substitute(
                          "<tr>" \
                          "<td>$0</td>",
                          EscapeForHtmlToString(keyspace));

    if (table->GetTableType() == PGSQL_TABLE_TYPE &&
        !master_->catalog_manager()->IsColocatedParentTable(*table) &&
        !master_->catalog_manager()->IsTablegroupParentTable(*table)) {
      const auto result = GetPgsqlTableOid(table_uuid);
      if (result.ok()) {
        ysql_table_oid = std::to_string(*result);
      } else {
        LOG(ERROR) << "Failed to get OID of '" << table_uuid << "' ysql table";
      }

      display_info += Substitute(
                      "<td><a href=\"/table?id=$3\">$0</a></td>" \
                      "<td>$1</td>" \
                      "<td>$2</td>" \
                      "<td>$3</td>" \
                      "<td>$4</td>",
                      EscapeForHtmlToString(l->name()),
                      state,
                      EscapeForHtmlToString(l->pb.state_msg()),
                      EscapeForHtmlToString(table_uuid),
                      ysql_table_oid);

      if (has_tablegroups) {
        if (master_->catalog_manager()->IsColocatedUserTable(*table)) {
          const auto parent_table = table->GetColocatedTablet()->table();
          ysql_parent_oid = GetParentTableOid(parent_table);
          display_info += Substitute("<td>$0</td>", ysql_parent_oid);
        } else {
          display_info += Substitute("<td></td>");
        }
      }
    } else if (master_->catalog_manager()->IsTablegroupParentTable(*table) ||
               master_->catalog_manager()->IsColocatedParentTable(*table)) {
      // Colocated parent table.
      ysql_table_oid = GetParentTableOid(table);

      // Insert a newline in id and name to wrap long tablegroup text.
      std::string parent_name = l->name();
      display_info += Substitute(
                      "<td><a href=\"/table?id=$0\">$1</a></td>" \
                      "<td>$2</td>" \
                      "<td>$3</td>" \
                      "<td>$4</td>" \
                      "<td>$5</td>",
                      EscapeForHtmlToString(table_uuid),
                      EscapeForHtmlToString(parent_name.insert(32, "\n")),
                      state,
                      EscapeForHtmlToString(l->pb.state_msg()),
                      EscapeForHtmlToString(table_uuid.insert(32, "\n")),
                      ysql_table_oid);
    } else {
      // System table - don't include parent table column
      display_info += Substitute(
                      "<td><a href=\"/table?id=$3\">$0</a></td>" \
                      "<td>$1</td>" \
                      "<td>$2</td>" \
                      "<td>$3</td>" \
                      "<td>$4</td>",
                      EscapeForHtmlToString(l->name()),
                      state,
                      EscapeForHtmlToString(l->pb.state_msg()),
                      EscapeForHtmlToString(table_uuid),
                      ysql_table_oid);
    }
    display_info += "</tr>\n";
    (*ordered_tables[table_cat])[table_uuid] = display_info;
  }

  for (int i = 0; i < kNumTypes; ++i) {
    if (only_user_tables && (table_type_[i] != "Index" && table_type_[i] != "User")) {
      continue;
    }
    if (ordered_tables[i]->empty() && table_type_[i] == "Colocated") {
      continue;
    }

    (*output) << "<div class='panel panel-default'>\n"
              << "<div class='panel-heading'><h2 class='panel-title'>" << table_type_[i]
              << " tables</h2></div>\n";
    (*output) << "<div class='panel-body table-responsive'>";

    if (ordered_tables[i]->empty()) {
      (*output) << "There are no " << static_cast<char>(tolower(table_type_[i][0]))
                << table_type_[i].substr(1) << " tables.\n";
    } else {
      *output << "<table class='table table-striped' style='table-layout: fixed;'>\n";
      *output << "  <tr><th width='14%'>Keyspace</th>\n"
              << "      <th width='21%'>Table Name</th>\n"
              << "      <th width='9%'>State</th>\n"
              << "      <th width='14%'>Message</th>\n";
      if ((table_type_[i] == "User" || table_type_[i] == "Index") && has_tablegroups) {
        *output << "      <th width='22%'>UUID</th>\n"
                << "      <th width='10%'>YSQL OID</th>\n"
                << "      <th width='10%'>Parent OID</th></tr>\n";
      } else {
        *output << "      <th width='28%'>UUID</th>\n"
                << "      <th width='14%'>YSQL OID</th></tr>\n";
      }
      for (const StringMap::value_type &table : *(ordered_tables[i])) {
        *output << table.second;
      }
      (*output) << "</table>\n";
    }
    (*output) << "</div> <!-- panel-body -->\n";
    (*output) << "</div> <!-- panel -->\n";
  }
}

namespace {

bool CompareByHost(const TabletReplica& a, const TabletReplica& b) {
    return a.ts_desc->permanent_uuid() < b.ts_desc->permanent_uuid();
}

} // anonymous namespace


void MasterPathHandlers::HandleTablePage(const Webserver::WebRequest& req,
                                         Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  // True if table_id, false if (keyspace, table).
  const auto arg_end = req.parsed_args.end();
  auto id_arg = req.parsed_args.find("id");
  auto keyspace_arg = arg_end;
  auto table_arg = arg_end;
  if (id_arg == arg_end) {
    keyspace_arg = req.parsed_args.find("keyspace_name");
    table_arg = req.parsed_args.find("table_name");
    if (keyspace_arg == arg_end || table_arg == arg_end) {
      *output << " Missing 'id' argument or 'keyspace_name, table_name' argument pair.";
      *output << " Arguments must either contain the table id or the "
                 " (keyspace_name, table_name) pair.";
      return;
    }
  }

  scoped_refptr<TableInfo> table;

  if (id_arg != arg_end) {
    table = master_->catalog_manager()->GetTableInfo(id_arg->second);
  } else {
    const auto keyspace_type_arg = req.parsed_args.find("keyspace_type");
    const auto keyspace_type = (keyspace_type_arg == arg_end
        ? GetDefaultDatabaseType(keyspace_arg->second)
        : DatabaseTypeByName(keyspace_type_arg->second));
    if (keyspace_type == YQLDatabase::YQL_DATABASE_UNKNOWN) {
      *output << "Wrong keyspace_type found '" << keyspace_type_arg->second << "'."
              << "Possible values are: " << kDBTypeNameCql << ", "
              << kDBTypeNamePgsql << ", " << kDBTypeNameRedis << ".";
      return;
    }
    table = master_->catalog_manager()->GetTableInfoFromNamespaceNameAndTableName(
        keyspace_type, keyspace_arg->second, table_arg->second);
  }

  if (table == nullptr) {
    *output << "Table not found!";
    return;
  }

  Schema schema;
  PartitionSchema partition_schema;
  NamespaceName keyspace_name;
  TableName table_name;
  vector<scoped_refptr<TabletInfo> > tablets;
  {
    auto l = table->LockForRead();
    keyspace_name = master_->catalog_manager()->GetNamespaceName(table->namespace_id());
    table_name = l->name();
    *output << "<h1>Table: " << EscapeForHtmlToString(TableLongName(keyspace_name, table_name))
            << " ("<< table->id() <<") </h1>\n";

    *output << "<table class='table table-striped'>\n";
    *output << "  <tr><td>Version:</td><td>" << l->pb.version() << "</td></tr>\n";

    *output << "  <tr><td>Type:</td><td>" << TableType_Name(l->pb.table_type())
            << "</td></tr>\n";

    string state = SysTablesEntryPB_State_Name(l->pb.state());
    Capitalize(&state);
    *output << "  <tr><td>State:</td><td>"
            << state
            << EscapeForHtmlToString(l->pb.state_msg())
            << "</td></tr>\n";

    TablespaceId tablespace_id;
    auto result = master_->catalog_manager()->GetTablespaceForTable(table);
    if (result.ok()) {
      // If the table is associated with a tablespace, display tablespace, otherwise
      // just display replication info.
      if (result.get()) {
        tablespace_id = result.get().value();
        *output << "  <tr><td>Tablespace OID:</td><td>"
                << GetPgsqlTablespaceOid(tablespace_id)
                << "  </td></tr>\n";
      }
      auto replication_info = CHECK_RESULT(master_->catalog_manager()->GetTableReplicationInfo(
            l->pb.replication_info(), tablespace_id));
      *output << "  <tr><td>Replication Info:</td><td>"
              << "    <pre class=\"prettyprint\">" << replication_info.DebugString() << "</pre>"
              << "  </td></tr>\n </table>\n";
    } else {
      // The table was associated with a tablespace, but that tablespace was not found.
      *output << "  <tr><td>Replication Info:</td><td>";
      if (FLAGS_ysql_tablespace_info_refresh_secs > 0) {
        *output << "  Tablespace information not available now, please try again after "
                << FLAGS_ysql_tablespace_info_refresh_secs << " seconds. ";
      } else {
        *output << "  Tablespace information is not available as the periodic task "
                << "  used to refresh it is disabled.";

      }
      *output << "  </td></tr>\n </table>\n";
    }

    Status s = SchemaFromPB(l->pb.schema(), &schema);
    if (s.ok()) {
      s = PartitionSchema::FromPB(l->pb.partition_schema(), schema, &partition_schema);
    }
    if (!s.ok()) {
      *output << "Unable to decode partition schema: " << s.ToString();
      return;
    }
    table->GetAllTablets(&tablets);
  }

  HtmlOutputSchemaTable(schema, output);

  *output << "<table class='table table-striped'>\n";
  *output << "  <tr><th>Tablet ID</th><th>Partition</th><th>State</th>"
      "<th>Message</th><th>RaftConfig</th></tr>\n";
  for (const scoped_refptr<TabletInfo>& tablet : tablets) {
    auto locations = tablet->GetReplicaLocations();
    vector<TabletReplica> sorted_locations;
    AppendValuesFromMap(*locations, &sorted_locations);
    std::sort(sorted_locations.begin(), sorted_locations.end(), &CompareByHost);

    auto l = tablet->LockForRead();

    Partition partition;
    Partition::FromPB(l->pb.partition(), &partition);

    string state = SysTabletsEntryPB_State_Name(l->pb.state());
    Capitalize(&state);
    *output << Substitute(
        "<tr><th>$0</th><td>$1</td><td>$2</td><td>$3</td><td>$4</td></tr>\n",
        tablet->tablet_id(),
        EscapeForHtmlToString(partition_schema.PartitionDebugString(partition, schema)),
        state,
        EscapeForHtmlToString(l->pb.state_msg()),
        RaftConfigToHtml(sorted_locations, tablet->tablet_id()));
  }
  *output << "</table>\n";

  HtmlOutputTasks(table->GetTasks(), output);
}

void MasterPathHandlers::HandleTasksPage(const Webserver::WebRequest& req,
                                         Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  auto tables = master_->catalog_manager()->GetTables(GetTablesMode::kAll);
  *output << "<h3>Active Tasks</h3>\n";
  *output << "<table class='table table-striped'>\n";
  *output << "  <tr><th>Task Name</th><th>State</th><th>Start "
             "Time</th><th>Time</th><th>Description</th></tr>\n";
  for (const auto& table : tables) {
    for (const auto& task : table->GetTasks()) {
      HtmlOutputTask(task, output);
    }
  }
  *output << "</table>\n";

  std::vector<std::shared_ptr<MonitoredTask>> jobs =
      master_->catalog_manager()->GetRecentJobs();
  *output << Substitute(
      "<h3>Last $0 user-initiated jobs started in the past $1 "
      "hours</h3>\n",
      FLAGS_tasks_tracker_num_long_term_tasks,
      FLAGS_long_term_tasks_tracker_keep_time_multiplier *
          MonoDelta::FromMilliseconds(FLAGS_catalog_manager_bg_task_wait_ms)
              .ToSeconds() /
          3600);
  *output << "<table class='table table-striped'>\n";
  *output << "  <tr><th>Job Name</th><th>State</th><th>Start "
             "Time</th><th>Duration</th><th>Description</th></tr>\n";
  for (std::vector<std::shared_ptr<MonitoredTask>>::reverse_iterator iter =
           jobs.rbegin();
       iter != jobs.rend(); ++iter) {
    HtmlOutputTask(*iter, output);
  }
  *output << "</table>\n";

  std::vector<std::shared_ptr<MonitoredTask> > tasks =
    master_->catalog_manager()->GetRecentTasks();
  *output << Substitute(
      "<h3>Last $0 tasks started in the past $1 seconds</h3>\n",
      FLAGS_tasks_tracker_num_tasks,
      FLAGS_tasks_tracker_keep_time_multiplier *
          MonoDelta::FromMilliseconds(FLAGS_catalog_manager_bg_task_wait_ms)
              .ToSeconds());
  *output << "<table class='table table-striped'>\n";
  *output << "  <tr><th>Task Name</th><th>State</th><th>Start "
             "Time</th><th>Duration</th><th>Description</th></tr>\n";
  for (std::vector<std::shared_ptr<MonitoredTask>>::reverse_iterator iter =
           tasks.rbegin();
       iter != tasks.rend(); ++iter) {
    HtmlOutputTask(*iter, output);
  }
  *output << "</table>\n";
}

std::vector<TabletInfoPtr> MasterPathHandlers::GetNonSystemTablets() {
  std::vector<TabletInfoPtr> nonsystem_tablets;

  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  auto tables = master_->catalog_manager()->GetTables(GetTablesMode::kRunning);

  for (const auto& table : tables) {
    if (master_->catalog_manager()->IsSystemTable(*table.get())) {
      continue;
    }
    TabletInfos ts;
    table->GetAllTablets(&ts);

    for (TabletInfoPtr t : ts) {
      nonsystem_tablets.push_back(t);
    }
  }
  return nonsystem_tablets;
}

std::vector<TabletInfoPtr> MasterPathHandlers::GetLeaderlessTablets() {
  std::vector<TabletInfoPtr> leaderless_tablets;

  auto nonsystem_tablets = GetNonSystemTablets();

  for (TabletInfoPtr t : nonsystem_tablets) {
    auto rm = t.get()->GetReplicaLocations();

    auto has_leader = std::any_of(
      rm->begin(), rm->end(),
      [](const auto &item) { return item.second.role == consensus::RaftPeerPB::LEADER; });

    if (!has_leader) {
      leaderless_tablets.push_back(t);
    }
  }
  return leaderless_tablets;
}

Result<std::vector<TabletInfoPtr>> MasterPathHandlers::GetUnderReplicatedTablets() {
  std::vector<TabletInfoPtr> underreplicated_tablets;

  auto nonsystem_tablets = GetNonSystemTablets();

  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  int cluster_rf;

  RETURN_NOT_OK_PREPEND(master_->catalog_manager()->GetReplicationFactor(&cluster_rf),
                        "Unable to find replication factor");

  for (TabletInfoPtr t : nonsystem_tablets) {
    auto rm = t.get()->GetReplicaLocations();

    // Find out the tablets which have been replicated less than the replication factor
    if(rm->size() < cluster_rf) {
      underreplicated_tablets.push_back(t);
    }
  }
  return underreplicated_tablets;
}

void MasterPathHandlers::HandleTabletReplicasPage(const Webserver::WebRequest& req,
                                                  Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;

  auto leaderless_ts = GetLeaderlessTablets();
  auto underreplicated_ts = GetUnderReplicatedTablets();

  *output << "<h3>Leaderless Tablets</h3>\n";
  *output << "<table class='table table-striped'>\n";
  *output << "  <tr><th>Table Name</th><th>Table UUID</th><th>Tablet ID</th></tr>\n";

  for (TabletInfoPtr t : leaderless_ts) {
    *output << Substitute(
        "<tr><td><a href=\"/table?id=$0\">$1</a></td><td>$2</td><th>$3</th></tr>\n",
        EscapeForHtmlToString(t->table()->id()),
        EscapeForHtmlToString(t->table()->name()),
        EscapeForHtmlToString(t->table()->id()),
        EscapeForHtmlToString(t.get()->tablet_id()));
  }

  *output << "</table>\n";

  if(!underreplicated_ts.ok()) {
    LOG(WARNING) << underreplicated_ts.ToString();
    *output << "<h2>Call to get the cluster replication factor failed</h2>\n";
    return;
  }

  *output << "<h3>Underreplicated Tablets</h3>\n";
  *output << "<table class='table table-striped'>\n";
  *output << "  <tr><th>Table Name</th><th>Table UUID</th><th>Tablet ID</th>"
          << "<th>Tablet Replication Count</th></tr>\n";

  for (TabletInfoPtr t : *underreplicated_ts) {
    auto rm = t.get()->GetReplicaLocations();

    *output << Substitute(
        "<tr><td><a href=\"/table?id=$0\">$1</a></td><td>$2</td>"
        "<td>$3</td><td>$4</td></tr>\n",
        EscapeForHtmlToString(t->table()->id()),
        EscapeForHtmlToString(t->table()->name()),
        EscapeForHtmlToString(t->table()->id()),
        EscapeForHtmlToString(t.get()->tablet_id()),
        EscapeForHtmlToString(std::to_string(rm->size())));
  }

  *output << "</table>\n";
}

void MasterPathHandlers::HandleGetReplicationStatus(const Webserver::WebRequest& req,
                                                    Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  JsonWriter jw(output, JsonWriter::COMPACT);

  auto leaderless_ts = GetLeaderlessTablets();

  jw.StartObject();
  jw.String("leaderless_tablets");
  jw.StartArray();

  for (TabletInfoPtr t : leaderless_ts) {
    jw.StartObject();
    jw.String("table_uuid");
    jw.String(t->table()->id());
    jw.String("tablet_uuid");
    jw.String(t.get()->tablet_id());
    jw.EndObject();
  }

  jw.EndArray();
  jw.EndObject();
}

void MasterPathHandlers::HandleGetUnderReplicationStatus(const Webserver::WebRequest& req,
                                                    Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  JsonWriter jw(output, JsonWriter::COMPACT);

  auto underreplicated_ts = GetUnderReplicatedTablets();

  if(!underreplicated_ts.ok()) {
    jw.StartObject();
    jw.String("Error");
    jw.String(underreplicated_ts.status().ToString());
    jw.EndObject();
    return;
  }

  jw.StartObject();
  jw.String("underreplicated_tablets");
  jw.StartArray();

  for(TabletInfoPtr t : *underreplicated_ts) {
    jw.StartObject();
    jw.String("table_uuid");
    jw.String(t->table()->id());
    jw.String("tablet_uuid");
    jw.String(t.get()->tablet_id());
    jw.EndObject();
  }

  jw.EndArray();
  jw.EndObject();
}

void MasterPathHandlers::RootHandler(const Webserver::WebRequest& req,
                                     Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  // First check if we are the master leader. If not, make a curl call to the master leader and
  // return that as the UI payload.
  SCOPED_LEADER_SHARED_LOCK(l, master_->catalog_manager());
  if (!l.first_failed_status().ok()) {
    // We are not the leader master, retrieve the response from the leader master.
    RedirectToLeader(req, resp);
    return;
  }

  SysClusterConfigEntryPB config;
  Status s = master_->catalog_manager()->GetClusterConfig(&config);
  if (!s.ok()) {
    *output << "<div class=\"alert alert-warning\">" << s.ToString() << "</div>";
    return;
  }

  // Get all the tables.
  auto tables = master_->catalog_manager()->GetTables(GetTablesMode::kRunning);

  // Get the list of user tables.
  vector<scoped_refptr<TableInfo> > user_tables;
  for (scoped_refptr<TableInfo> table : tables) {
    if (master_->catalog_manager()->IsUserTable(*table)) {
      user_tables.push_back(table);
    }
  }
  // Get the version info.
  VersionInfoPB version_info;
  VersionInfo::GetVersionInfoPB(&version_info);

  // Display the overview information.
  (*output) << "<h1>YugabyteDB</h1>\n";

  (*output) << "<div class='row dashboard-content'>\n";

  (*output) << "<div class='col-xs-12 col-md-8 col-lg-6'>\n";
  (*output) << "<div class='panel panel-default'>\n"
            << "<div class='panel-heading'><h2 class='panel-title'> Overview</h2></div>\n";
  (*output) << "<div class='panel-body table-responsive'>";
  (*output) << "<table class='table'>\n";

  // Universe UUID.
  (*output) << "  <tr>";
  (*output) << Substitute(" <td>$0<span class='yb-overview'>$1</span></td>",
                          "<i class='fa fa-database yb-dashboard-icon' aria-hidden='true'></i>",
                          "Universe UUID ");
  (*output) << Substitute(" <td>$0</td>",
                          config.cluster_uuid());
  (*output) << "  </tr>\n";

  // Replication factor.
  (*output) << "  <tr>";
  (*output) << Substitute(" <td>$0<span class='yb-overview'>$1</span></td>",
                          "<i class='fa fa-files-o yb-dashboard-icon' aria-hidden='true'></i>",
                          "Replication Factor ");
  int num_replicas = 0;
  s = master_->catalog_manager()->GetReplicationFactor(&num_replicas);
  if (!s.ok()) {
    s = s.CloneAndPrepend("Unable to determine Replication factor.");
    LOG(WARNING) << s.ToString();
    *output << "<h1>" << s.ToString() << "</h1>\n";
  }
  (*output) << Substitute(" <td>$0 <a href='$1' class='btn btn-default pull-right'>$2</a></td>",
                          num_replicas,
                          "/cluster-config",
                          "See full config &raquo;");
  (*output) << "  </tr>\n";

  // Tserver count.
  (*output) << "  <tr>";
  (*output) << Substitute(" <td>$0<span class='yb-overview'>$1</span></td>",
                          "<i class='fa fa-server yb-dashboard-icon' aria-hidden='true'></i>",
                          "Num Nodes (TServers) ");
  (*output) << Substitute(" <td>$0 <a href='$1' class='btn btn-default pull-right'>$2</a></td>",
                          GetTserverCountForDisplay(master_->ts_manager()),
                          "/tablet-servers",
                          "See all nodes &raquo;");
  (*output) << "  </tr>\n";

  // Num user tables.
  (*output) << "  <tr>";
  (*output) << Substitute(" <tr><td>$0<span class='yb-overview'>$1</span></td>",
                          "<i class='fa fa-table yb-dashboard-icon' aria-hidden='true'></i>",
                          "Num User Tables ");
  (*output) << Substitute(" <td>$0 <a href='$1' class='btn btn-default pull-right'>$2</a></td>",
                          user_tables.size(),
                          "/tables",
                          "See all tables &raquo;");
  (*output) << "  </tr>\n";

  // Load Balancer State
  {
    IsLoadBalancerIdleRequestPB req;
    IsLoadBalancerIdleResponsePB resp;
    Status isIdle = master_->catalog_manager()->IsLoadBalancerIdle(&req, &resp);

    (*output) << Substitute(" <tr><td>$0<span class='yb-overview'>$1</span></td>"
                            "<td><i class='fa $2' aria-hidden='true'> </i></td></tr>\n",
                            "<i class='fa fa-tasks yb-dashboard-icon' aria-hidden='true'></i>",
                            "Is Load Balanced?",
                            isIdle.ok() ? "fa-check"
                                        : "fa-times label label-danger");
  }
  // Build version and type.
  (*output) << Substitute("  <tr><td>$0<span class='yb-overview'>$1</span></td><td>$2</td></tr>\n",
                          "<i class='fa fa-code-fork yb-dashboard-icon' aria-hidden='true'></i>",
                          "YugabyteDB Version ", version_info.version_number());
  (*output) << Substitute("  <tr><td>$0<span class='yb-overview'>$1</span></td><td>$2</td></tr>\n",
                          "<i class='fa fa-terminal yb-dashboard-icon' aria-hidden='true'></i>",
                          "Build Type ", version_info.build_type());
  (*output) << "</table>";
  (*output) << "</div> <!-- panel-body -->\n";
  (*output) << "</div> <!-- panel -->\n";
  (*output) << "</div> <!-- col-xs-12 col-md-8 col-lg-6 -->\n";

  // Display the master info.
  (*output) << "<div class='col-xs-12 col-md-8 col-lg-6'>\n";
  HandleMasters(req, resp);
  (*output) << "</div> <!-- col-xs-12 col-md-8 col-lg-6 -->\n";
}

void MasterPathHandlers::HandleMasters(const Webserver::WebRequest& req,
                                       Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  vector<ServerEntryPB> masters;
  Status s = master_->ListMasters(&masters);
  if (!s.ok()) {
    s = s.CloneAndPrepend("Unable to list Masters");
    LOG(WARNING) << s.ToString();
    *output << "<h1>" << s.ToString() << "</h1>\n";
    return;
  }
  (*output) << "<div class='panel panel-default'>\n"
            << "<div class='panel-heading'><h2 class='panel-title'>Masters</h2></div>\n";
  (*output) << "<div class='panel-body table-responsive'>";
  (*output) << "<table class='table'>\n";
  (*output) << "  <tr>\n"
            << "    <th>Server</th>\n"
            << "    <th>RAFT Role</th>\n"
            << "    <th>Uptime</th>\n"
            << "    <th>Details</th>\n"
            << "  </tr>\n";

  for (const ServerEntryPB& master : masters) {
    if (master.has_error()) {
      string error = StatusFromPB(master.error()).ToString();
      *output << "  <tr>\n";
      const string kErrStart = "peer ([";
      const string kErrEnd = "])";
      size_t start_pos = error.find(kErrStart);
      size_t end_pos = error.find(kErrEnd);
      if (start_pos != string::npos && end_pos != string::npos && (start_pos < end_pos)) {
        start_pos = start_pos + kErrStart.length();
        string host_port = error.substr(start_pos, end_pos - start_pos);
        *output << "<td><font color='red'>" << EscapeForHtmlToString(host_port)
                << "</font></td>\n";
        *output << "<td><font color='red'>" << RaftPeerPB_Role_Name(RaftPeerPB::UNKNOWN_ROLE)
                << "</font></td>\n";
      }
      *output << Substitute("    <td colspan=2><font color='red'><b>ERROR: $0</b></font></td>\n",
                              EscapeForHtmlToString(error));
      *output << "  </tr>\n";
      continue;
    }
    auto reg = master.registration();
    string host_port = GetHttpHostPortFromServerRegistration(reg);
    string reg_text = RegistrationToHtml(reg, host_port);
    if (master.instance_id().permanent_uuid() == master_->instance_pb().permanent_uuid()) {
      reg_text = Substitute("<b>$0</b>", reg_text);
    }
    string raft_role = master.has_role() ? RaftPeerPB_Role_Name(master.role()) : "N/A";
    auto delta = Env::Default()->NowMicros() - master.instance_id().start_time_us();
    string uptime = UptimeString(MonoDelta::FromMicroseconds(delta).ToSeconds());
    string cloud = reg.cloud_info().placement_cloud();
    string region = reg.cloud_info().placement_region();
    string zone = reg.cloud_info().placement_zone();

    *output << "  <tr>\n"
            << "    <td>" << reg_text << "</td>\n"
            << "    <td>" << raft_role << "</td>\n"
            << "    <td>" << uptime << "</td>\n"
            << "    <td><div><span class='yb-overview'>CLOUD: </span>" << cloud << "</div>\n"
            << "        <div><span class='yb-overview'>REGION: </span>" << region << "</div>\n"
            << "        <div><span class='yb-overview'>ZONE: </span>" << zone << "</div>\n"
            << "        <div><span class='yb-overview'>UUID: </span>"
            << master.instance_id().permanent_uuid()
            << "</div></td>\n"
            << "  </tr>\n";
  }

  (*output) << "</table>";
  (*output) << "</div> <!-- panel-body -->\n";
  (*output) << "</div> <!-- panel -->\n";
}

namespace {

// Visitor for the catalog table which dumps tables and tablets in a JSON format. This
// dump is interpreted by the CM agent in order to track time series entities in the SMON
// database.
//
// This implementation relies on scanning the catalog table directly instead of using the
// catalog manager APIs. This allows it to work even on a non-leader master, and avoids
// any requirement for locking. For the purposes of metrics entity gathering, it's OK to
// serve a slightly stale snapshot.
//
// It is tempting to directly dump the metadata protobufs using JsonWriter::Protobuf(...),
// but then we would be tying ourselves to textual compatibility of the PB field names in
// our catalog table. Instead, the implementation specifically dumps the fields that we
// care about.
//
// This should be considered a "stable" protocol -- do not rename, remove, or restructure
// without consulting with the CM team.
class JsonDumperBase {
 public:
  explicit JsonDumperBase(JsonWriter* jw) : jw_(jw) {}

  virtual ~JsonDumperBase() {}

  virtual std::string name() const = 0;

 protected:
  JsonWriter* jw_;
};

class JsonKeyspaceDumper : public Visitor<PersistentNamespaceInfo>, public JsonDumperBase {
 public:
  explicit JsonKeyspaceDumper(JsonWriter* jw) : JsonDumperBase(jw) {}

  std::string name() const override { return "keyspaces"; }

  virtual Status Visit(const std::string& keyspace_id,
                       const SysNamespaceEntryPB& metadata) override {
    jw_->StartObject();
    jw_->String("keyspace_id");
    jw_->String(keyspace_id);

    jw_->String("keyspace_name");
    jw_->String(metadata.name());

    jw_->String("keyspace_type");
    jw_->String(DatabaseTypeName((metadata.database_type())));

    jw_->EndObject();
    return Status::OK();
  }
};

class JsonTableDumper : public Visitor<PersistentTableInfo>, public JsonDumperBase {
 public:
  explicit JsonTableDumper(JsonWriter* jw) : JsonDumperBase(jw) {}

  std::string name() const override { return "tables"; }

  Status Visit(const std::string& table_id, const SysTablesEntryPB& metadata) override {
    if (metadata.state() != SysTablesEntryPB::RUNNING) {
      return Status::OK();
    }

    jw_->StartObject();
    jw_->String("table_id");
    jw_->String(table_id);

    jw_->String("keyspace_id");
    jw_->String(metadata.namespace_id());

    jw_->String("table_name");
    jw_->String(metadata.name());

    jw_->String("state");
    jw_->String(SysTablesEntryPB::State_Name(metadata.state()));

    jw_->EndObject();
    return Status::OK();
  }
};

class JsonTabletDumper : public Visitor<PersistentTabletInfo>, public JsonDumperBase {
 public:
  explicit JsonTabletDumper(JsonWriter* jw) : JsonDumperBase(jw) {}

  std::string name() const override { return "tablets"; }

  Status Visit(const std::string& tablet_id, const SysTabletsEntryPB& metadata) override {
    const std::string& table_id = metadata.table_id();
    if (metadata.state() != SysTabletsEntryPB::RUNNING) {
      return Status::OK();
    }

    jw_->StartObject();
    jw_->String("table_id");
    jw_->String(table_id);

    jw_->String("tablet_id");
    jw_->String(tablet_id);

    jw_->String("state");
    jw_->String(SysTabletsEntryPB::State_Name(metadata.state()));

    // Dump replica UUIDs
    if (metadata.has_committed_consensus_state()) {
      const consensus::ConsensusStatePB& cs = metadata.committed_consensus_state();
      jw_->String("replicas");
      jw_->StartArray();
      for (const RaftPeerPB& peer : cs.config().peers()) {
        jw_->StartObject();
        jw_->String("type");
        jw_->String(RaftPeerPB::MemberType_Name(peer.member_type()));

        jw_->String("server_uuid");
        jw_->String(peer.permanent_uuid());

        jw_->String("addr");
        const auto& host_port = peer.last_known_private_addr()[0];
        jw_->String(HostPortPBToString(host_port));

        jw_->EndObject();
      }
      jw_->EndArray();

      if (cs.has_leader_uuid()) {
        jw_->String("leader");
        jw_->String(cs.leader_uuid());
      }
    }

    jw_->EndObject();
    return Status::OK();
  }
};

template <class Dumper>
Status JsonDumpCollection(JsonWriter* jw, Master* master, stringstream* output) {
  unique_ptr<Dumper> json_dumper(new Dumper(jw));
  jw->String(json_dumper->name());
  jw->StartArray();
  const Status s = master->catalog_manager()->sys_catalog()->Visit(json_dumper.get());
  if (s.ok()) {
    // End the array only if there is no error.
    jw->EndArray();
  } else {
    // Print just an error message.
    output->str("");
    JsonWriter jw_err(output, JsonWriter::COMPACT);
    jw_err.StartObject();
    jw_err.String("error");
    jw_err.String(s.ToString());
    jw_err.EndObject();
  }
  return s;
}

} // anonymous namespace

void MasterPathHandlers::HandleDumpEntities(const Webserver::WebRequest& req,
                                            Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  JsonWriter jw(output, JsonWriter::COMPACT);
  jw.StartObject();

  if (JsonDumpCollection<JsonKeyspaceDumper>(&jw, master_, output).ok() &&
      JsonDumpCollection<JsonTableDumper>(&jw, master_, output).ok() &&
      JsonDumpCollection<JsonTabletDumper>(&jw, master_, output).ok()) {
    // End the object only if there is no error.
    jw.EndObject();
  }
}

void MasterPathHandlers::HandleCheckIfLeader(const Webserver::WebRequest& req,
                                              Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  JsonWriter jw(output, JsonWriter::COMPACT);
  jw.StartObject();
  {
    SCOPED_LEADER_SHARED_LOCK(l, master_->catalog_manager());

    // If we are not the master leader.
    if (!l.first_failed_status().ok()) {
      resp->code = 503;
      return;
    }

    jw.String("STATUS");
    jw.String(l.leader_status().CodeAsString());
    jw.EndObject();
    return;
  }
}

void MasterPathHandlers::HandleGetMastersStatus(const Webserver::WebRequest& req,
                                                    Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  vector<ServerEntryPB> masters;
  Status s = master_->ListMasters(&masters);
  ListMastersResponsePB pb_resp;
  JsonWriter jw(output, JsonWriter::COMPACT);
  if (!s.ok()) {
    jw.Protobuf(pb_resp);
    return;
  }
  for (const ServerEntryPB& master : masters) {
    pb_resp.add_masters()->CopyFrom(master);
  }
  jw.Protobuf(pb_resp);
}

void MasterPathHandlers::HandleGetClusterConfig(
  const Webserver::WebRequest& req, Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  *output << "<h1>Current Cluster Config</h1>\n";
  SysClusterConfigEntryPB config;
  Status s = master_->catalog_manager()->GetClusterConfig(&config);
  if (!s.ok()) {
    *output << "<div class=\"alert alert-warning\">" << s.ToString() << "</div>";
    return;
  }

  *output << "<div class=\"alert alert-success\">Successfully got cluster config!</div>"
  << "<pre class=\"prettyprint\">" << config.DebugString() << "</pre>";
}

void MasterPathHandlers::HandleGetClusterConfigJSON(
  const Webserver::WebRequest& req, Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  JsonWriter jw(output, JsonWriter::COMPACT);

  master_->catalog_manager()->AssertLeaderLockAcquiredForReading();

  SysClusterConfigEntryPB config;
  Status s = master_->catalog_manager()->GetClusterConfig(&config);
  if (!s.ok()) {
    jw.StartObject();
    jw.String("error");
    jw.String(s.ToString());
    jw.EndObject();
    return;
  }

  // return cluster config in JSON format
  jw.Protobuf(config);
}

void MasterPathHandlers::HandleVersionInfoDump(
    const Webserver::WebRequest& req, Webserver::WebResponse* resp) {
  std::stringstream *output = &resp->output;
  JsonWriter jw(output, JsonWriter::PRETTY);

  // Get the version info.
  VersionInfoPB version_info;
  VersionInfo::GetVersionInfoPB(&version_info);

  jw.Protobuf(version_info);
}

void MasterPathHandlers::HandlePrettyLB(
  const Webserver::WebRequest& req, Webserver::WebResponse* resp) {

  std::stringstream *output = &resp->output;

  // Don't render if there are more than 5 tservers.
  vector<std::shared_ptr<TSDescriptor>> descs;
  const auto& ts_manager = master_->ts_manager();
  ts_manager->GetAllDescriptors(&descs);

  if (descs.size() > 5) {
    *output << "<div class='alert alert-warning'>"
            << "Current configuration has more than 5 tservers. Not recommended"
            << " to view this pretty display as it might not be rendered properly."
            << "</div>";
    return;
  }

  // Don't render if there is a lot of placement nesting.
  unordered_set<std::string> clouds;
  unordered_set<std::string> regions;
  // Map of zone -> {tserver UUIDs}
  // e.g. zone1 -> {ts1uuid, ts2uuid, ts3uuid}.
  unordered_map<std::string, vector<std::string>> zones;
  for (const auto& desc : descs) {
    std::string uuid = desc->permanent_uuid();
    std::string cloud = desc->GetCloudInfo().placement_cloud();
    std::string region = desc->GetCloudInfo().placement_region();
    std::string zone = desc->GetCloudInfo().placement_zone();

    zones[zone].push_back(uuid);
    clouds.insert(cloud);
    regions.insert(region);
  }

  // If the we have more than 1 cloud or more than 1 region skip this page
  // as currently it might not diplay prettily.
  if (clouds.size() > 1 || regions.size() > 1 || zones.size() > 3) {
    *output << "<div class='alert alert-warning'>"
            << "Current placement has more than 1 cloud provider or 1 region or 3 zones. "
            << "Not recommended to view this pretty display as it might not be rendered properly."
            << "</div>";
    return;
  }

  // Get the TServerTree.
  // A map of tserver -> all tables with their tablets.
  TServerTree tserver_tree;
  Status s = CalculateTServerTree(&tserver_tree);
  if (!s.ok()) {
    *output << "<div class='alert alert-warning'>"
            << "Current placement has more than 4 tables. Not recommended"
            << " to view this pretty display as it might not be rendered properly."
            << "</div>";
    return;
  }

  BlacklistSet blacklist = master_->catalog_manager()->BlacklistSetFromPB();

  // A single zone.
  int color_index = 0;
  unordered_map<std::string, std::string> tablet_colors;

  *output << "<div class='row'>\n";
  for (const auto& zone : zones) {
    // Panel for this Zone.
    // Split the zones in proportion of the number of tservers in each zone.
    *output << Substitute("<div class='col-lg-$0'>\n", 12*zone.second.size()/descs.size());

    // Change the display of the panel if all tservers in this zone are down.
    bool all_tservers_down = true;
    for (const auto& tserver : zone.second) {
      std::shared_ptr<TSDescriptor> desc;
      if (!master_->ts_manager()->LookupTSByUUID(tserver, &desc)) {
        continue;
      }
      all_tservers_down = all_tservers_down && !desc->IsLive();
    }
    string zone_panel_display = "panel-success";
    if (all_tservers_down) {
      zone_panel_display = "panel-danger";
    }

    *output << Substitute("<div class='panel $0'>\n", zone_panel_display);
    *output << Substitute("<div class='panel-heading'>"
                          "<h6 class='panel-title'>Zone: $0</h6></div>\n", zone.first);
    *output << "<div class='row'>\n";

    // Tservers for this panel.
    for (const auto& tserver : zone.second) {
      // Split tservers equally.
      *output << Substitute("<div class='col-lg-$0'>\n", 12/(zone.second.size()));
      std::shared_ptr<TSDescriptor> desc;
      if (!master_->ts_manager()->LookupTSByUUID(tserver, &desc)) {
        continue;
      }

      // Get the state of tserver.
      bool ts_live = desc->IsLive();
      // Get whether tserver is blacklisted.
      bool blacklisted = desc->IsBlacklisted(blacklist);
      string panel_type = "panel-success";
      string icon_type = "fa-check";
      if (!ts_live || blacklisted) {
        panel_type = "panel-danger";
        icon_type = "fa-times";
      }
      *output << Substitute("<div class='panel $0' style='margin-bottom: 0px'>\n", panel_type);

      // Point to the tablet servers link.
      TSRegistrationPB reg = desc->GetRegistration();
      string host_port = GetHttpHostPortFromServerRegistration(reg.common());
      *output << Substitute("<div class='panel-heading'>"
                            "<h6 class='panel-title'><a href='http://$0'>TServer - $0    "
                            "<i class='fa $1'></i></a></h6></div>\n",
                            HostPortPBToString(reg.common().http_addresses(0)),
                            icon_type);

      *output << "<table class='table table-borderless table-hover'>\n";
      for (const auto& table : tserver_tree[tserver]) {
        *output << Substitute("<tr height='200px'>\n");
        // Display the table name.
        string tname = master_->catalog_manager()->GetTableInfo(table.first)->name();
        // Link the table name to the corresponding table page on the master.
        ServerRegistrationPB reg;
        if (!master_->GetMasterRegistration(&reg).ok()) {
          continue;
        }
        *output << Substitute("<td><h4><a href='http://$0/table?id=$1'>"
                              "<i class='fa fa-table'></i>    $2</a></h4>\n",
                              HostPortPBToString(reg.http_addresses(0)),
                              table.first,
                              tname);
        // Replicas of this table.
        for (const auto& replica : table.second) {
          // All the replicas of the same tablet will have the same color, so
          // look it up in the map if assigned, otherwise assign one from the pool.
          if (tablet_colors.find(replica.tablet_id) == tablet_colors.end()) {
            tablet_colors[replica.tablet_id] = kYBColorList[color_index];
            color_index = (color_index + 1)%kYBColorList.size();
          }

          // Leaders and followers have different formatting.
          // Leaders need to stand out.
          if (replica.role == consensus::RaftPeerPB_Role_LEADER) {
            *output << Substitute("<button type='button' class='btn btn-default'"
                                "style='background-image:none; border: 6px solid $0; "
                                "font-weight: bolder'>"
                                "L</button>\n",
                                tablet_colors[replica.tablet_id]);
          } else {
            *output << Substitute("<button type='button' class='btn btn-default'"
                                "style='background-image:none; border: 4px dotted $0'>"
                                "F</button>\n",
                                tablet_colors[replica.tablet_id]);
          }
        }
        *output << "</td>\n";
        *output << "</tr>\n";
      }
      *output << "</table><!-- tserver-level-table -->\n";
      *output << "</div><!-- tserver-level-panel -->\n";
      *output << "</div><!-- tserver-level-spacing -->\n";
    }
    *output << "</div><!-- tserver-level-row -->\n";
    *output << "</div><!-- zone-level-panel -->\n";
    *output << "</div><!-- zone-level-spacing -->\n";
  }
  *output << "</div><!-- zone-level-row -->\n";
}

void MasterPathHandlers::HandleLBStatistics(
  const Webserver::WebRequest& req, Webserver::WebResponse* resp) {

  // Displays a table of all tables for which load balancing has been skipped.
  std::stringstream *output = &resp->output;
  const auto tables = master_->catalog_manager()
                              ->load_balancer()->GetAllTablesLoadBalancerSkipped();

  *output << "<h3>Load balance skipped Tables</h3>\n";
  *output << "<table class='table table-striped'>\n";
  *output << "  <tr><th>Table Name</th><th>Table UUID</th><th>Table Type</th></tr>\n";

  for (const auto& table : tables) {
    if (table->is_system()) {
      continue;
    }
    *output << Substitute(
        "<tr><td><a href=\"/table?id=$0\">$1</a></td><td>$2</td><td>$3</td></tr>\n",
        EscapeForHtmlToString(table->id()),
        EscapeForHtmlToString(table->name()),
        EscapeForHtmlToString(table->id()),
        EscapeForHtmlToString(TableType_Name(table->GetTableType())));
  }

  *output << "</table>\n";

}

Status MasterPathHandlers::Register(Webserver* server) {
  bool is_styled = true;
  bool is_on_nav_bar = true;

  // The set of handlers visible on the nav bar.
  server->RegisterPathHandler(
    "/", "Home", std::bind(&MasterPathHandlers::RootHandler, this, _1, _2), is_styled,
    is_on_nav_bar, "fa fa-home");
  Webserver::PathHandlerCallback cb =
      std::bind(&MasterPathHandlers::HandleTabletServers, this, _1, _2,
                TServersViewType::kTServersDefaultView);
  server->RegisterPathHandler(
      "/tablet-servers", "Tablet Servers",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      is_on_nav_bar, "fa fa-server");
  cb = std::bind(&MasterPathHandlers::HandleTabletServers, this, _1, _2,
                 TServersViewType::kTServersClocksView);
  server->RegisterPathHandler(
      "/tablet-server-clocks", "Tablet Server Clocks",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      false /* is_on_nav_bar */);
  cb = std::bind(&MasterPathHandlers::HandleCatalogManager,
      this, _1, _2, false /* only_user_tables */);
  server->RegisterPathHandler(
      "/tables", "Tables",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      is_on_nav_bar, "fa fa-table");

  // The set of handlers not currently visible on the nav bar.
  cb = std::bind(&MasterPathHandlers::HandleTablePage, this, _1, _2);
  server->RegisterPathHandler(
      "/table", "", std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb),
      is_styled, false);
  server->RegisterPathHandler(
      "/masters", "Masters", std::bind(&MasterPathHandlers::HandleMasters, this, _1, _2), is_styled,
      false);
  cb = std::bind(&MasterPathHandlers::HandleGetClusterConfig, this, _1, _2);
  server->RegisterPathHandler(
      "/cluster-config", "Cluster Config",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      false);
  cb = std::bind(&MasterPathHandlers::HandleGetClusterConfigJSON, this, _1, _2);
  server->RegisterPathHandler(
      "/api/v1/cluster-config", "Cluster Config JSON",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), false,
      false);
  cb = std::bind(&MasterPathHandlers::HandleTasksPage, this, _1, _2);
  server->RegisterPathHandler(
      "/tasks", "Tasks",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      false);
  cb = std::bind(&MasterPathHandlers::HandleTabletReplicasPage, this, _1, _2);
  server->RegisterPathHandler(
      "/tablet-replication", "Tablet Replication Health",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      false);
  cb = std::bind(&MasterPathHandlers::HandleLBStatistics, this, _1, _2);
  server->RegisterPathHandler(
      "/lb-statistics", "Load balancer Statistics",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      false);
  cb = std::bind(&MasterPathHandlers::HandlePrettyLB, this, _1, _2);
  server->RegisterPathHandler(
      "/pretty-lb", "Load balancer Pretty Picture",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), is_styled,
      false);

  // JSON Endpoints
  cb = std::bind(&MasterPathHandlers::HandleGetTserverStatus, this, _1, _2);
  server->RegisterPathHandler(
      "/api/v1/tablet-servers", "Tserver Statuses",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), false, false);
  cb = std::bind(&MasterPathHandlers::HandleHealthCheck, this, _1, _2);
  server->RegisterPathHandler(
      "/api/v1/health-check", "Cluster Health Check",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), false, false);
  cb = std::bind(&MasterPathHandlers::HandleGetReplicationStatus, this, _1, _2);
  server->RegisterPathHandler(
      "/api/v1/tablet-replication", "Tablet Replication Health",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), false, false);
  cb = std::bind(&MasterPathHandlers::HandleGetUnderReplicationStatus, this, _1, _2);
  server->RegisterPathHandler(
      "/api/v1/tablet-under-replication", "Tablet UnderReplication Status",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), false, false);
  cb = std::bind(&MasterPathHandlers::HandleDumpEntities, this, _1, _2);
  server->RegisterPathHandler(
      "/dump-entities", "Dump Entities",
      std::bind(&MasterPathHandlers::CallIfLeaderOrPrintRedirect, this, _1, _2, cb), false, false);
  server->RegisterPathHandler(
      "/api/v1/is-leader", "Leader Check",
      std::bind(&MasterPathHandlers::HandleCheckIfLeader, this, _1, _2), false, false);
  server->RegisterPathHandler(
      "/api/v1/masters", "Master Statuses",
      std::bind(&MasterPathHandlers::HandleGetMastersStatus, this, _1, _2), false, false);
  server->RegisterPathHandler(
      "/api/v1/version", "YB Version Information",
      std::bind(&MasterPathHandlers::HandleVersionInfoDump, this, _1, _2), false, false);
  return Status::OK();
}

string MasterPathHandlers::RaftConfigToHtml(const std::vector<TabletReplica>& locations,
                                            const std::string& tablet_id) const {
  stringstream html;

  html << "<ul>\n";
  for (const TabletReplica& location : locations) {
    string location_html = TSDescriptorToHtml(*location.ts_desc, tablet_id);
    if (location.role == RaftPeerPB::LEADER) {
      html << Substitute("  <li><b>LEADER: $0</b></li>\n", location_html);
    } else {
      html << Substitute("  <li>$0: $1</li>\n",
                         RaftPeerPB_Role_Name(location.role), location_html);
    }
  }
  html << "</ul>\n";
  return html.str();
}

string MasterPathHandlers::TSDescriptorToHtml(const TSDescriptor& desc,
                                              const std::string& tablet_id) const {
  TSRegistrationPB reg = desc.GetRegistration();

  if (reg.common().http_addresses().size() > 0) {
    return Substitute(
        "<a href=\"http://$0/tablet?id=$1\">$2</a>",
        HostPortPBToString(reg.common().http_addresses(0)),
        EscapeForHtmlToString(tablet_id),
        EscapeForHtmlToString(reg.common().http_addresses(0).host()));
  } else {
    return EscapeForHtmlToString(desc.permanent_uuid());
  }
}

string MasterPathHandlers::RegistrationToHtml(
    const ServerRegistrationPB& reg, const std::string& link_text) const {
  string link_html = EscapeForHtmlToString(link_text);
  if (reg.http_addresses().size() > 0) {
    link_html = Substitute("<a href=\"http://$0/\">$1</a>",
                           HostPortPBToString(reg.http_addresses(0)),
                           link_html);
  }
  return link_html;
}

void MasterPathHandlers::CalculateTabletMap(TabletCountMap* tablet_map) {
  auto tables = master_->catalog_manager()->GetTables(GetTablesMode::kRunning);
  for (const auto& table : tables) {
    if (master_->catalog_manager()->IsColocatedUserTable(*table)) {
      // will be taken care of by colocated parent table
      continue;
    }

    TabletInfos tablets;
    table->GetAllTablets(&tablets);
    bool is_user_table = master_->catalog_manager()->IsUserCreatedTable(*table);

    for (const auto& tablet : tablets) {
      auto replication_locations = tablet->GetReplicaLocations();

      for (const auto& replica : *replication_locations) {
        if (is_user_table || master_->catalog_manager()->IsColocatedParentTable(*table)
                          || master_->catalog_manager()->IsTablegroupParentTable(*table)) {
          if (replica.second.role == consensus::RaftPeerPB_Role_LEADER) {
            (*tablet_map)[replica.first].user_tablet_leaders++;
          } else {
            (*tablet_map)[replica.first].user_tablet_followers++;
          }
        } else {
          if (replica.second.role == consensus::RaftPeerPB_Role_LEADER) {
            (*tablet_map)[replica.first].system_tablet_leaders++;
          } else {
            (*tablet_map)[replica.first].system_tablet_followers++;
          }
        }
      }
    }
  }
}

Status MasterPathHandlers::CalculateTServerTree(TServerTree* tserver_tree) {
  auto tables = master_->catalog_manager()->GetTables(GetTablesMode::kRunning);

  int count = 0;
  for (const auto& table : tables) {
    if (!master_->catalog_manager()->IsUserCreatedTable(*table) ||
    master_->catalog_manager()->IsColocatedUserTable(*table)) {
      continue;
    }

    count++;
    if (count > 4) {
      return STATUS(NotSupported, "Not supported for more than 4 tables.");
    }
  }

  for (const auto& table : tables) {
    if (!master_->catalog_manager()->IsUserCreatedTable(*table) ||
    master_->catalog_manager()->IsColocatedUserTable(*table)) {
      // only display user created tables that are not colocated.
      continue;
    }

    TabletInfos tablets;
    table->GetAllTablets(&tablets);

    for (const auto& tablet : tablets) {
      auto replica_locations = tablet->GetReplicaLocations();
      for (const auto& replica : *replica_locations) {
        (*tserver_tree)[replica.first][tablet->table()->id()].emplace_back(
          replica.second.role,
          tablet->tablet_id()
        );
      }
    }
  }

  return Status::OK();
}




} // namespace master
} // namespace yb
