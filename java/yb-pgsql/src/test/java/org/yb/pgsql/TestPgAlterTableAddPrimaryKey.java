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

import static org.yb.AssertionWrappers.*;

import java.io.File;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yb.client.TestUtils;
import org.yb.util.YBTestRunnerNonTsanOnly;

@RunWith(value = YBTestRunnerNonTsanOnly.class)
public class TestPgAlterTableAddPrimaryKey extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestPgAlterTableAddPrimaryKey.class);

  @Test
  public void simplest() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int)");
      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");
    }
  }

  @Test
  public void duplicates() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int, v int)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 1)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 2)");

      // Note:
      // PG error in this case mentions "nopk_pkey", not just "nopk"
      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (id)",
          "duplicate key value violates unique constraint \"nopk\"");

      stmt.executeUpdate("DELETE FROM nopk WHERE v = 2");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1)));
    }
  }

  @Test
  public void nulls() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (NULL)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1)");

      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (id)",
          "column \"id\" contains null values");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1),
          new Row((Object) null)));
    }
  }

  @Test
  public void columnTypes() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int, v1 int[10][20], v2 text)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, '{1,2,3}', 'qwe')");
      stmt.executeUpdate("INSERT INTO nopk VALUES (2, '{3,4}',   'zxcv')");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, new Integer[] { 1, 2, 3 }, "qwe"),
          new Row(2, new Integer[] { 3, 4 }, "zxcv")));
    }
  }

  @Test
  public void columnTypesUnsupported() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TYPE typeid AS (i int)");
      stmt.executeUpdate("CREATE TABLE nopk (id typeid, v int)");

      String msg = "PRIMARY KEY containing column of type 'user_defined_type' not yet supported";

      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (id)", msg);
      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (id HASH, v)", msg);
      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (v HASH, id)", msg);
    }
  }

  @Test
  public void missing() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int)");

      String msg = "column \"missme\" named in key does not exist";

      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (missme)", msg);
      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (id HASH, missme)", msg);
      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (missme HASH, id)", msg);
    }
  }

  @Test
  public void complexPk() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (v1 int, v2 text, v3 char, v4 boolean)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, '111', '1', true)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (2, '222', '2', false)");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY ((v1, v2) HASH, v3 ASC, v4 DESC)");

      stmt.executeUpdate("INSERT INTO nopk VALUES (2, '222', '3', true)");

      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, '222', '2', false)",
          "duplicate key value violates unique constraint \"nopk_pkey\"");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY v1, v2, v3, v4", Arrays.asList(
          new Row(1, "111", "1", true),
          new Row(2, "222", "2", false),
          new Row(2, "222", "3", true)));
    }
  }

  @Test
  public void pkInclude() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int, v1 int, v2 int)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 11, 111)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (2, 22, 222)");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id) INCLUDE (v1, v2)");

      // Scan is supposed to be index-only scan, but it's index scan for us.
      {
        String includeQuery = "SELECT v1 FROM nopk WHERE id = 2";
        assertTrue(isIndexScan(stmt, includeQuery, "nopk_pkey"));
        assertQuery(stmt, includeQuery, new Row(22));
      }

      stmt.executeUpdate("INSERT INTO nopk VALUES (3, 11, 111)");

      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (3, 99, 999)",
          "duplicate key value violates unique constraint \"nopk_pkey\"");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 11, 111),
          new Row(2, 22, 222),
          new Row(3, 11, 111)));

      {
        String includeQuery = "SELECT v1 FROM nopk WHERE id = 3";
        assertTrue(isIndexScan(stmt, includeQuery, "nopk_pkey"));
        assertQuery(stmt, includeQuery, new Row(11));
      }
    }
  }

  @Test
  public void pkUsingIndex() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int)");
      stmt.executeUpdate("CREATE UNIQUE INDEX nopk_idx ON nopk (id ASC)");

      runInvalidQuery(stmt, "ALTER TABLE nopk ADD CONSTRAINT nopk_pkey PRIMARY KEY"
          + " USING INDEX nopk_idx",
          "ALTER TABLE / ADD CONSTRAINT PRIMARY KEY USING INDEX is not supported");
    }
  }

  @Test
  public void sequences() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk ("
          + "id serial,"
          + "v1 int GENERATED ALWAYS AS IDENTITY,"
          + "v2 int GENERATED BY DEFAULT AS IDENTITY (MINVALUE 10),"
          + "stuff text)");
      stmt.executeUpdate("INSERT INTO nopk (stuff) VALUES ('r1')");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 10, "r1")));

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      stmt.executeUpdate("INSERT INTO nopk (stuff) VALUES ('r2')");
      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 10, "r1"),
          new Row(2, 2, 11, "r2")));
    }
  }

  @Test
  public void typedTable() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TYPE nopk_type AS (id int, v int)");
      stmt.executeUpdate("CREATE TABLE nopk OF nopk_type");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 10)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (2, 20)");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 10),
          new Row(2, 20)));

      runInvalidQuery(stmt, "ALTER TABLE nopk DROP COLUMN v",
          "cannot drop column from typed table");
    }
  }

  @Ignore // TODO(alex): Enable after INHERITS is supported in #1129
  public void inheritedTable() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk_parent (id int)");
      stmt.executeUpdate("INSERT INTO nopk_parent VALUES (1)");
      stmt.executeUpdate("INSERT INTO nopk_parent VALUES (2)");

      stmt.executeUpdate("CREATE TABLE nopk_child (v int) INHERITS (nopk_parent);");
      stmt.executeUpdate("INSERT INTO nopk_child VALUES (3, 30)");
      stmt.executeUpdate("INSERT INTO nopk_child VALUES (4, 40)");

      alterAddPrimaryKey(stmt, "nopk_parent", "ADD PRIMARY KEY (id)");
      alterAddPrimaryKey(stmt, "nopk_child", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk_parent ORDER BY id", Arrays.asList(
          new Row(1),
          new Row(2)));
      assertRowList(stmt, "SELECT * FROM nopk_child ORDER BY id", Arrays.asList(
          new Row(3, 30),
          new Row(4, 40)));
    }
  }

  /** Adding PK to a partitioned table is not yet implemented. */
  @Test
  public void partitionedTable() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk_whole (id int) PARTITION BY LIST (id)");
      stmt.executeUpdate("CREATE TABLE nopk_part1 PARTITION OF nopk_whole"
          + " FOR VALUES IN (1, 2, 3)");
      stmt.executeUpdate("CREATE TABLE nopk_part2 PARTITION OF nopk_whole"
          + " FOR VALUES IN (10, 20, 30, 40) PARTITION BY LIST (id)");
      stmt.executeUpdate("CREATE TABLE nopk_part2_part1 PARTITION OF nopk_part2"
          + " FOR VALUES IN (10, 20)");
      stmt.executeUpdate("CREATE TABLE nopk_part2_part2 PARTITION OF nopk_part2"
          + " FOR VALUES IN (30, 40)");

      runInvalidQuery(stmt, "ALTER TABLE nopk_whole ADD PRIMARY KEY (id)",
          "adding primary key to a partitioned table is not yet implemented");
      runInvalidQuery(stmt, "ALTER TABLE nopk_part1 ADD PRIMARY KEY (id)",
          "adding primary key to a partitioned table is not yet implemented");
      runInvalidQuery(stmt, "ALTER TABLE nopk_part2 ADD PRIMARY KEY (id)",
          "adding primary key to a partitioned table is not yet implemented");
      runInvalidQuery(stmt, "ALTER TABLE nopk_part2_part1 ADD PRIMARY KEY (id)",
          "adding primary key to a partitioned table is not yet implemented");
      runInvalidQuery(stmt, "ALTER TABLE nopk_part2_part2 ADD PRIMARY KEY (id)",
          "adding primary key to a partitioned table is not yet implemented");
    }
  }

  @Test
  public void tablesInColocatedDb() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE DATABASE clc WITH colocated = true");
    }

    try (Connection conn2 = getConnectionBuilder().withDatabase("clc").connect();
        Statement stmt = conn2.createStatement()) {
      stmt.executeUpdate("CREATE TABLE normal_table (id int PRIMARY KEY)");
      stmt.executeUpdate("INSERT INTO normal_table VALUES (1)");
      stmt.executeUpdate("INSERT INTO normal_table VALUES (2)");

      stmt.executeUpdate("CREATE TABLE nopk_c (id int)");
      stmt.executeUpdate("INSERT INTO nopk_c VALUES (3)");
      stmt.executeUpdate("INSERT INTO nopk_c VALUES (4)");

      stmt.executeUpdate("CREATE TABLE nopk_nc (id int) WITH (colocated = false)");
      stmt.executeUpdate("INSERT INTO nopk_nc VALUES (5)");
      stmt.executeUpdate("INSERT INTO nopk_nc VALUES (6)");

      assertEquals(1, getNumTablets("clc", "normal_table"));
      assertEquals(1, getNumTablets("clc", "nopk_c"));
      assertEquals(NUM_TABLET_SERVERS, getNumTablets("clc", "nopk_nc"));

      // This doesn't really accomplish much though, since colocated property is invisible to SQL
      // - we can't check whether a re-created table keeps/gains/loses it.
      // See #6159
      alterAddPrimaryKey(stmt, "nopk_c", "ADD PRIMARY KEY (id)");
      alterAddPrimaryKey(stmt, "nopk_nc", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM normal_table ORDER BY id", Arrays.asList(
          new Row(1),
          new Row(2)));
      assertRowList(stmt, "SELECT * FROM nopk_c ORDER BY id", Arrays.asList(
          new Row(3),
          new Row(4)));
      assertRowList(stmt, "SELECT * FROM nopk_nc ORDER BY id", Arrays.asList(
          new Row(5),
          new Row(6)));
      assertEquals(1, getNumTablets("clc", "normal_table"));
      assertEquals(1, getNumTablets("clc", "nopk_c"));
      assertEquals(NUM_TABLET_SERVERS, getNumTablets("clc", "nopk_nc"));
    }
  }

  @Test
  public void tablesInTablegroup() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLEGROUP tgroup1");

      stmt.executeUpdate("CREATE TABLE normal_table (id int PRIMARY KEY)"
          + " TABLEGROUP tgroup1");
      stmt.executeUpdate("INSERT INTO normal_table VALUES (1)");
      stmt.executeUpdate("INSERT INTO normal_table VALUES (2)");

      stmt.executeUpdate("CREATE TABLE nopk (id int)"
          + " TABLEGROUP tgroup1");
      stmt.executeUpdate("INSERT INTO nopk VALUES (3)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (4)");

      stmt.executeUpdate("CREATE TABLE nopk2 (id int, id2 int unique)"
          + " TABLEGROUP tgroup1");
      stmt.executeUpdate("INSERT INTO nopk2 VALUES (5, 5)");
      stmt.executeUpdate("INSERT INTO nopk2 VALUES (6, 6)");

      assertEquals(1, getNumTablets(DEFAULT_PG_DATABASE, "normal_table"));
      assertEquals(1, getNumTablets(DEFAULT_PG_DATABASE, "nopk"));
      assertEquals(1, getNumTablets(DEFAULT_PG_DATABASE, "nopk2"));
      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");
      alterAddPrimaryKey(stmt, "nopk2", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM normal_table ORDER BY id", Arrays.asList(
          new Row(1),
          new Row(2)));
      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(3),
          new Row(4)));
      assertRowList(stmt, "SELECT * FROM nopk2 ORDER BY id", Arrays.asList(
          new Row(5, 5),
          new Row(6, 6)));

      assertRowList(stmt,
        "SELECT s.relname, pg_tablegroup.grpname " +
        "FROM (SELECT relname, unnest(reloptions) AS opts FROM pg_class) " +
        "s, pg_tablegroup WHERE opts LIKE " +
        "CONCAT('%tablegroup=', CAST(pg_tablegroup.oid AS text), '%') " +
        "ORDER BY s", Arrays.asList(
          new Row("nopk", "tgroup1"),
          new Row("nopk2", "tgroup1"),
          new Row("nopk2_id2_key", "tgroup1"),
          new Row("nopk2_pkey", "tgroup1"),
          new Row("nopk_pkey", "tgroup1"),
          new Row("normal_table", "tgroup1"),
          new Row("normal_table_pkey", "tgroup1")));
      assertEquals(1, getNumTablets(DEFAULT_PG_DATABASE, "normal_table"));
      assertEquals(1, getNumTablets(DEFAULT_PG_DATABASE, "nopk"));
      assertEquals(1, getNumTablets(DEFAULT_PG_DATABASE, "nopk2"));
    }
  }

  @Test
  public void defaults() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk ("
          + " id int DEFAULT 10, "
          + " drop_me int DEFAULT 10, "
          + " v int DEFAULT 10"
          + ")");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 1, 1)");
      stmt.executeUpdate("ALTER TABLE nopk DROP COLUMN drop_me");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1)));

      stmt.executeUpdate("INSERT INTO nopk (id) VALUES (2)");
      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1),
          new Row(2, 10)));

      stmt.executeUpdate("INSERT INTO nopk (v) VALUES (2)");
      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1),
          new Row(2, 10),
          new Row(10, 2)));
    }
  }

  @Test
  public void notNullAndCheck() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk ("
          + " id int CHECK (id > 0),"
          + " drop_me int,"
          + " v1 int CHECK (v1 > 0),"
          + " v2 int NOT NULL"
          + ")");
      stmt.executeUpdate("ALTER TABLE nopk DROP COLUMN drop_me");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 1, 1)");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 1)));

      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (0, 2, 2)",
          "violates check constraint \"nopk_id_check\"");
      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, 0, 2)",
          "violates check constraint \"nopk_v1_check\"");
      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, 2, NULL)",
          "violates not-null constraint");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 1)));
    }
  }

  /** Altered table references a FK table. */
  @Test
  public void foreignKeys() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE fk_ref_table (id int PRIMARY KEY, v int UNIQUE)");
      stmt.executeUpdate("CREATE UNIQUE INDEX ON fk_ref_table (v, id)");
      stmt.executeUpdate("INSERT INTO fk_ref_table VALUES (1, 1)");
      stmt.executeUpdate("INSERT INTO fk_ref_table VALUES (2, 2)");

      stmt.executeUpdate("CREATE TABLE nopk ("
          + " id int,"
          + " fk1 int REFERENCES fk_ref_table (id),"
          + " drop_me int,"
          + " fk2 int REFERENCES fk_ref_table (v),"
          + " fk3 int)");
      stmt.executeUpdate("ALTER TABLE nopk ADD FOREIGN KEY (fk2, fk3)"
          + " REFERENCES fk_ref_table (v, id)");
      stmt.executeUpdate("ALTER TABLE nopk DROP COLUMN drop_me");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 1, 1, 1)");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 1, 1)));

      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, 20, 2, 2)",
          "violates foreign key constraint \"nopk_fk1_fkey\"");
      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, 2, 20, 2)",
          "violates foreign key constraint \"nopk_fk2_fkey\"");
      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, 2, 2, 20)",
          "violates foreign key constraint \"nopk_fk2_fkey1\"");
      stmt.executeUpdate("INSERT INTO nopk VALUES (2, 2, 2, 2)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 1, 1),
          new Row(2, 2, 2, 2)));

      runInvalidQuery(stmt, "DELETE FROM fk_ref_table WHERE id = 1",
          "violates foreign key constraint \"nopk_fk1_fkey\" on table \"nopk\"");
    }
  }

  /** Altered table itself is referenced through FK constraints from other table. */
  @Test
  public void foreignKeys2() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int UNIQUE, drop_me int, v int)");
      stmt.executeUpdate("CREATE UNIQUE INDEX ON nopk (v)");
      stmt.executeUpdate("CREATE UNIQUE INDEX ON nopk (v, id)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 1, 1)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (2, 2, 2)");
      stmt.executeUpdate("ALTER TABLE nopk DROP COLUMN drop_me");

      stmt.executeUpdate("CREATE TABLE referencing_table ("
          + " id int PRIMARY KEY,"
          + " fk1 int REFERENCES nopk (id),"
          + " drop_me int,"
          + " fk2 int REFERENCES nopk (v),"
          + " fk3 int)");
      stmt.executeUpdate("ALTER TABLE referencing_table ADD FOREIGN KEY (fk2, fk3)"
          + " REFERENCES nopk (v, id)");
      stmt.executeUpdate("INSERT INTO referencing_table VALUES (1, 1, 1, 1, 1)");
      stmt.executeUpdate("ALTER TABLE referencing_table DROP COLUMN drop_me");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1),
          new Row(2, 2)));
      assertRowList(stmt, "SELECT * FROM referencing_table ORDER BY id", Arrays.asList(
          new Row(1, 1, 1, 1)));

      runInvalidQuery(stmt, "INSERT INTO referencing_table VALUES (2, 20, 2, 2)",
          "violates foreign key constraint \"referencing_table_fk1_fkey\"");
      runInvalidQuery(stmt, "INSERT INTO referencing_table VALUES (2, 2, 20, 2)",
          "violates foreign key constraint \"referencing_table_fk2_fkey\"");
      runInvalidQuery(stmt, "INSERT INTO referencing_table VALUES (2, 2, 2, 20)",
          "violates foreign key constraint \"referencing_table_fk2_fkey1\"");
      stmt.executeUpdate("INSERT INTO referencing_table VALUES (2, 2, 2, 2)");

      assertRowList(stmt, "SELECT * FROM referencing_table ORDER BY id", Arrays.asList(
          new Row(1, 1, 1, 1),
          new Row(2, 2, 2, 2)));

      runInvalidQuery(stmt, "DELETE FROM nopk WHERE id = 1",
          "violates foreign key constraint \"referencing_table_fk1_fkey\""
              + " on table \"referencing_table\"");
    }
  }

  @Test
  public void otherConstraintsAndIndexes() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      // TODO(alex): Add EXCLUDE constraint after #997, in the meantime just make sure it's NYI.
      runInvalidQuery(stmt, "CREATE TABLE fail (c circle, EXCLUDE USING gist (c WITH &&))",
          "EXCLUDE constraint not supported yet");

      stmt.executeUpdate("CREATE TABLE nopk ("
          + " id int,"
          + " v1 int UNIQUE,"
          + " drop_me int,"
          + " v2 int,"
          + " v3 int,"
          + " v4 int"
          + ")");
      stmt.executeUpdate("CREATE UNIQUE INDEX ON nopk (v2)");
      stmt.executeUpdate("CREATE INDEX ON nopk (v3)");
      stmt.executeUpdate("CREATE INDEX ON nopk ((v4 * 2))");
      stmt.executeUpdate("CREATE INDEX ON nopk ((v2, v3) HASH, v4 DESC NULLS LAST)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 1, 1, 1, 1, 1)");
      stmt.executeUpdate("ALTER TABLE nopk DROP COLUMN drop_me");

      String v3query = "SELECT v3 FROM nopk WHERE v3 = 1";
      String v4query = "SELECT v4 FROM nopk WHERE v4 * 2 = 2";
      assertTrue(isIndexOnlyScan(stmt, v3query, "nopk_v3_idx"));
      assertTrue(isIndexScan(stmt, v4query, "nopk_expr_idx"));

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 1, 1, 1)));

      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, 1, 2, 2, 2)",
          "violates unique constraint \"nopk_v1_key\"");

      runInvalidQuery(stmt, "INSERT INTO nopk VALUES (2, 2, 1, 2, 2)",
          "violates unique constraint \"nopk_v2_idx\"");

      stmt.executeUpdate("INSERT INTO nopk VALUES (2, 2, 2, 1, 2)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1, 1, 1, 1),
          new Row(2, 2, 2, 1, 2)));

      assertTrue(isIndexOnlyScan(stmt, v3query, "nopk_v3_idx"));
      assertRowList(stmt, v3query, Arrays.asList(
          new Row(1),
          new Row(1)));

      assertTrue(isIndexScan(stmt, v4query, "nopk_expr_idx"));
      assertRowList(stmt, v4query, Arrays.asList(
          new Row(1)));
    }
  }

  @Test
  public void triggers() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int, drop_me int, v int)");
      stmt.executeUpdate("INSERT INTO nopk VALUES (1, 1, 1)");
      stmt.executeUpdate(
          "CREATE FUNCTION notice_on_trigger() RETURNS trigger"
              + " LANGUAGE plpgsql"
              + " AS $$"
              + "   BEGIN RAISE NOTICE 'Trigger called: %', TG_NAME;"
              + "   RETURN NEW;"
              + "   END;"
              + " $$;");
      for (String timing : Arrays.asList("before", "after")) {
        for (String scope : Arrays.asList("statement", "row")) {
          for (String action : Arrays.asList("insert", "update", "delete")) {
            stmt.executeUpdate(MessageFormat.format(
                "CREATE TRIGGER nopk__{0}_{2}_{1}"
                    + " {0} {2} ON nopk"
                    + " FOR EACH {1}"
                    + " EXECUTE PROCEDURE notice_on_trigger()",
                timing, scope, action));
          }
          String whenExpr = scope.equals("row")
              ? "OLD.id > 0 AND NEW.id > 0 AND OLD.v > 0 AND NEW.v > 0"
              : "RANDOM() >= 0";
          stmt.executeUpdate(MessageFormat.format(
              "CREATE TRIGGER nopk__{0}_update_c_{1}"
                  + " {0} UPDATE OF id, v ON nopk"
                  + " FOR EACH {1}"
                  + " WHEN (" + whenExpr + ")"
                  + " EXECUTE PROCEDURE notice_on_trigger()",
              timing, scope));
        }
      }
      stmt.executeUpdate("ALTER TABLE nopk DROP COLUMN drop_me");

      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");

      assertRowList(stmt, "SELECT * FROM nopk ORDER BY id", Arrays.asList(
          new Row(1, 1)));
    }
  }

  @Test
  public void secondPk() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int PRIMARY KEY)");
      runInvalidQuery(stmt, "ALTER TABLE nopk ADD PRIMARY KEY (id)",
          "multiple primary keys for table \"nopk\" are not allowed");
    }
  }

  @Test
  public void splitInto() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int) SPLIT INTO 2 TABLETS");
      assertEquals(2, getNumTablets(DEFAULT_PG_DATABASE, "nopk"));
      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");
      assertEquals(2, getNumTablets(DEFAULT_PG_DATABASE, "nopk"));
    }
  }

  @Test
  public void roles() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE nopk (id int)");
      stmt.executeUpdate("CREATE ROLE new_user");
      stmt.executeUpdate("ALTER TABLE nopk OWNER TO new_user");
      assertQuery(stmt, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'nopk'",
          new Row("new_user"));
      alterAddPrimaryKey(stmt, "nopk", "ADD PRIMARY KEY (id)");
      assertQuery(stmt, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'nopk'",
          new Row("new_user"));
    }
  }

  /**
   * Test to verify basic compatibility with pg_dump output (we don't check pg_X tables content).
   * <p>
   * Source was a real output from pg_dump in its default configuration.
   */
  @Test
  public void restorePgDump() throws Exception {
    URL sqlFileRes = getClass().getClassLoader()
        .getResource("TestPgAlterTableAddPrimaryKey/restorePgDump.sql");
    assertTrue("Dump SQL resource not found!", sqlFileRes != null);
    File sqlFile = new File(sqlFileRes.getFile());

    String ysqlshPath = new File(PgRegressRunner.getPgBinDir(), "ysqlsh").getAbsolutePath();
    ProcessBuilder procBuilder = new ProcessBuilder(
        ysqlshPath,
        "-h", getPgHost(0),
        "-p", Integer.toString(getPgPort(0)),
        DEFAULT_PG_DATABASE);

    procBuilder.redirectInput(sqlFile);

    List<String> output = runProcess(procBuilder);

    // Sanity checks.
    assertTrue(output.size() > 0);
    assertFalse("There was an error executing SQL: " + output,
        StringUtils.join(output, '\n').toLowerCase().contains("error"));

    try (Statement stmt = connection.createStatement()) {
      assertRowList(stmt, "SELECT * FROM public.with_constraints_and_such ORDER BY id",
          Arrays.asList(
              new Row(1, new Integer[] { 1, 2 }, 10, 123, 321, 111),
              new Row(2, new Integer[] { 2, 3 }, 20, 234, 432, 222),
              new Row(3, new Integer[] { 3, 4 }, 30, 345, 543, 333)));
    }
  }

  //
  // Helpers
  //

  /**
   * Execute ALTER TABLE with the given alter spec, ensuring everything was migrated properly.
   */
  private void alterAddPrimaryKey(
      Statement stmt,
      String tableName,
      String alterSpec) throws Exception {
    String countPgClass = "SELECT COUNT(*) FROM pg_class";
    String getTableNames = "SELECT table_name FROM information_schema.tables"
        + " WHERE table_schema = 'public' ORDER BY table_name";
    String getOid = "SELECT oid FROM pg_class WHERE relname = '" + tableName + "'";
    // This query is based on pg_dump.c query, with some columns removed.
    // We're also selecting owning_tab_name instead of owning_tab OID.
    String getSequences = "SELECT c.oid, c.relname, "
        + "     c.relkind, c.relnamespace, "
        + "     (SELECT rolname FROM pg_catalog.pg_roles WHERE oid = c.relowner) AS rolname, "
        + "     c.relchecks, c.relhastriggers, "
        + "     c.relhasindex, c.relhasrules, c.relhasoids, "
        + "     c.relrowsecurity, c.relforcerowsecurity, "
        + "     c.relfrozenxid, c.relminmxid, tc.oid AS toid, "
        + "     tc.relfrozenxid AS tfrozenxid, "
        + "     tc.relminmxid AS tminmxid, "
        + "     c.relpersistence, c.relispopulated, "
        + "     c.relreplident, c.relpages, "
        + "     CASE WHEN c.reloftype <> 0"
        + "       THEN c.reloftype::pg_catalog.regtype ELSE NULL END"
        + "     AS reloftype, "
        + "     (SELECT c2.relname FROM pg_class c2 WHERE c2.oid = d.refobjid) AS owning_tab_name, "
        + "     d.refobjsubid AS owning_col, "
        + "     (SELECT spcname FROM pg_tablespace t WHERE t.oid = c.reltablespace)"
        + "       AS reltablespace, "
        + "     array_remove(array_remove(c.reloptions,'check_option=local'),"
        + "                  'check_option=cascaded')"
        + "     AS reloptions, "
        + "     CASE"
        + "       WHEN 'check_option=local' = ANY (c.reloptions)"
        + "         THEN 'LOCAL'::text "
        + "       WHEN 'check_option=cascaded' = ANY (c.reloptions)"
        + "         THEN 'CASCADED'::text ELSE NULL END"
        + "     AS checkoption, "
        + "     tc.reloptions AS toast_reloptions, "
        + "     c.relkind = 'S' AND EXISTS ("
        + "       SELECT 1 FROM pg_depend"
        + "       WHERE classid = 'pg_class'::regclass"
        + "         AND objid = c.oid"
        + "         AND objsubid = 0"
        + "         AND refclassid = 'pg_class'::regclass"
        + "         AND deptype = 'i'"
        + "     ) AS is_identity_sequence"
        + " FROM pg_class c "
        + " LEFT JOIN pg_depend d"
        + "   ON (c.relkind = 'S' AND d.classid = c.tableoid AND"
        + "     d.objid = c.oid AND d.objsubid = 0 AND "
        + "     d.refclassid = c.tableoid AND d.deptype IN ('a', 'i')) "
        + " LEFT JOIN pg_class tc ON (c.reltoastrelid = tc.oid) "
        + " LEFT JOIN pg_init_privs pip"
        + "   ON (c.oid = pip.objoid AND"
        + "     pip.classoid = 'pg_class'::regclass AND"
        + "     pip.objsubid = 0) "
        + " WHERE c.relkind in ('S') "
        + " ORDER BY c.oid";

    // Saving stuff to verify after rename.

    long oldOid = getSingleRow(stmt.executeQuery(getOid)).getLong(0);
    long oldPgClassSize = getSingleRow(stmt.executeQuery(countPgClass)).getLong(0);

    List<Row> oldTableNames = getRowList(stmt.executeQuery(getTableNames));
    List<Row> oldSequences = getRowList(stmt.executeQuery(getSequences));

    PgSystemTableInfo oldState = new PgSystemTableInfo(stmt.getConnection(), oldOid);

    stmt.executeUpdate("ALTER TABLE " + tableName + " " + alterSpec);

    // OID has changed, but the pg_class row content did not.
    long newOid = getSingleRow(stmt.executeQuery(getOid)).getLong(0);
    assertNotEquals(oldOid, newOid);

    // Stuff targeting old OID should match the stuff targeting the new one.

    // There's one more index in the pool now.
    assertQuery(stmt, countPgClass, new Row(oldPgClassSize + 1));

    // Everything else should remain as it was.
    assertRowList(stmt, getTableNames, oldTableNames);
    assertRowList(stmt, getSequences, oldSequences);

    PgSystemTableInfo newState = new PgSystemTableInfo(stmt.getConnection(), newOid);

    assertPgStateEquals(oldState, newState);
  }

  private List<Row> execCheckQuery(PreparedStatement ps, long oid) throws Exception {
    ps.setLong(1, oid);
    return getRowList(ps.executeQuery());
  }

  private void assertPgStateEquals(PgSystemTableInfo oldState, PgSystemTableInfo newState) {
    assertRow(oldState.pgClassRow, newState.pgClassRow);
    assertRows(oldState.attrs, newState.attrs);
    assertRows(oldState.defaults, newState.defaults);
    assertRows(oldState.checkConstrs, newState.checkConstrs);
    assertRows(oldState.indexes, newState.indexes);
    assertRows(oldState.foreignKeys, newState.foreignKeys);
    assertRows(oldState.triggers, newState.triggers);
  }

  private int getNumTablets(String databaseName, String tableName) throws Exception {
    List<String> lines = runProcess(
        TestUtils.findBinary("yb-admin"),
        "--master_addresses",
        masterAddresses,
        "list_tablets",
        "ysql." + databaseName,
        tableName);
    // We don't care about the output, just number of lines (minus header line).
    return lines.size() - 1;
  }

  private class PgSystemTableInfo {
    Row pgClassRow;
    List<Row> attrs;
    List<Row> defaults;
    List<Row> checkConstrs;
    List<Row> indexes;
    List<Row> foreignKeys;
    List<Row> triggers;

    public PgSystemTableInfo(Connection conn, long oid) throws Exception {
      // Columns not selected: reltype, relhasindex, relfilenode, relpartbound,
      // relnatts (because it includes dropped attributes).
      PreparedStatement getPgClassRow = conn.prepareStatement(
          "SELECT relname, relnamespace, reloftype, relowner, relam, reltablespace, relpages,"
              + "   reltuples, relallvisible, reltoastrelid, relisshared,"
              + "   relpersistence, relkind, relchecks, relhasoids, relhasrules,"
              + "   relhastriggers, relhassubclass, relrowsecurity, relforcerowsecurity,"
              + "   relispopulated, relreplident, relispartition, relrewrite, relfrozenxid,"
              + "   relminmxid, relacl"
              + " FROM pg_class WHERE oid = ?");

      // These queries are taken from pg_dump.c, with the following changes:
      //  * Formatting was changed slightly.
      //  * Attributes query doesn't include attnotnull (they are changed for PK cols)
      //      and dropped columns.
      //  * Index query doesn't include PK index.
      //  * Index query selects column names instead of attnums.
      //  * Attribute-related queries don't include attnum (replaced by ORDER BY).
      //  * Queries don't include tableoids because we don't care.
      //  * Queries don't include OIDs that are expected to change.
      //  * Added ORDER BY to all queries.
      PreparedStatement getAttrs = conn.prepareStatement(
          "SELECT a.attname, a.atttypmod, "
              + "   a.attstattarget, a.attstorage, t.typstorage, "
              + "   a.atthasdef, a.attlen, a.attalign, a.attislocal, "
              + "   pg_catalog.format_type(t.oid,a.atttypmod) AS atttypname, "
              + "   array_to_string(a.attoptions, ', ') AS attoptions, "
              + "   CASE WHEN a.attcollation <> t.typcollation "
              + "     THEN a.attcollation ELSE 0 END AS attcollation, "
              + "   a.attidentity, "
              + "   pg_catalog.array_to_string(ARRAY("
              + "     SELECT pg_catalog.quote_ident(option_name) || "
              + "     ' ' || pg_catalog.quote_literal(option_value) "
              + "     FROM pg_catalog.pg_options_to_table(attfdwoptions) "
              + "     ORDER BY option_name"
              + "   ), E',\n    ') AS attfdwoptions ,"
              + "   CASE WHEN a.atthasmissing AND NOT a.attisdropped "
              + "     THEN a.attmissingval ELSE null END AS attmissingval "
              + " FROM pg_catalog.pg_attribute a LEFT JOIN pg_catalog.pg_type t "
              + "   ON a.atttypid = t.oid "
              + " WHERE a.attrelid = ?::pg_catalog.oid "
              + "   AND a.attnum > 0::pg_catalog.int2 "
              + "   AND NOT a.attisdropped "
              + " ORDER BY a.attnum");
      PreparedStatement getDefaults = conn.prepareStatement(
          "SELECT pg_catalog.pg_get_expr(adbin, adrelid) AS adsrc "
              + " FROM pg_catalog.pg_attrdef "
              + " WHERE adrelid = ?::pg_catalog.oid "
              + " ORDER BY adnum");
      PreparedStatement getCheckConstrs = conn.prepareStatement(
          "SELECT conname, pg_catalog.pg_get_constraintdef(oid) AS consrc,"
              + "   conislocal, convalidated"
              + " FROM pg_catalog.pg_constraint "
              + " WHERE conrelid = ?::pg_catalog.oid AND contype = 'c'"
              + " ORDER BY conname");
      PreparedStatement getIndexes = conn.prepareStatement(
          "SELECT t.relname AS indexname, "
              + "   inh.inhparent AS parentidx, "
              + "   pg_catalog.pg_get_indexdef(i.indexrelid) AS indexdef, "
              + "   i.indnkeyatts AS indnkeyatts, "
              + "   i.indnatts AS indnatts, "
              + "   ARRAY(SELECT a.attname FROM UNNEST(i.indkey) k "
              + "     INNER JOIN pg_attribute a ON a.attrelid = t2.oid AND a.attnum = k) "
              + "     AS indkey_colnames, "
              + "   i.indisclustered, "
              + "   i.indisreplident, i.indoption, t.relpages, "
              + "   c.contype, c.conname, "
              + "   c.condeferrable, c.condeferred, "
              + "   pg_catalog.pg_get_constraintdef(c.oid, false) AS condef, "
              + "   (SELECT spcname FROM pg_catalog.pg_tablespace s WHERE s.oid = t.reltablespace)"
              + "     AS tablespace, "
              + "   t.reloptions AS indreloptions, "
              + "   (SELECT pg_catalog.array_agg(attnum ORDER BY attnum) "
              + "     FROM pg_catalog.pg_attribute "
              + "     WHERE attrelid = i.indexrelid AND "
              + "       attstattarget >= 0) AS indstatcols,"
              + "   (SELECT pg_catalog.array_agg(attstattarget ORDER BY attnum) "
              + "     FROM pg_catalog.pg_attribute "
              + "     WHERE attrelid = i.indexrelid AND "
              + "       attstattarget >= 0) AS indstatvals "
              + " FROM pg_catalog.pg_index i "
              + " JOIN pg_catalog.pg_class t ON (t.oid = i.indexrelid) "
              + " JOIN pg_catalog.pg_class t2 ON (t2.oid = i.indrelid) "
              + " LEFT JOIN pg_catalog.pg_constraint c "
              + "   ON (i.indrelid = c.conrelid AND "
              + "     i.indexrelid = c.conindid AND "
              + "     c.contype IN ('u','x')) "
              + " LEFT JOIN pg_catalog.pg_inherits inh "
              + "   ON (inh.inhrelid = indexrelid) "
              + " WHERE i.indrelid = ?::pg_catalog.oid "
              + "   AND (i.indisvalid OR t2.relkind = 'p') "
              + "   AND i.indisready "
              + "   AND i.indisprimary = false "
              + " ORDER BY indexname");
      PreparedStatement getForeignKeys = conn.prepareStatement(
          "SELECT conname, confrelid, "
              + "   pg_catalog.pg_get_constraintdef(oid) AS condef "
              + " FROM pg_catalog.pg_constraint "
              + " WHERE conrelid = ?::pg_catalog.oid "
              + "  AND conparentid = 0 "
              + "  AND contype = 'f'"
              + " ORDER BY conname");
      PreparedStatement getTriggers = conn.prepareStatement(
          "SELECT tgname, "
              + "   tgfoid::pg_catalog.regproc AS tgfname, "
              + "   pg_catalog.pg_get_triggerdef(oid, false) AS tgdef, "
              + "   ARRAY(SELECT a.attname FROM UNNEST(t.tgattr) k "
              + "     INNER JOIN pg_attribute a ON a.attrelid = t.tgrelid AND a.attnum = k) "
              + "     AS tgattr_colnames, "
              + "   tgenabled "
              + " FROM pg_catalog.pg_trigger t "
              + " WHERE tgrelid = ?::pg_catalog.oid "
              + "   AND NOT tgisinternal"
              + " ORDER BY tgfname");
      List<Row> pgClassRows = execCheckQuery(getPgClassRow, oid);
      assertTrue("Table with OID " + oid + " not found!", pgClassRows.size() > 0);
      this.pgClassRow = pgClassRows.get(0);
      this.attrs = execCheckQuery(getAttrs, oid);
      this.defaults = execCheckQuery(getDefaults, oid);
      this.checkConstrs = execCheckQuery(getCheckConstrs, oid);
      this.indexes = execCheckQuery(getIndexes, oid);
      this.foreignKeys = execCheckQuery(getForeignKeys, oid);
      this.triggers = execCheckQuery(getTriggers, oid);
    }
  }
}
