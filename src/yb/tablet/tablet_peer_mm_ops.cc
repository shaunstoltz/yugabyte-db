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

#include "yb/tablet/tablet_peer_mm_ops.h"

#include <algorithm>
#include <map>
#include <mutex>
#include <string>

#include <gflags/gflags.h>
#include "yb/gutil/strings/substitute.h"
#include "yb/tablet/maintenance_manager.h"
#include "yb/tablet/tablet.h"
#include "yb/tablet/tablet_metrics.h"
#include "yb/util/flag_tags.h"
#include "yb/util/metrics.h"

METRIC_DEFINE_gauge_uint32(table, log_gc_running,
                           "Log GCs Running",
                           yb::MetricUnit::kOperations,
                           "Number of log GC operations currently running.");
METRIC_DEFINE_histogram(table, log_gc_duration,
                        "Log GC Duration",
                        yb::MetricUnit::kMilliseconds,
                        "Time spent garbage collecting the logs.", 60000LU, 1);

namespace yb {
namespace tablet {

using std::map;
using strings::Substitute;

//
// LogGCOp.
//

LogGCOp::LogGCOp(TabletPeer* tablet_peer)
    : MaintenanceOp(
          StringPrintf("LogGCOp(%s)", tablet_peer->tablet()->tablet_id().c_str()),
          MaintenanceOp::LOW_IO_USAGE),
      tablet_peer_(tablet_peer),
      log_gc_duration_(
          METRIC_log_gc_duration.Instantiate(tablet_peer->tablet()->GetTableMetricsEntity())),
      log_gc_running_(
          METRIC_log_gc_running.Instantiate(tablet_peer->tablet()->GetTableMetricsEntity(), 0)),
      sem_(1) {}

void LogGCOp::UpdateStats(MaintenanceOpStats* stats) {
  int64_t retention_size;

  if (!tablet_peer_->GetGCableDataSize(&retention_size).ok()) {
    return;
  }
  stats->set_logs_retained_bytes(retention_size);
  stats->set_runnable(sem_.GetValue() == 1);
}

bool LogGCOp::Prepare() {
  return sem_.try_lock();
}

void LogGCOp::Perform() {
  CHECK(!sem_.try_lock());

  Status s = tablet_peer_->RunLogGC();
  if (!s.ok()) {
    s = s.CloneAndPrepend("Unexpected error while running Log GC from TabletPeer");
    LOG(ERROR) << s.ToString();
  }

  sem_.unlock();
}

scoped_refptr<Histogram> LogGCOp::DurationHistogram() const {
  return log_gc_duration_;
}

scoped_refptr<AtomicGauge<uint32_t> > LogGCOp::RunningGauge() const {
  return log_gc_running_;
}

}  // namespace tablet
}  // namespace yb
