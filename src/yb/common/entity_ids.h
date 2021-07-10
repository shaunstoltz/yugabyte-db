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

#ifndef YB_COMMON_ENTITY_IDS_H
#define YB_COMMON_ENTITY_IDS_H

#include <string>
#include <set>
#include <utility>

#include "yb/common/entity_ids_types.h"

#include "yb/util/result.h"

namespace yb {

static const uint32_t kPgSequencesDataTableOid = 0xFFFF;
static const uint32_t kPgSequencesDataDatabaseOid = 0xFFFF;

static const uint32_t kPgIndexTableOid = 2610;  // Hardcoded for pg_index. (in pg_index.h)
static const uint32_t kPgClassTableOid = 1259;  // Hardcoded for pg_class. (in pg_class.h)
static const uint32_t kPgDatabaseTableOid = 1262;  // Hardcoded for pg_database. (in pg_database.h)
static const uint32_t kPgFirstNormalObjectId = 16384; // Hardcoded in transam.h

extern const TableId kPgProcTableId;
extern const TableId kPgYbCatalogVersionTableId;
extern const TableId kPgTablespaceTableId;
extern const string kPgSequencesDataNamespaceId;

// Get YB namespace id for a Postgres database.
NamespaceId GetPgsqlNamespaceId(uint32_t database_oid);

// Get YB table id for a Postgres table.
TableId GetPgsqlTableId(uint32_t database_oid, uint32_t table_oid);

// Get YB tablegroup id for a Postgres tablegroup.
TablegroupId GetPgsqlTablegroupId(uint32_t database_oid, uint32_t tablegroup_oid);

// Get YB tablespace id for a Postgres tablespace.
TablespaceId GetPgsqlTablespaceId(uint32_t tablespace_oid);

// Is the namespace/table id a Postgres database or table id?
bool IsPgsqlId(const string& id);

// Get Postgres database and table oids from a YB namespace/table id.
Result<uint32_t> GetPgsqlDatabaseOid(const NamespaceId& namespace_id);
Result<uint32_t> GetPgsqlTableOid(const TableId& table_id);
Result<uint32_t> GetPgsqlTablegroupOid(const TablegroupId& tablegroup_id);
Result<uint32_t> GetPgsqlTablegroupOidByTableId(const TableId& table_id);
Result<uint32_t> GetPgsqlDatabaseOidByTableId(const TableId& table_id);
Result<uint32_t> GetPgsqlTablespaceOid(const TablespaceId& tablespace_id);

}  // namespace yb

#endif  // YB_COMMON_ENTITY_IDS_H
