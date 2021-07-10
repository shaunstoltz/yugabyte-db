---
title: What's new in the v2.6 stable release series
headerTitle: What's new in the v2.6 stable release series
linkTitle: v2.6 (stable)
description: Enhancements, changes, and resolved issues in the current stable release series recommended for production deployments.
headcontent: Features, enhancements, and resolved issues in the current stable release series recommended for production deployments.
aliases:
  - /latest/releases/whats-new/stable-releases/
menu:
  latest:
    identifier: stable-release
    parent: whats-new
    weight: 2586
isTocNested: true
showAsideToc: true
---

Included here are the release notes for all releases in the v2.6 stable release series. Content will be added as new notable features and changes are available in the patch releases of the v2.6 stable release series.

## v2.6.0.0 - July 7, 2021

### Downloads

<a class="download-binary-link" href="https://downloads.yugabyte.com/yugabyte-2.6.0.0-darwin.tar.gz">
  <button>
    <i class="fab fa-apple"></i><span class="download-text">macOS</span>
  </button>
</a>
&nbsp; &nbsp; &nbsp;
<a class="download-binary-link" href="https://downloads.yugabyte.com/yugabyte-2.6.0.0-linux.tar.gz">
  <button>
    <i class="fab fa-linux"></i><span class="download-text">Linux</span>
  </button>
</a>
<br />

### Docker

```sh
docker pull yugabytedb/yugabyte:2.6.0.0-b69
```

### New features

#### Core database

##### Point-in-time recovery

This feature allows you to restore the state of a cluster back to a previous point in time. This release is focused on support for the YCQL API. You can set up PITR at a YCQL keyspace level and recover from data changes and from metadata operations, such as CREATE TABLE / CREATE INDEX / ALTER TABLE / DROP TABLE / DROP INDEX. Support for YSQL is still very limited, only allowing recovery of data. (The items in this list cover new functionality as well as bug fixes.)

* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] [[7135](https://github.com/yugabyte/yugabyte-db/issues/7135)] Restore table schema
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Add restore_snapshot_schedule to admin
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Add yb-admin commands to create and list snapshot schedules
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Cleanup deleted tablets
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Cleanup not restored tables and tablets
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Cleanup outdated snapshots
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Correct history retention for newly added tablets
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Fix SnapshotScheduleTest.RemoveNewTablets
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Fix YbAdminSnapshotScheduleTest.UndeleteIndex
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Handle master failover
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Load snapshot schedules during bootstrap
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Restore deleted table
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Special history retention mechanism
* [[7126](https://github.com/yugabyte/yugabyte-db/issues/7126)] Take system catalog snapshot
* [[7137](https://github.com/yugabyte/yugabyte-db/issues/7137)] Provide ability to create snapshot schedule for YSQL database and YCQL keyspace
* [[8417](https://github.com/yugabyte/yugabyte-db/issues/8417)] Implement delete_snapshot_schedule
* [[8419](https://github.com/yugabyte/yugabyte-db/issues/8419)] PITR related fixes
* [[8543](https://github.com/yugabyte/yugabyte-db/issues/8543)] Add test for need to increase table version on restore
* [[8773](https://github.com/yugabyte/yugabyte-db/issues/8773)] Add DDL log
* [[9046](https://github.com/yugabyte/yugabyte-db/issues/9046)] Fix crash when using multiple masters
* [[9162](https://github.com/yugabyte/yugabyte-db/issues/9162)] Fix stack overflow in filtering iterator
* [[9171](https://github.com/yugabyte/yugabyte-db/issues/9171)] Fix restoring snapshot to time before history cutoff

#### Yugabyte Platform

* [[7215](https://github.com/yugabyte/yugabyte-db/issues/7215)] Added an ability to select multiple backups for deletion rather than deleting individual backups.
* [[7278](https://github.com/yugabyte/yugabyte-db/issues/7278)] [[7446](https://github.com/yugabyte/yugabyte-db/issues/7446)] Added improved search usability for Live and Slow queries by adding autocomplete suggestions, better filtering and navigation.
* [[7421](https://github.com/yugabyte/yugabyte-db/issues/7421)] In order to enhance security, encryption is enabled by default for both client to node and node to node cases.
* [[7474](https://github.com/yugabyte/yugabyte-db/issues/7474)] [[7725](https://github.com/yugabyte/yugabyte-db/issues/7725)] Platform now supports creating multi-instance cloud providers. Cloud provider components to allow adding more than one config for the same provider.
* [[7799](https://github.com/yugabyte/yugabyte-db/issues/7799)] Added support for AWS GP3 volumes during universe creation from the Platform. The disk size and IOPS configuration for GP3 drives are configurable, whereas throughput is not configurable and is set to default value of 125MiB/sec.

### Improvements

#### Core database

* [[1248](https://github.com/yugabyte/yugabyte-db/issues/1248)] [YSQL] Create background task for verifying tablet data integrity
* [[1479](https://github.com/yugabyte/yugabyte-db/issues/1479)] [YBase] Allow normal load balancing for DEAD+BLACKLISTED TS
* [[3040](https://github.com/yugabyte/yugabyte-db/issues/3040)] [YBase] Allow global leader load balancing
* [[3460](https://github.com/yugabyte/yugabyte-db/issues/3460)] [YSQL] Integrate Orafce extension with Yugabyte
* [[4580](https://github.com/yugabyte/yugabyte-db/issues/4580)] add metric for wal files size (#7260)
* [[6631](https://github.com/yugabyte/yugabyte-db/issues/6631)] [YBase] Allow support for prefixes while specifying placement info
* [[6636](https://github.com/yugabyte/yugabyte-db/issues/6636)] [DocDB] Cache table->tablespace->placement information in YB-Master
* [[6845](https://github.com/yugabyte/yugabyte-db/issues/6845)] [YSQL] Introduce the 'use_node_hostname_for_local_tserver' gflag to use DNS name instead of IP for local tserver connection
* [[6947](https://github.com/yugabyte/yugabyte-db/issues/6947)] [YBase] Allow leader balancing for DEAD nodes
* [[6982](https://github.com/yugabyte/yugabyte-db/issues/6982)] [YSQL] Specify read time for catalog tables to guarantee consistent state of catalog cache
* [[7047](https://github.com/yugabyte/yugabyte-db/issues/7047)] [YSQL] Read minimal possible number of columns in case of index scan
* [[7068](https://github.com/yugabyte/yugabyte-db/issues/7068)] Allow reloading of the config file with 'ts-cli'
* [[7108](https://github.com/yugabyte/yugabyte-db/issues/7108)] [DocDB] Disable tablet splitting during index backfill
* [[7199](https://github.com/yugabyte/yugabyte-db/issues/7199)] track and display heartbeat roundtrip time from each yb-tserver in yb-master UI (#7239)
* [[7355](https://github.com/yugabyte/yugabyte-db/issues/7355)] [YSQL] check backfill bad connection status
* [[7455](https://github.com/yugabyte/yugabyte-db/issues/7455)] [YCQL] Update index from transaction with cross-key statements.
* [[7487](https://github.com/yugabyte/yugabyte-db/issues/7487)] [DocDB] Remove unnecessary Value decoding and TTL calculation in doc_reader.cc
* [[7509](https://github.com/yugabyte/yugabyte-db/issues/7509)] [YCQL] Allow both = and != operator in partial indexes.
* [[7509](https://github.com/yugabyte/yugabyte-db/issues/7509)] [YCQL] Support partial indexes
* [[7534](https://github.com/yugabyte/yugabyte-db/issues/7534)] [YSQL] Support ALTER TABLE ADD PRIMARY KEY for colocated tables
* [[7543](https://github.com/yugabyte/yugabyte-db/issues/7543)] [DocDB] Add uptime into master home UI
* [[7547](https://github.com/yugabyte/yugabyte-db/issues/7547)] Set flags automatically based on the node's available resources
* [[7557](https://github.com/yugabyte/yugabyte-db/issues/7557)] [YCQL] Support != operator
* [[7564](https://github.com/yugabyte/yugabyte-db/issues/7564)] [YBase] Auto tune ysql_num_shards_per_tserver similar to yb_num_shards_per_tserver
* [[7600](https://github.com/yugabyte/yugabyte-db/issues/7600)] [YSQL] Explain --masters in ysql_dump cli.
* [[7617](https://github.com/yugabyte/yugabyte-db/issues/7617)] [DocDB] Record and display disk usage by drive
* [[7628](https://github.com/yugabyte/yugabyte-db/issues/7628)] Add LDAP libraries as special case for YB client packaging
* [[7632](https://github.com/yugabyte/yugabyte-db/issues/7632)] [YCQL] Support upsert for JSONB column field values
* [[7647](https://github.com/yugabyte/yugabyte-db/issues/7647)] [DocDB] Adds Num SST Files to TS tablets view
* [[7649](https://github.com/yugabyte/yugabyte-db/issues/7649)] [YCQL] Block secondary index creation on static columns.
* [[7651](https://github.com/yugabyte/yugabyte-db/issues/7651)] [YSQL] Always listen on UNIX domain socket
* [[7661](https://github.com/yugabyte/yugabyte-db/issues/7661)] [DocDB] Run manually triggered compactions concurrently
* [[7705](https://github.com/yugabyte/yugabyte-db/issues/7705)] [YSQL] prioritize internal HBA config
* [[7724](https://github.com/yugabyte/yugabyte-db/issues/7724)] [YSQL] add GUC var yb_index_state_flags_update_delay
* [[7748](https://github.com/yugabyte/yugabyte-db/issues/7748)] [YSQL] ALTER ADD PK should do column checks
* [[7756](https://github.com/yugabyte/yugabyte-db/issues/7756)] Make Encryption at Rest Code Openssl 1.1.1 Compatible
* [[7804](https://github.com/yugabyte/yugabyte-db/issues/7804)] [DocDB] Make WritableFileWriter buffer gflag controllable
* [[7805](https://github.com/yugabyte/yugabyte-db/issues/7805)] Share Histograms across tablets belonging to a table instead of having Histograms (in TabletMetrics and other objects) separately for each tablet. Share Histograms in RocksDBStatistics across various tablets belonging to a table.
* [[7813](https://github.com/yugabyte/yugabyte-db/issues/7813)] [YSQL] YSQL dump should always include HASH/ASC/DESC modifier for indexes/pkey.
* [[7844](https://github.com/yugabyte/yugabyte-db/issues/7844)] Set tcmalloc max cache bytes for yb-master similar to the yb-tserver.
* [[7873](https://github.com/yugabyte/yugabyte-db/issues/7873)] [DocDB] Initialize block cache for master/sys_catalog
* [[7915](https://github.com/yugabyte/yugabyte-db/issues/7915)] Add flag to allow dumping lock batch keys in case of timeout
* [[7916](https://github.com/yugabyte/yugabyte-db/issues/7916)] CQL call timeout
* [[7944](https://github.com/yugabyte/yugabyte-db/issues/7944)] [YSQL] deprecate flag ysql_wait_until_index_permissions_timeout_ms
* [[7977](https://github.com/yugabyte/yugabyte-db/issues/7977)] [DocDB] Send per tablet disk usage to the master via heartbeats
* [[8002](https://github.com/yugabyte/yugabyte-db/issues/8002)] [DocDB] Increase thresholds for master long lock warnings
* [[8026](https://github.com/yugabyte/yugabyte-db/issues/8026)] Bump up timestamp_history_retention_interval_sec to 900s
* [[8027](https://github.com/yugabyte/yugabyte-db/issues/8027)] A separate YSQL flag for yb_client_timeout
* [[8037](https://github.com/yugabyte/yugabyte-db/issues/8037)] [DocDB] Refactor memory management for tablets into a separate class
* [[8052](https://github.com/yugabyte/yugabyte-db/issues/8052)] Add ability to configure cipher list
* [[8073](https://github.com/yugabyte/yugabyte-db/issues/8073)] Drop rocksdb memstore arena from 128kb to 64kb
* [[8330](https://github.com/yugabyte/yugabyte-db/issues/8330)] [YCQL] Provide capability to skip writing null JSONB attribute in UPDATE statement
* [DocDB] Added a max_depth param to the mem-trackers view (#7903)
* Default fail_on_out_of_range_clock_skew=false

#### Yugabyte Platform

* [[5296](https://github.com/yugabyte/yugabyte-db/issues/5296)] Allow editing cloud provider in case of provider is not in use
* [[5733](https://github.com/yugabyte/yugabyte-db/issues/5733)] Disabled "stop process" and "remove node" for a single node universe
* [[5946](https://github.com/yugabyte/yugabyte-db/issues/5946)] Clock sync is now checked while creating or expanding the universe. Clock sync is added to health checks now.
* [[6913](https://github.com/yugabyte/yugabyte-db/issues/6913)] [[6914](https://github.com/yugabyte/yugabyte-db/issues/6914)] Add ability to reset slow query data and hide slow queries.
* [[6924](https://github.com/yugabyte/yugabyte-db/issues/6924)] When a node is removed/released from a universe, hide the "Show Live Queries" button.
* [[7171](https://github.com/yugabyte/yugabyte-db/issues/7171)] Added a validation that the on-prem instance type name cannot be the same for different customers on the same platform.
* [[7193](https://github.com/yugabyte/yugabyte-db/issues/7193)] Fixed issues with Run sample apps to have the deterministic payload and unify behaviour of YCQL and YSQL app.
* [[7223](https://github.com/yugabyte/yugabyte-db/issues/7223)] [[7224](https://github.com/yugabyte/yugabyte-db/issues/7224)] Added a new “Show Universes” action in the Actions menu. This provides a way for users to see all the associated universes that are using a particular KMS config. We are now also showing the list of universes as a modal dialog box associated with the certificate.
* [[7311](https://github.com/yugabyte/yugabyte-db/issues/7311)] Added appropriate warnings while using ephemeral storage for the cases like stopping a VM or pausing an universe as it will potentially lead to data loss.
* [[7416](https://github.com/yugabyte/yugabyte-db/issues/7416)] Platform: Changed default port of On-Prem provider to 22 (#7599)
* [[7447](https://github.com/yugabyte/yugabyte-db/issues/7447)] When universe creation is in progress, other operations which require the Universe in "ready" state should be disabled like "Edit universe", "Read replicas", "Run sample apps", etc.
* [[7536](https://github.com/yugabyte/yugabyte-db/issues/7536)] You can now specify an SSH username even when not using a custom key-pair.
* [[7591](https://github.com/yugabyte/yugabyte-db/issues/7591)] Added labeling for the Azure Instance Type dropdown similar to GCP/AWS.
* [[7624](https://github.com/yugabyte/yugabyte-db/issues/7624)] Removed refetch on window focus for slow queries
* [[7706](https://github.com/yugabyte/yugabyte-db/issues/7706)] Edit backup config credentials. (#8536)
* [[7732](https://github.com/yugabyte/yugabyte-db/issues/7732)] [UI] remove beta tag from Azure provider tab
* [[7736](https://github.com/yugabyte/yugabyte-db/issues/7736)] Change the username help info for certificate based authentication
* [[7780](https://github.com/yugabyte/yugabyte-db/issues/7780)] Fixed an issue causing old backups to not get deleted by a schedule.
* [[7918](https://github.com/yugabyte/yugabyte-db/issues/7918)] Add us-west2 GCP metadata to Platform
* [[7950](https://github.com/yugabyte/yugabyte-db/issues/7950)] Navigating to a universe with KMS enabled will show this error due if something has been misconfigured
* [[8038](https://github.com/yugabyte/yugabyte-db/issues/8038)] Default metrics button now points to the Prometheus metrics endpoint.
* [[8051](https://github.com/yugabyte/yugabyte-db/issues/8051)] Redact sensitive data and secrets from audit logs
* [[8144](https://github.com/yugabyte/yugabyte-db/issues/8144)] Validate custom certificates to ensure they are signed by the correct CA, and that the cert is for the correct node
* [[8302](https://github.com/yugabyte/yugabyte-db/issues/8302)] Added Platform's metrics endpoint (/prometheus_metrics) as a scrape target to the Prometheus instance that is configured as part of the Platform install
* [[8460](https://github.com/yugabyte/yugabyte-db/issues/8460)] Made proxy timeout configurable, Default value is 60 seconds.

### Bug fixes

#### Core database

* [[4412](https://github.com/yugabyte/yugabyte-db/issues/4412)] [DocDB] Fix Load balancer state for move operations
* [[4437](https://github.com/yugabyte/yugabyte-db/issues/4437)] [[8731](https://github.com/yugabyte/yugabyte-db/issues/8731)] [DocDB] disabled bloom filters for master tablet and fixed DocDBAwareV2FilterPolicy compatibility for range-partitioned co-located tables
* [[5854](https://github.com/yugabyte/yugabyte-db/issues/5854)] [DocDB] Handling tablet splitting errors at YBSession level
* [[6096](https://github.com/yugabyte/yugabyte-db/issues/6096)] [YSQL] Fix crash during bootstrap when replaying WAL of deleted colocated table
* [[6509](https://github.com/yugabyte/yugabyte-db/issues/6509)] [YSQL] Fix leaking when a portal is used to query in small batches (**refer to the note following this list**)
* [[6672](https://github.com/yugabyte/yugabyte-db/issues/6672)] [DocDB] fix for deadlock in GlobalBacktraceState constructor
* [[6789](https://github.com/yugabyte/yugabyte-db/issues/6789)] [YSQL] Fix ysql_dumpall and ysql_dump to work with Tablespaces
* [[6821](https://github.com/yugabyte/yugabyte-db/issues/6821)] [[7069](https://github.com/yugabyte/yugabyte-db/issues/7069)] [[7344](https://github.com/yugabyte/yugabyte-db/issues/7344)] (YCQL) Fix issues when selecting optimal scan path
* [[7055](https://github.com/yugabyte/yugabyte-db/issues/7055)] [YCQL] Fixed bugs in processing LIMIT and OFFSET clause.
* [[7324](https://github.com/yugabyte/yugabyte-db/issues/7324)] [YSQL] Early bailout when bind condition is an empty search array
* [[7369](https://github.com/yugabyte/yugabyte-db/issues/7369)] [YSQL] Respect leader affinity on master sys catalog tablet
* [[7398](https://github.com/yugabyte/yugabyte-db/issues/7398)] [DocDB] Crashing after CopyTo from parent to child causes child bootstrap failure
* [[7398](https://github.com/yugabyte/yugabyte-db/issues/7398)] [DocDB] Forcing remote bootstrap to replay split operation causes seg fault
* [[7484](https://github.com/yugabyte/yugabyte-db/issues/7484)] [DocDB] Sort the hosts of tablet replicas consistently in Admin UI
* [[7499](https://github.com/yugabyte/yugabyte-db/issues/7499)] [YSQL] Import pg_dump: label INDEX ATTACH ArchiveEntries with an owner.
* [[7602](https://github.com/yugabyte/yugabyte-db/issues/7602)] [DocDB] FlushTablets rpc causes SEGV of the tserver process
* [[7641](https://github.com/yugabyte/yugabyte-db/issues/7641)] [YCQL] Fix checks in index update path that determine full row removal.
* [[7678](https://github.com/yugabyte/yugabyte-db/issues/7678)] [YSQL] Import Fix race condition in psql \e's detection of file modification.
* [[7682](https://github.com/yugabyte/yugabyte-db/issues/7682)] [YSQL] Import Forbid marking an identity column as nullable.
* [[7702](https://github.com/yugabyte/yugabyte-db/issues/7702)] [YSQL] Import Avoid corner-case memory leak in SSL parameter processing.
* [[7715](https://github.com/yugabyte/yugabyte-db/issues/7715)] [YSQL] Prevent DocPgsqlScanSpec and DocQLScanSpec from accepting rvalue reference to hash and range components
* [[7729](https://github.com/yugabyte/yugabyte-db/issues/7729)] Avoid recreating aborted transaction
* [[7729](https://github.com/yugabyte/yugabyte-db/issues/7729)] Fix checking ABORTED txn status at follower
* [[7741](https://github.com/yugabyte/yugabyte-db/issues/7741)] [YSQL] Import Don't leak malloc'd strings when a GUC setting is rejected.
* [[7791](https://github.com/yugabyte/yugabyte-db/issues/7791)] [YSQL] Import Fix psql's \connect command some more.
* [[7798](https://github.com/yugabyte/yugabyte-db/issues/7798)] [DocDB] Only the YB-Master Leader should refresh the tablespace info in memory
* [[7802](https://github.com/yugabyte/yugabyte-db/issues/7802)] [YSQL] Import Fix connection string handling in psql's \connect command.
* [[7806](https://github.com/yugabyte/yugabyte-db/issues/7806)] [YSQL] Import Fix recently-introduced breakage in psql's \connect command.
* [[7812](https://github.com/yugabyte/yugabyte-db/issues/7812)] [YSQL] Import Fix connection string handling in src/bin/scripts/ programs.
* [[7835](https://github.com/yugabyte/yugabyte-db/issues/7835)] Don't crash when trying to append ValueType::kTombstone to a key
* [[7894](https://github.com/yugabyte/yugabyte-db/issues/7894)] Keep ScopedRWOperation while applying intents for large transaction
* [[7937](https://github.com/yugabyte/yugabyte-db/issues/7937)] [YSQL] Avoid unnecessary secondary index writes for UPDATE on table with
* [[7979](https://github.com/yugabyte/yugabyte-db/issues/7979)] ysql Import Fix handling of -d "connection string" in pg_dump/pg_restore.
* [[8006](https://github.com/yugabyte/yugabyte-db/issues/8006)] [YSQL] Import Fix out-of-bound memory access for interval -> char conversion
* [[8029](https://github.com/yugabyte/yugabyte-db/issues/8029)] Fix slow queries failing to fetch on client-to-node TLS encrypted universes
* [[8030](https://github.com/yugabyte/yugabyte-db/issues/8030)] [YSQL] Import Redesign the caching done by get_cached_rowtype().
* [[8047](https://github.com/yugabyte/yugabyte-db/issues/8047)] [YSQL] Import Fix some inappropriately-disallowed uses of ALTER ROLE/DATABASE SET.
* [[8065](https://github.com/yugabyte/yugabyte-db/issues/8065)] [DocDB] Fix Sys Catalog Leader Affinity with Full Move
* [[8079](https://github.com/yugabyte/yugabyte-db/issues/8079)] Ensure leadership before handling catalog version
* [[8101](https://github.com/yugabyte/yugabyte-db/issues/8101)] [YCQL] Fixed CQLServiceImpl::Shutdown
* [[8118](https://github.com/yugabyte/yugabyte-db/issues/8118)] [YSQL] Import 'Fix memory leak when rejecting bogus DH parameters.'
* [[8150](https://github.com/yugabyte/yugabyte-db/issues/8150)] [[8196](https://github.com/yugabyte/yugabyte-db/issues/8196)] Fix preceding op id in case of empty ops sent to the follower
* [[8204](https://github.com/yugabyte/yugabyte-db/issues/8204)] [YBase] GetLoadMoveCompletionPercent returns an incorrect 100% if tservers haven't heartbeated their tablet reports
* [[8254](https://github.com/yugabyte/yugabyte-db/issues/8254)] No leader lease needed for BackfillIndex
* [[8348](https://github.com/yugabyte/yugabyte-db/issues/8348)] Correctly handling a failure to create a priority thread pool worker thread
* [[8360](https://github.com/yugabyte/yugabyte-db/issues/8360)] [YBase] Correctly compare running workers
* [[8388](https://github.com/yugabyte/yugabyte-db/issues/8388)] [YSQL] prevent temp indexes from using lsm
* [[8390](https://github.com/yugabyte/yugabyte-db/issues/8390)] Fix NPE Handling for indexed_table_id
* [[8496](https://github.com/yugabyte/yugabyte-db/issues/8496)] Downgrade gperftools to 2.7
* [[8591](https://github.com/yugabyte/yugabyte-db/issues/8591)] [DocDB] Add protection against missing UserFrontiers in older SST files during intents cleanup
* [[8766](https://github.com/yugabyte/yugabyte-db/issues/8766)] [DocDB] recreate table with the same name could cause insert to fail
* [[8834](https://github.com/yugabyte/yugabyte-db/issues/8834)] [YCQL] Remove incorrect CHECK condition added in D10931
* [[9108](https://github.com/yugabyte/yugabyte-db/issues/9108)] Tablespace task in YB-Master should skip system_postgres namespace
* [YSQL] Try all preferred zones in master sys catalog affinity task and suppress logs

{{< note title="New built-in functions" >}}
[[6509](https://github.com/yugabyte/yugabyte-db/issues/6509)] adds several new built-in functions that can be used to collect internal memory usage data:

* `yb_getrusage()` returns the output from the system `getrusage()` call.
* `yb_mem_usage_kb()` returns memory usage in kilobytes for the current session in the proxy server.
* `yb_mem_usage()` is the text version of `yb_mem_usage_kb()`. Returning a text value allows Yugabyte to provide more details than just the total usage.
* `yb_mem_usage_sql_b` and `yb_mem_usage_sql_kb` return memory usage in bytes and kilobytes respectively by the SQL layer of the current session in the proxy server.
* `yb_mem_usage_sql` returns SQL usage in text. The text value may contain more details than just the total usage.

These functions are enabled by default for _new installations_ on 2.6. But, if you’re upgrading from v2.4 or an earlier version, you can enable them manually by adding them to pg_proc.
{{</note>}}

#### Yugabyte Platform

* [[1342](https://github.com/yugabyte/yugabyte-db/issues/1342)] Fixing the error message when the get host info call to cloud providers fails
* [[7007](https://github.com/yugabyte/yugabyte-db/issues/7007)] Fixed an issue where Restore backup dialog allowed empty/no universe name selected.
* [[7408](https://github.com/yugabyte/yugabyte-db/issues/7408)] Retry Task button should not be visible for tasks other than "Create Universe" Task, as it’s the only task that supports retry.
* [[7437](https://github.com/yugabyte/yugabyte-db/issues/7437)] Since Kubernetes currently doesn't support read replicas, disabled it from the UI; k8s providers are also not shown when configuring a read-replica.
* [[7441](https://github.com/yugabyte/yugabyte-db/issues/7441)] Added field-level validation for User Tags to disallow "Name" as a key for a tag
* [[7442](https://github.com/yugabyte/yugabyte-db/issues/7442)] Only include the queries run by the user under slow queries
* [[7444](https://github.com/yugabyte/yugabyte-db/issues/7444)] Fixed an issue in Edit Universe, as user was able to edit User Tags but not save them
* [[7554](https://github.com/yugabyte/yugabyte-db/issues/7554)] Fixed an issue where error toaster appears even when the Provider is added successfully
* [[7562](https://github.com/yugabyte/yugabyte-db/issues/7562)] In case of Encryption at rest configuration fixed an error in configuring KMS provider.
* [[7593](https://github.com/yugabyte/yugabyte-db/issues/7593)] [[7895](https://github.com/yugabyte/yugabyte-db/issues/7895)] Fixed and issue where users Unable to create azure universe with custom image from image gallery
* [[7656](https://github.com/yugabyte/yugabyte-db/issues/7656)] After manually provisioning an on-premises node, create universe tries to use "centos" user, not "yugabyte"
* [[7687](https://github.com/yugabyte/yugabyte-db/issues/7687)] YSQL health check fails when YSQL auth is enabled
* [[7698](https://github.com/yugabyte/yugabyte-db/issues/7698)] Custom SMTP Configuration API returns unmasked SMTP password
* [[7703](https://github.com/yugabyte/yugabyte-db/issues/7703)] Can't send email for custom SMTP settings without authentication (empty username)
* [[7704](https://github.com/yugabyte/yugabyte-db/issues/7704)] Backup to S3 fails using Yugaware instance's IAM role
* [[7727](https://github.com/yugabyte/yugabyte-db/issues/7727)] [[7728](https://github.com/yugabyte/yugabyte-db/issues/7728)] Fix UI issues with k8s provider creation and deletion
* [[7769](https://github.com/yugabyte/yugabyte-db/issues/7769)] Prevent adding on-prem node instance with duplicate IP
* [[7779](https://github.com/yugabyte/yugabyte-db/issues/7779)] Health check fails on k8s portal for all the universes on clock synchronization with FailedClock synchronization and Error getting NTP state
* [[7810](https://github.com/yugabyte/yugabyte-db/issues/7810)] Health check emails not working with default SMTP configuration
* [[7811](https://github.com/yugabyte/yugabyte-db/issues/7811)] Slow queries is not displaying all queries on k8s universe pods
* [[7841](https://github.com/yugabyte/yugabyte-db/issues/7841)] Resume Universe Failure. (#7508)
* [[7864](https://github.com/yugabyte/yugabyte-db/issues/7864)] Delete associated backups after deleting the universe. (#8579)
* [[7959](https://github.com/yugabyte/yugabyte-db/issues/7959)] Disabling Node-to-Node TLS during universe creation causes universe creation to fail
* [[7995](https://github.com/yugabyte/yugabyte-db/issues/7995)] Enable SSH pipelining
* [[8399](https://github.com/yugabyte/yugabyte-db/issues/8399)] Fix read replica cluster addition failure
* [[8426](https://github.com/yugabyte/yugabyte-db/issues/8426)] Remove duplicate "Clock Skew Alert Resolved" messages
* [[8503](https://github.com/yugabyte/yugabyte-db/issues/8503)] Fix Add instance modal form to allow for adding instances in isolated region cases.
* [[8525](https://github.com/yugabyte/yugabyte-db/issues/8525)] Audit migration failed on AWS portal
* [[8541](https://github.com/yugabyte/yugabyte-db/issues/8541)] Returns bad request as a response when trying to create a provider with an existing name
* [[8611](https://github.com/yugabyte/yugabyte-db/issues/8611)] Enable edit backup configuration for the active tab only. (#8673)
* [[8620](https://github.com/yugabyte/yugabyte-db/issues/8620)] Reset the values on cancel. (#8626)
* [[8742](https://github.com/yugabyte/yugabyte-db/issues/8742)] UI shows blank page after clicking on the edit provider configuration button
* Universe alerts should be resolved when a universe is deleted (#7537)

### Known issues

#### Core database

N/A

#### Yugabyte Platform

N/A

## Notes

{{< note title="New release versioning" >}}

Starting with v2.2, Yugabyte release versions follow a [new release versioning convention](../../versioning). The latest release series, denoted by `MAJOR.ODD`, incrementally introduces new features and changes and is intended for development and testing only. Revision releases, denoted by `MAJOR.ODD.REVISION` versioning, can include new features and changes that might break backwards compatibility. For more information, see [Supported and planned releases](../../releases-overview).

{{< /note >}}

{{< note title="Upgrading from 1.3" >}}

Prior to v2.0, YSQL was still in beta. Upon release of v2.0, a backward-incompatible file format change was made for YSQL. For existing clusters running pre-2.0 release with YSQL enabled, you cannot upgrade to v2.0 or later. Instead, export your data from existing clusters and then import the data into a new cluster (v2.0 or later).

{{< /note >}}
