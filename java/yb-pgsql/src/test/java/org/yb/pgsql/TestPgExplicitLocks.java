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

package org.yb.pgsql;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.util.Pair;
import org.yb.util.YBTestRunnerNonTsanOnly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.postgresql.util.PSQLException;
import static org.yb.AssertionWrappers.*;

@RunWith(value=YBTestRunnerNonTsanOnly.class)
public class TestPgExplicitLocks extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestPgSelect.class);

  @Test
  public void testExplicitLocks() throws Exception {
    setupSimpleTable("explicitlocks");
    Connection c1 = getConnectionBuilder().connect();
    Connection c2 = getConnectionBuilder().connect();

    Statement s1 = c1.createStatement();
    Statement s2 = c2.createStatement();

    try {
      String query = "begin";
      s1.execute(query);
      query = "select * from explicitlocks where h=0 and r=0 for update";
      s1.execute(query);
    } catch (PSQLException ex) {
      LOG.error("Unexpected exception:", ex);
      throw ex;
    }

    boolean conflict_occurred = false;
    try {
      String query = "update explicitlocks set vi=5 where h=0 and r=0";
      s2.execute(query);
    } catch (PSQLException ex) {
      if (ex.getMessage().contains("Conflicts with higher priority transaction")) {
        LOG.info("Conflict ERROR");
        conflict_occurred = true;
      }
    }
    assertEquals(conflict_occurred, true);
    LOG.info("Done with the test");
  }

  private class ParallelQueryRunner {
    private Statement stmts[];

    ParallelQueryRunner(Statement s1, Statement s2) throws SQLException {
      stmts = new Statement[]{s1, s2};
      runOnBoth("SET retry_max_backoff = 0");
      stmts[0].execute("SET yb_transaction_priority_lower_bound = 0.5");
      stmts[1].execute("SET yb_transaction_priority_upper_bound = 0.1");
    }

    Pair<Integer, Integer> runWithConflict(String query1, String query2) throws SQLException {
      return run(query1, query2, true);
    }

    Pair<Integer, Integer> runWithoutConflict(String query1, String query2) throws SQLException {
      return run(query1, query2, false);
    }

    private Pair<Integer, Integer> run(
      String query1, String query2, boolean conflict_expected) throws SQLException {
      runOnBoth("BEGIN");
      stmts[0].execute(query1);
      int first = stmts[0].getUpdateCount();
      int second = 0;
      if (conflict_expected) {
        runInvalidQuery(stmts[1], query2, "Conflicts with higher priority transaction");
        stmts[0].execute("COMMIT");
        stmts[1].execute("ROLLBACK");
      } else {
        stmts[1].execute(query2);
        second = stmts[1].getUpdateCount();
        runOnBoth("COMMIT");
      }
      return new Pair<>(first, second);
    }

    private void runOnBoth(String query) throws SQLException {
      for (Statement s : stmts) {
        s.execute(query);
      }
    }
  }

  private static class QueryBuilder {
    private String table;

    public QueryBuilder(String table) {
      this.table = table;
    }

    String insert(String values) {
      return String.format("INSERT INTO %s VALUES %s", table, values);
    }

    String delete(String where) {
      return String.format("DELETE FROM %s WHERE %s", table, where);
    }

    String select(String where) {
      return String.format("SELECT * FROM %s WHERE %s FOR KEY SHARE OF %1$s", table, where);
    }
  };

  private void runRangeKeyLocksTest(ParallelQueryRunner runner) throws SQLException {
    QueryBuilder builder = new QueryBuilder("table_with_hash");
    // SELECT must lock (h: 1)
    runner.runWithConflict(
      builder.select("h = 1"),
      builder.delete("h = 1 AND r1 = 10 AND r2 = 100"));
    // SELECT must lock (h: 1, r1: 10, r2: 100)
    runner.runWithConflict(
      builder.select("h = 1 AND r1 = 10 AND r2 = 100"),
      builder.delete("h = 1"));
    // SELECT must lock (h: 1, r1: 10)
    runner.runWithConflict(
      builder.select("h = 1 AND r1 = 10"),
      builder.delete("h = 1 AND r1 = 10 AND r2 = 102"));
    // SELECT must lock (h:1) as r1 value is ambiguous
    runner.runWithConflict(
      builder.select("h = 1 AND r1 IN (10, 11) AND r2 = 102"),
      builder.delete("h = 1 AND r1 = 12 AND r2 = 102"));
    // SELECT must lock (h:1, r1: 11)
    runner.runWithConflict(
      builder.select("h = 1 AND r1 = 11 AND r2 IN (98, 99)"),
      builder.delete("h = 1 AND r1 = 11 AND r2 = 102"));
    // SELECT must lock (h:1)
    runner.runWithConflict(
      builder.select("h = 1 AND r2 = 102"),
      builder.delete("h = 1 AND r1 = 11 AND r2 = 101"));
    // SELECT must lock () i.e. whole tablet
    runner.runWithConflict(
      builder.select("r1 = 11"),
      builder.delete("h = 2 AND r1 = 21 AND r2 = 201"));
    // SELECT must lock () i.e. whole tablet
    runner.runWithConflict(
      builder.select("r2 = 102"),
      builder.delete("h = 2 AND r1 = 21 AND r2 = 201"));
    // SELECT must lock (h:1, r1:10, r2:100)
    runner.runWithConflict(
      builder.select("h = 1 AND r1 = 10 AND r2 = 100"),
      builder.delete("h = 1 AND r1 = 10 AND r2 = 100"));

    // SELECT must lock (h: 1)
    runner.runWithoutConflict(
      builder.select("h = 1"),
      builder.delete("h = 2 AND r1 = 20 AND r2 = 200"));
    // SELECT must lock (h: 1, r1: 10)
    runner.runWithoutConflict(
      builder.select("h = 1 AND r1 = 10"),
      builder.delete("h = 1 AND r1 = 11 AND r2 = 101"));
    // SELECT must lock (h:1)
    runner.runWithoutConflict(
      builder.select("h = 1 AND r1 in (10, 11) AND r2 = 102"),
      builder.delete("h = 2 AND r1 = 21 AND r2 = 201"));
    // SELECT must lock (h:1, r1: 11)
    runner.runWithoutConflict(
      builder.select("h = 1 AND r1 = 11 AND r2 in (100, 101)"),
      builder.delete("h = 1 AND r1 = 10 AND r2 = 100"));
    // SELECT must lock (h:1)
    runner.runWithoutConflict(
      builder.select("h = 1 AND r2 = 102"),
      builder.delete("h = 2 AND r1 = 22 AND r2 = 202"));
    // SELECT must lock (h:1, r1:10, r2:100)
    runner.runWithoutConflict(
      builder.select("h = 1 AND r1 = 10 AND r2 = 100"),
      builder.delete("h = 1 AND r1 = 10 AND r2 = 1000"));

    builder = new QueryBuilder("table_without_hash");
    // SELECT must lock () i.e. whole tablet
    runner.runWithConflict(
      builder.select("r2 = 11"),
      builder.delete("r1 = 2 AND r2 = 22"));
    // SELECT must lock () i.e. whole tablet
    runner.runWithConflict(
      builder.select("r1 = 1 OR r2 = 11"),
      builder.delete("r1 = 2 AND r2 = 22"));
    // SELECT must lock (r1:1)
    runner.runWithConflict(
      builder.select("r1 = 1"),
      builder.delete("r1 = 1 AND r2 = 10"));
    // SELECT must lock (r1:1, r2:10)
    runner.runWithConflict(
      builder.select("r2 = 10 AND r1 IN (1)"),
      builder.delete("r1 = 1 AND r2 = 10"));

    // SELECT must lock (r1:1)
    runner.runWithoutConflict(
      builder.select("r1 = 1"),
      builder.delete("r1 = 2 AND r2 = 20"));
    // SELECT must lock (r1:1, r2:10)
    runner.runWithoutConflict(
      builder.select("r2 = 10 AND r1 IN (1)"),
      builder.delete("r1 = 1 AND r2 = 11"));
  }

  private void runFKLocksTest(ParallelQueryRunner runner) throws SQLException {
    QueryBuilder childBuilder = new QueryBuilder("child_with_hash");
    QueryBuilder parentBuilder = new QueryBuilder("parent_with_hash");
    runner.runWithConflict(
      childBuilder.insert("(1, 1, 10, 100)"),
      parentBuilder.delete("h = 1"));
    runner.runWithConflict(
      childBuilder.insert("(2, 1, 10, 100)"),
      parentBuilder.delete("h = 1 AND r1 = 10"));
    runner.runWithConflict(
      childBuilder.insert("(3, 1, 10, 100)"),
      parentBuilder.delete("h = 1 AND r1 = 10 AND r2 = 100"));

    runner.runWithoutConflict(
      childBuilder.insert("(4, 1, 10, 100)"),
      parentBuilder.delete("h = 2"));
    runner.runWithoutConflict(
      childBuilder.insert("(5, 1, 10, 100)"),
      parentBuilder.delete("h = 1 AND r1 = 11"));
    runner.runWithoutConflict(
      childBuilder.insert("(6, 1, 10, 100)"),
      parentBuilder.delete("h = 1 AND r1 = 10 AND r2 = 1000"));
    Pair<Integer, Integer> result = runner.runWithoutConflict(
      parentBuilder.delete("h = 3 AND r1 = 1 AND r2 = 10"),
      parentBuilder.delete("h = 3 AND r1 = 1 AND r2 = 20"));
    LOG.info("RESULT " + result.getFirst() + ", " + result.getSecond());
    assertEquals(new Pair<>(1, 1), result);

    childBuilder = new QueryBuilder("child_without_hash");
    parentBuilder = new QueryBuilder("parent_without_hash");
    runner.runWithoutConflict(
      childBuilder.insert("(3, 1, 10)"),
      parentBuilder.delete("r1 = 2"));
    runner.runWithoutConflict(
      childBuilder.insert("(4, 1, 10)"),
      parentBuilder.delete("r1 = 1 AND r2 = 11"));

    runner.runWithConflict(
      childBuilder.insert("(1, 1, 10)"),
      parentBuilder.delete("r1 = 1"));
    runner.runWithConflict(
      childBuilder.insert("(2, 1, 10)"),
      parentBuilder.delete("r1 = 1 AND r2 = 10"));
    result = runner.runWithoutConflict(
      parentBuilder.delete("r1 = 3 AND r2 = 10"),
      parentBuilder.delete("r1 = 3 AND r2 = 20"));
    assertEquals(new Pair<>(1, 1), result);
  }

  private void testRangeKeyLocks(IsolationLevel pgIsolationLevel) throws Exception {
    ConnectionBuilder builder = getConnectionBuilder().withIsolationLevel(pgIsolationLevel);
    try (Connection conn = builder.connect();
         Statement stmt = conn.createStatement();
         Connection extraConn = builder.connect();
         Statement extraStmt = extraConn.createStatement()) {
      stmt.execute(
        "CREATE TABLE table_with_hash(h INT, r1 INT, r2 INT, PRIMARY KEY(h, r1 ASC, r2 DESC))");
      stmt.execute(
        "CREATE TABLE table_without_hash(r1 INT, r2 INT, PRIMARY KEY(r1 ASC, r2 DESC))");
      stmt.execute("INSERT INTO table_with_hash VALUES " +
        "(1, 10, 100), (1, 10, 1000), (1, 10, 1001), (1, 11, 101), (1, 12, 102), " +
        "(2, 20, 200), (2, 21, 201), (2, 22, 202)");
      stmt.execute("INSERT INTO table_without_hash VALUES " +
        "(1, 10), (1, 11), (1, 12), " +
        "(2, 10), (2, 11), (2, 20), (2, 21), (2, 22)");
      runRangeKeyLocksTest(new ParallelQueryRunner(stmt, extraStmt));
    }
  }

  private void testFKLocks(IsolationLevel pgIsolationLevel) throws Exception {
    ConnectionBuilder builder = getConnectionBuilder().withIsolationLevel(pgIsolationLevel);
    try (Connection conn = builder.connect();
         Statement stmt = conn.createStatement();
         Connection extraConn = builder.connect();
         Statement extraStmt = extraConn.createStatement()) {
      stmt.execute(
        "CREATE TABLE parent_with_hash(h INT, r1 INT, r2 INT, PRIMARY KEY(h, r1 ASC, r2 DESC))");
      stmt.execute(
        "CREATE TABLE parent_without_hash(r1 INT, r2 INT, PRIMARY KEY(r1 ASC, r2 DESC))");

      stmt.execute("INSERT INTO parent_with_hash VALUES " +
        "(1, 10, 100), (1, 10, 1000), (1, 11, 101), (1, 12, 102), " +
        "(2, 20, 200), (3, 1, 10), (3, 1, 20)");
      stmt.execute("INSERT INTO parent_without_hash VALUES " +
        "(1, 10), (1, 11), (1, 12), (2, 20), (3, 10), (3, 20)");

      stmt.execute("CREATE TABLE child_with_hash(" +
        "k INT PRIMARY KEY, pH INT, pR1 INT, pR2 INT, " +
        "FOREIGN KEY(pH, PR1, pR2) REFERENCES parent_with_hash(h, r1, r2))");
      stmt.execute("CREATE TABLE child_without_hash(" +
        "k INT PRIMARY KEY, pR1 INT, pR2 INT, " +
        "FOREIGN KEY(pR1, pR2) REFERENCES parent_without_hash(r1, r2))");

      // Indices are necessary.
      // As after deletion from parent items referenced item is searched in child.
      // In case there are no index full scan is used which creates intent for a whole tablet.
      stmt.execute("CREATE INDEX ON child_with_hash(pH, pR1, pR2)");
      stmt.execute("CREATE INDEX ON child_without_hash(pR1, pR2)");

      runFKLocksTest(new ParallelQueryRunner(stmt, extraStmt));
    }
  }

  private void testLocksIsolationLevel(IsolationLevel pgIsolationLevel) throws Exception {
    testRangeKeyLocks(pgIsolationLevel);
    testFKLocks(pgIsolationLevel);
  }

  @Test
  public void testLocksSerializableIsolation() throws Exception {
    testLocksIsolationLevel(IsolationLevel.SERIALIZABLE);
  }

  @Test
  public void testLocksSnapshotIsolation() throws Exception {
    testLocksIsolationLevel(IsolationLevel.REPEATABLE_READ);
  }
}
