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

#include "yb/integration-tests/cql_test_base.h"

#include "yb/consensus/raft_consensus.h"

#include "yb/util/random_util.h"
#include "yb/util/test_macros.h"
#include "yb/util/test_util.h"

using namespace std::literals;

DECLARE_int64(cql_processors_limit);
DECLARE_int32(client_read_write_timeout_ms);

namespace yb {

class CqlTest : public CqlTestBase {
 public:
  virtual ~CqlTest() = default;
};

TEST_F(CqlTest, ProcessorsLimit) {
  constexpr int kSessions = 10;
  FLAGS_cql_processors_limit = 1;

  std::vector<CassandraSession> sessions;
  bool has_failures = false;
  for (int i = 0; i != kSessions; ++i) {
    auto session = EstablishSession(driver_.get());
    if (!session.ok()) {
      LOG(INFO) << "Establish session failure: " << session.status();
      ASSERT_TRUE(session.status().IsServiceUnavailable());
      has_failures = true;
    } else {
      sessions.push_back(std::move(*session));
    }
  }

  ASSERT_TRUE(has_failures);
}

// Execute delete in parallel to transactional update of the same row.
TEST_F(CqlTest, ConcurrentDeleteRowAndUpdateColumn) {
  constexpr int kIterations = 70;
  auto session1 = ASSERT_RESULT(EstablishSession(driver_.get()));
  auto session2 = ASSERT_RESULT(EstablishSession(driver_.get()));
  ASSERT_OK(session1.ExecuteQuery(
      "CREATE TABLE t (i INT PRIMARY KEY, j INT) WITH transactions = { 'enabled' : true }"));
  auto insert_prepared = ASSERT_RESULT(session1.Prepare("INSERT INTO t (i, j) VALUES (?, ?)"));
  for (int key = 1; key <= 2 * kIterations; ++key) {
    auto stmt = insert_prepared.Bind();
    stmt.Bind(0, key);
    stmt.Bind(1, key * 10);
    ASSERT_OK(session1.Execute(stmt));
  }
  auto update_prepared = ASSERT_RESULT(session1.Prepare(
      "BEGIN TRANSACTION "
      "  UPDATE t SET j = j + 1 WHERE i = ?;"
      "  UPDATE t SET j = j + 1 WHERE i = ?;"
      "END TRANSACTION;"));
  auto delete_prepared = ASSERT_RESULT(session1.Prepare("DELETE FROM t WHERE i = ?"));
  std::vector<CassandraFuture> futures;
  for (int i = 0; i < kIterations; ++i) {
    int k1 = i * 2 + 1;
    int k2 = i * 2 + 2;

    auto update_stmt = update_prepared.Bind();
    update_stmt.Bind(0, k1);
    update_stmt.Bind(1, k2);
    futures.push_back(session1.ExecuteGetFuture(update_stmt));
  }

  for (int i = 0; i < kIterations; ++i) {
    int k2 = i * 2 + 2;

    auto delete_stmt = delete_prepared.Bind();
    delete_stmt.Bind(0, k2);
    futures.push_back(session1.ExecuteGetFuture(delete_stmt));
  }

  for (auto& future : futures) {
    ASSERT_OK(future.Wait());
  }

  auto result = ASSERT_RESULT(session1.ExecuteWithResult("SELECT * FROM t"));
  auto iterator = result.CreateIterator();
  int num_rows = 0;
  int num_even = 0;
  while (iterator.Next()) {
    ++num_rows;
    auto row = iterator.Row();
    auto key = row.Value(0).As<int>();
    auto value = row.Value(1).As<int>();
    if ((key & 1) == 0) {
      LOG(ERROR) << "Even key: " << key;
      ++num_even;
    }
    ASSERT_EQ(value, key * 10 + 1);
    LOG(INFO) << "Row: " << key << " => " << value;
  }
  ASSERT_EQ(num_rows, kIterations);
  ASSERT_EQ(num_even, 0);
}

TEST_F(CqlTest, TestUpdateListIndexAfterOverwrite) {
  auto session = ASSERT_RESULT(EstablishSession(driver_.get()));
  auto cql = [&](const std::string query) {
    ASSERT_OK(session.ExecuteQuery(query));
  };
  cql("CREATE TABLE test(h INT, v LIST<INT>, PRIMARY KEY(h))");
  cql("INSERT INTO test (h, v) VALUES (1, [1, 2, 3])");

  auto select = [&]() -> Result<string> {
    auto result = VERIFY_RESULT(session.ExecuteWithResult("SELECT * FROM test"));
    auto iter = result.CreateIterator();
    DFATAL_OR_RETURN_ERROR_IF(!iter.Next(), STATUS(NotFound, "Did not find result in test table."));
    auto row = iter.Row();
    auto key = row.Value(0).As<int>();
    EXPECT_EQ(key, 1);
    return row.Value(1).ToString();
  };

  cql("UPDATE test SET v = [4, 5, 6] where h = 1");
  cql("UPDATE test SET v[0] = 7 WHERE h = 1");
  auto res1 = ASSERT_RESULT(select());
  EXPECT_EQ(res1, "[7, 5, 6]");

  cql("INSERT INTO test (h, v) VALUES (1, [10, 11, 12])");
  cql("UPDATE test SET v[0] = 8 WHERE h = 1");
  auto res2 = ASSERT_RESULT(select());
  EXPECT_EQ(res2, "[8, 11, 12]");
}

TEST_F(CqlTest, Timeout) {
  FLAGS_client_read_write_timeout_ms = 5000 * kTimeMultiplier;

  auto session = ASSERT_RESULT(EstablishSession(driver_.get()));
  ASSERT_OK(session.ExecuteQuery(
      "CREATE TABLE t (i INT PRIMARY KEY, j INT) WITH transactions = { 'enabled' : true }"));

  auto peers = ListTabletPeers(cluster_.get(), ListPeersFilter::kAll);
  for (const auto& peer : peers) {
    peer->raft_consensus()->TEST_DelayUpdate(100ms);
  }

  auto prepared = ASSERT_RESULT(session.Prepare(
      "BEGIN TRANSACTION "
      "  INSERT INTO t (i, j) VALUES (?, ?);"
      "END TRANSACTION;"));
  struct Request {
    CassandraFuture future;
    CoarseTimePoint start_time;
  };
  std::deque<Request> requests;
  constexpr int kOps = 50;
  constexpr int kKey = 42;
  int executed_ops = 0;
  for (;;) {
    while (!requests.empty() && requests.front().future.Ready()) {
      WARN_NOT_OK(requests.front().future.Wait(), "Insert failed");
      auto passed = CoarseMonoClock::now() - requests.front().start_time;
      ASSERT_LE(passed, FLAGS_client_read_write_timeout_ms * 1ms + 2s * kTimeMultiplier);
      requests.pop_front();
    }
    if (executed_ops >= kOps) {
      if (requests.empty()) {
        break;
      }
      std::this_thread::sleep_for(100ms);
      continue;
    }

    auto stmt = prepared.Bind();
    stmt.Bind(0, kKey);
    stmt.Bind(1, ++executed_ops);
    requests.push_back(Request {
        .future = session.ExecuteGetFuture(stmt),
        .start_time = CoarseMonoClock::now(),
    });
  }
}

} // namespace yb
