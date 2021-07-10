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

#include "yb/tablet/operations/truncate_operation.h"

#include <glog/logging.h>

#include "yb/common/wire_protocol.h"
#include "yb/consensus/consensus_round.h"
#include "yb/rpc/rpc_context.h"
#include "yb/server/hybrid_clock.h"
#include "yb/tablet/tablet.h"
#include "yb/tablet/tablet_peer.h"
#include "yb/tserver/tserver.pb.h"
#include "yb/util/trace.h"

namespace yb {
namespace tablet {

template <>
void RequestTraits<tserver::TruncateRequestPB>::SetAllocatedRequest(
    consensus::ReplicateMsg* replicate, tserver::TruncateRequestPB* request) {
  replicate->set_allocated_truncate_request(request);
}

template <>
tserver::TruncateRequestPB* RequestTraits<tserver::TruncateRequestPB>::MutableRequest(
    consensus::ReplicateMsg* replicate) {
  return replicate->mutable_truncate_request();
}

Status TruncateOperation::DoAborted(const Status& status) {
  return status;
}

Status TruncateOperation::DoReplicated(int64_t leader_term, Status* complete_status) {
  TRACE("APPLY TRUNCATE: started");

  RETURN_NOT_OK(tablet()->Truncate(this));

  TRACE("APPLY TRUNCATE: finished");

  return Status::OK();
}

}  // namespace tablet
}  // namespace yb
