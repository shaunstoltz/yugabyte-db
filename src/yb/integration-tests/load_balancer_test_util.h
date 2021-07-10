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

#ifndef YB_INTEGRATION_TESTS_LOAD_BALANCER_TEST_UTIL_H_
#define YB_INTEGRATION_TESTS_LOAD_BALANCER_TEST_UTIL_H_

#include <gtest/gtest.h>
#include "yb/master/ts_manager.h"

namespace yb {
namespace integration_tests {

// Collection of test utility functions for load balancer tests.

// Checks the loads given and ensure that they are within one of each other.
bool AreLoadsBalanced(const std::vector<uint32_t>& tserver_loads);

// Checks that the loads of tservers specified in zero_load_tservers is 0 and
// whether the remaining non-zero loads are balanced.
bool AreLoadsAsExpected(const std::unordered_map<master::TabletServerId, int>& tserver_loads,
                        const std::unordered_set<master::TabletServerId>& zero_load_tservers = {});

}  // namespace integration_tests
}  // namespace yb
#endif  // YB_INTEGRATION_TESTS_LOAD_BALANCER_TEST_UTIL_H_
