---
title: What's new in the v2.4 stable release series
headerTitle: What's new in the v2.4 stable release series
linkTitle: v2.4 (stable)
description: Enhancements, changes, and resolved issues in the current stable release series recommended for production deployments.
headcontent: Features, enhancements, and resolved issues in the current stable release series recommended for production deployments.
aliases:
  - /latest/releases/
menu:
  latest:
    identifier: stable-releases
    parent: whats-new
    weight: 2586
isTocNested: true
showAsideToc: true  
---

{{< note title="Notable features and changes" >}}

Included here are the release notes for all releases in the v2.4 stable release series. Content will be added as new notable features and changes are available in the patch releases of the v2.4 stable release series.

{{< /note >}}

## v2.4.1 - Feb 10, 2021

### Downloads

<a class="download-binary-link" href="https://downloads.yugabyte.com/yugabyte-2.4.1.0-darwin.tar.gz">
  <button>
    <i class="fab fa-apple"></i><span class="download-text">macOS</span>
  </button>
</a>
&nbsp; &nbsp; &nbsp;
<a class="download-binary-link" href="https://downloads.yugabyte.com/yugabyte-2.4.1.0-linux.tar.gz">
  <button>
    <i class="fab fa-linux"></i><span class="download-text">Linux</span>
  </button>
</a>
<br />

### Docker

```sh
docker pull yugabytedb/yugabyte:2.4.1.0-b25
```

### Improvements

#### Yugabyte Platform

* When creating/restoring backups, use the local UNIX socket to connect to YSQL instead of the hostname if authentication is enabled [5571](https://github.com/yugabyte/yugabyte-db/issues/5571)
* On universe creation, platform checks that clocks are synchronized [6017](https://github.com/yugabyte/yugabyte-db/issues/6017)
* Health Check generates alerts if it isn't running [6581](https://github.com/yugabyte/yugabyte-db/issues/6581)
* Manual provisioning now runs the same preflight checks as regular provisioning [6819](https://github.com/yugabyte/yugabyte-db/issues/6819)
* Retrying a failed task now provides visual feedback [6820](https://github.com/yugabyte/yugabyte-db/issues/6820)
* Removed the "Transactions" plot line from YSQL Ops and Latency graphs  [6839](https://github.com/yugabyte/yugabyte-db/issues/6839)
* You can now specify a minimum TLS version for Platform [6893](https://github.com/yugabyte/yugabyte-db/issues/6893), [7140](https://github.com/yugabyte/yugabyte-db/issues/7140)
* Health checks now default to TLS 1.2 [7196](https://github.com/yugabyte/yugabyte-db/issues/7196)

#### Core Database

* The YB-TServer metrics output (for example, on [localhost](http://127.0.0.1:13000/metrics)) now shows the total affected rows for each operation. [4600](https://github.com/yugabyte/yugabyte-db/issues/4600)
* YSQL: Backup for colocated databases [4874](https://github.com/yugabyte/yugabyte-db/issues/4874)
* Optimizations to the YSQL layer to apply empty deletes only when required [5686](https://github.com/yugabyte/yugabyte-db/issues/5686)
    * Backup: Fix restore of colocated table with `table_oid` already set [6678](https://github.com/yugabyte/yugabyte-db/issues/6678)
* Metrics thread now start after the first replication stream is created [5251](https://github.com/yugabyte/yugabyte-db/issues/5251)
* The YB-TServer metrics output (for example, on [localhost](http://127.0.0.1:13000/metrics)) now shows transaction BEGIN, COMMIT, and ROLLBACK statements. [6486](https://github.com/yugabyte/yugabyte-db/issues/6486)
* Restore now preserves the exact partitioning of the source tablets [6628](https://github.com/yugabyte/yugabyte-db/issues/6628)
* Enabled a sanity check to ensure that the tablet lookup result matches the partition key [7016](https://github.com/yugabyte/yugabyte-db/issues/7016)

### Bug Fixes

#### Yugabyte Platform

* Release instance was not an option for a node if an install failed because of SSH access [5942](https://github.com/yugabyte/yugabyte-db/issues/5942)
* Backup-related tasks (schedules, restores, deletes) failed when the storage configuration was deleted [6680](https://github.com/yugabyte/yugabyte-db/issues/6680)
* VPC cross-linking failed during creation of an AWS provider [6748](https://github.com/yugabyte/yugabyte-db/issues/6748)
* Fixes to YSQL backups with node-to-node TLS encryption enabled [6965](https://github.com/yugabyte/yugabyte-db/issues/6965)
    * Add certs flags to `ysql_dump` when backing up a node-to-node TLS-enabled universe
* Corrected an error when backing up multiple YSQL namespaces in a universe that is encrypted at rest [7114](https://github.com/yugabyte/yugabyte-db/issues/7114)
* Fixed a syntax error in replicated.yml [7180](https://github.com/yugabyte/yugabyte-db/issues/7180)
* Fixed an issue preventing health checks from using an appropriate TLS version [7196](https://github.com/yugabyte/yugabyte-db/issues/7196)

#### Core Database

* YSQL: Fix calculation for transaction counts in YSQL metrics. [4599](https://github.com/yugabyte/yugabyte-db/issues/4599)
* ybase: a blacklisted TS' initial load was not replicated during master failover [6397](https://github.com/yugabyte/yugabyte-db/issues/6397)
* The CREATE INDEX statement generated by ysql_dump now uses SPLIT INTO syntax [6537](https://github.com/yugabyte/yugabyte-db/issues/6537)
* YCQL: Fixed a bug with an index update on a list after an overwrite [6735](https://github.com/yugabyte/yugabyte-db/issues/6735)
* Fixed a bug in YSQL for online index backfill capabilities, to ensure deletes to an index are persisted during backfill [6811](https://github.com/yugabyte/yugabyte-db/issues/6811)
* YCQL: Fixed various issues for literals of collection datatypes [6829](https://github.com/yugabyte/yugabyte-db/issues/6829), [6879](https://github.com/yugabyte/yugabyte-db/issues/6879)
* The permissions map is now rebuilt after all ALTER ROLE operations [7008](https://github.com/yugabyte/yugabyte-db/issues/7008)
* Fixes to Async Replication 2DC capabilities, including a bug fix for a race condition on consumer with smaller batch sizes [7040](https://github.com/yugabyte/yugabyte-db/issues/7040)

## v2.4.0 - Jan 22, 2021

### Downloads

<a class="download-binary-link" href="https://downloads.yugabyte.com/yugabyte-2.4.0.0-darwin.tar.gz">
  <button>
    <i class="fab fa-apple"></i><span class="download-text">macOS</span>
  </button>
</a>
&nbsp; &nbsp; &nbsp;
<a class="download-binary-link" href="https://downloads.yugabyte.com/yugabyte-2.4.0.0-linux.tar.gz">
  <button>
    <i class="fab fa-linux"></i><span class="download-text">Linux</span>
  </button>
</a>
<br />

### Docker

```sh
docker pull yugabytedb/yugabyte:2.4.0.0-b60
```

### New Features

#### Yugabyte Platform

- Data encryption in-transit enhancements:

  - Support for bringing your own CA certificates for an on-premise cloud provider ([#5729](https://github.com/yugabyte/yugabyte-db/issues/5729))
  - Support for rotating custom CA certificates ([#5727](https://github.com/yugabyte/yugabyte-db/issues/5727))
  - Displaying the certificate name in the YB Platform Universe editor
  - Moving of the Certificates page to Cloud Config > Security > Encryption in Transit

- Moving of TLS In-Transit Certificates to Cloud > Security Config

- Support for Rolling restart of a universe ([#6323](https://github.com/yugabyte/yugabyte-db/issues/6323))

- Support for VMware Tanzu as a new cloud provider ([#6633](https://github.com/yugabyte/yugabyte-db/issues/6633))

- Alerts for backup tasks ([#5556](https://github.com/yugabyte/yugabyte-db/issues/5556))

#### Core Database

- Introducing support for audit logging in YCQL and YSQL API ([#1331](https://github.com/yugabyte/yugabyte-db/issues/1331),[ #5887](https://github.com/yugabyte/yugabyte-db/issues/5887), [#6199](https://github.com/yugabyte/yugabyte-db/issues/6199))
- Ability to log slow running queries in YSQL ([#4817](https://github.com/YugaByte/yugabyte-db/issues/4817))
- Introducing support for LDAP integration in YSQL API ([#6088](https://github.com/yugabyte/yugabyte-db/issues/6088))

### Improvements

#### Yugabyte Platform

- Support for Transactional tables and Cross Cluster Async Replication topology ([#5779](https://github.com/yugabyte/yugabyte-db/issues/5779))
- Support for very large transactions and stability improvements ([#1923](https://github.com/yugabyte/yugabyte-db/issues/1923))
- Displaying an entire query in the detailed view of the live queries tab ([#6412](https://github.com/yugabyte/yugabyte-db/issues/6412))
- Not returning Hashes and Tokens in API responses ([#6388](https://github.com/yugabyte/yugabyte-db/issues/6388))
- Authentication of proxy requests against a valid platform session ([#6544](https://github.com/yugabyte/yugabyte-db/issues/6544))
- Disabling of the unused API endpoint run_query ([#6383](https://github.com/yugabyte/yugabyte-db/issues/6388))
- Improved error handling for user-created concurrent backup tasks ([#5888](https://github.com/yugabyte/yugabyte-db/issues/5888))
- Performance improvements for live queries ([#6289](https://github.com/yugabyte/yugabyte-db/issues/6289))
- Pre-flight checks for Create Universe, Edit Universe, and Add Node operations for an on-premise provider ([#6016](https://github.com/yugabyte/yugabyte-db/issues/6016))
- Disabling of the unused API endpoint run_in_shell ([#6384](https://github.com/yugabyte/yugabyte-db/issues/6384))
- Disabling of the unused API endpoint create_db_credentials ([#6385](https://github.com/yugabyte/yugabyte-db/issues/6385))
- Input validation for Kubernetes configuration path ([#6389](https://github.com/yugabyte/yugabyte-db/issues/6389))
- Input validation for the access_keys API endpoint ([#6386](https://github.com/yugabyte/yugabyte-db/issues/6386))
- Input path validation for backup target paths ([#6382](https://github.com/yugabyte/yugabyte-db/issues/6382))
- Timeout support for busybox ([#6652](https://github.com/yugabyte/yugabyte-db/issues/6652))
- Enabling of CORS policy by default ([#6390](https://github.com/yugabyte/yugabyte-db/issues/6390))

- Deleting backups for TLS-enabled universes ([#5980](https://github.com/yugabyte/yugabyte-db/issues/5980))

#### Core Database

- DDL consistency ([#4710](https://github.com/yugabyte/yugabyte-db/issues/4710),[ #3979](https://github.com/yugabyte/yugabyte-db/issues/3979),[ #4360](https://github.com/yugabyte/yugabyte-db/issues/4360))

- DDL performance improvements ([#5177](https://github.com/yugabyte/yugabyte-db/issues/5177), [#3503](https://github.com/yugabyte/yugabyte-db/issues/3503))

- ALTER versions for ORM support ([#4424](https://github.com/yugabyte/yugabyte-db/issues/4424))

- Online index backfill GA ([#2301](https://github.com/yugabyte/yugabyte-db/issues/2301), [#4899](https://github.com/yugabyte/yugabyte-db/issues/4899), [#5031](https://github.com/yugabyte/yugabyte-db/issues/5031))

- Table partitioning ([#1126](https://github.com/yugabyte/yugabyte-db/issues/1126),[ #5387](https://github.com/yugabyte/yugabyte-db/issues/5387))

- SQL support improvements:

  - Support for the FROM clause in UPDATE ([#738](https://github.com/YugaByte/yugabyte-db/issues/738))
  - Support for the USING clause in DELETE ([#738](https://github.com/YugaByte/yugabyte-db/issues/738))
  - SQL/JSON path language [PG12] ([#5408](https://github.com/YugaByte/yugabyte-db/issues/5408))

- Performance improvements:

  - Sequence cache min-value flag ([#6041](https://github.com/yugabyte/yugabyte-db/issues/6041),[ #5869](https://github.com/yugabyte/yugabyte-db/issues/5869))
  - Foreign-key batching ([#2951](https://github.com/yugabyte/yugabyte-db/issues/2951))

- YSQL usability improvements:

  - COPY FROM rows-per-txn option ([#2855](https://github.com/yugabyte/yugabyte-db/issues/2855), [#6380](https://github.com/yugabyte/yugabyte-db/issues/6380))
  - COPY / ysql_dump OOM ([#5205](https://github.com/yugabyte/yugabyte-db/issues/5205), [#5453](https://github.com/yugabyte/yugabyte-db/issues/5453), [#5603](https://github.com/yugabyte/yugabyte-db/issues/5603))
  - Large DML OOM ([#5374](https://github.com/yugabyte/yugabyte-db/issues/5374))
  - Updating pkey values ([#659](https://github.com/yugabyte/yugabyte-db/issues/659))
  - Restarting write-txns on conflict ([#4291](https://github.com/yugabyte/yugabyte-db/issues/4291))

- YSQL statement execution statistics ([#5478](https://github.com/yugabyte/yugabyte-db/issues/5478))

- Improved raft leader stepdown operations, by waiting for the target follower to catch up. This reduces the unavailability window during cluster operations. ([#5570](https://github.com/YugaByte/yugabyte-db/issues/5570))

- Improved performance during cluster overload:

  - Improved throttling for user-level YCQL RPC ([#4973](https://github.com/yugabyte/yugabyte-db/issues/4973))
  - Lower YCQL live RPC memory usage ([#5057](https://github.com/yugabyte/yugabyte-db/issues/5057))
  - Improved throttling for internal master level RPCs ([#5434](https://github.com/yugabyte/yugabyte-db/issues/5434))

- Improved performance for YCQL and many connections:

  - Cache for generic YCQL system queries ([#5043](https://github.com/yugabyte/yugabyte-db/pull/5043))
  - Cache for auth information for YCQL ([#6010](https://github.com/yugabyte/yugabyte-db/issues/6010))
  - Speedup system.partitions queries ([#6394](https://github.com/yugabyte/yugabyte-db/issues/6394))

- Improved DNS handling:

  - Process-wide cache for DNS queries ([#5201](https://github.com/yugabyte/yugabyte-db/issues/5201))
  - Avoid duplicate DNS requests during YCQL system.partitions queries ([#5225](https://github.com/yugabyte/yugabyte-db/issues/5225))

- Improved master-level load balancer (LB) operations:

  - Throttle tablet moves per table ([#4053](https://github.com/yugabyte/yugabyte-db/issues/4053))
  - New global optimization for tablet balancing -- this also allows the LB to work with colocation ([#3079](https://github.com/yugabyte/yugabyte-db/issues/3079))
  - New global optimization for leader balancing ([#5021](https://github.com/yugabyte/yugabyte-db/issues/5021))
  - Delay LB on master leader failover ([#5221](https://github.com/yugabyte/yugabyte-db/issues/5221))
  - Register temporary TS on master failover to prevent invalid LB operations ([#4691](https://github.com/yugabyte/yugabyte-db/issues/4691))
  - Configurable option to disallow TLS v1.0 and v1.1 protocols ([#6865](https://github.com/yugabyte/yugabyte-db/issues/6865))

- Skip loading deleted table metadata into master memory ([#5122](https://github.com/yugabyte/yugabyte-db/issues/5122))

### Bug Fixes

#### Yugabyte Platform

- Edit Universe did not display the TLS certificate used
- Multi-zone K8s universe creation failed ([#5882](https://github.com/yugabyte/yugabyte-db/issues/5882))
- Tasks page reported an incorrect status of a failed backup ([#6210](https://github.com/yugabyte/yugabyte-db/issues/6210))
- K8s universe upgrade did not wait for each pod to update before moving to the next ([#6360](https://github.com/yugabyte/yugabyte-db/issues/6360))
- Changing node count by AZ was doing a full move ([#5335](https://github.com/yugabyte/yugabyte-db/issues/5335))
- Enabling Encryption-at-Rest without KMS configuration caused create Universe to fail silently ([#6228](https://github.com/yugabyte/yugabyte-db/issues/6228))
- Missing SST size in the Nodes page of a universe with read replicas ([#6275](https://github.com/yugabyte/yugabyte-db/issues/6275))
- Incorrect link in the Live queries dashboard node name ([#6458](https://github.com/yugabyte/yugabyte-db/issues/6458))
- Failure to create a TLS-enabled universe with a custom home directory setting with on-premise provider ([#6602](https://github.com/yugabyte/yugabyte-db/issues/6602))
- Database node health liveliness check was blocked indefinitely ([#6301](https://github.com/yugabyte/yugabyte-db/issues/6301))

#### Core Database

- Critical fixes for transaction cleanup applicable to aborted transactions (observed frequently as servers reaching soft memory limit)

- Main raft fixes:

  - Incorrect tracking of flushed op id in case of aborted operations ([#4150](https://github.com/yugabyte/yugabyte-db/issues/4150))
  - Various fixes and potential crashes on tablet bootstrap ([#5003](https://github.com/yugabyte/yugabyte-db/issues/5003), [#3759](https://github.com/yugabyte/yugabyte-db/issues/3759), [#4983](https://github.com/yugabyte/yugabyte-db/issues/4983), [#5215](https://github.com/yugabyte/yugabyte-db/issues/5215), [#5224](https://github.com/yugabyte/yugabyte-db/issues/5224))
  - Avoid running out of threads due to FailureDetector ([#5752](https://github.com/yugabyte/yugabyte-db/issues/5752))

- Metrics reporting could be inconsistent due to regular and intent rocksdb using the same statistics object ([#5640](https://github.com/yugabyte/yugabyte-db/issues/5640))

- Various issues with the rocksdb snapshot mechanism used for backup/restore ([#6170](https://github.com/yugabyte/yugabyte-db/issues/6170), [#4756](https://github.com/yugabyte/yugabyte-db/issues/4756), [#5337](https://github.com/yugabyte/yugabyte-db/issues/5337))

- Fixes in the YCQL API:

  - Fix parser typo when scanning binary values ([#6827](https://github.com/yugabyte/yugabyte-db/issues/6827))
  - Omit where clause if there is no range column ([#6826](https://github.com/yugabyte/yugabyte-db/issues/6826))
  - Fix JSONB operator execution when applying to NULL objects ([#6766](https://github.com/yugabyte/yugabyte-db/issues/6766))
  - Fix range deletes to start and end at specified primary key bounds ([#6649](https://github.com/yugabyte/yugabyte-db/issues/6649))

- Fixes in the YSQL API:

  - Fix crash for large DDL transaction ([#6430](https://github.com/yugabyte/yugabyte-db/issues/6430))
  - Fixed SIGSERV in YBPreloadRelCache ([#6317](https://github.com/yugabyte/yugabyte-db/issues/6317))
  - Fix backup-restore issues when source table was altered before ([#6009](https://github.com/YugaByte/yugabyte-db/issues/6009), [#6245](https://github.com/YugaByte/yugabyte-db/issues/6245), [#5958](https://github.com/YugaByte/yugabyte-db/issues/5958))
  - Fix backup-restore failure due to unexpected NULLABLE column attribute. ([#6886](https://github.com/yugabyte/yugabyte-db/issues/6886))

- Retry on SSL_ERROR_WANT_WRITE ([#6266](https://github.com/yugabyte/yugabyte-db/issues/6266))

### Known Issues

#### Yugabyte Platform

- Azure IaaS orchestration (in beta status):

  - No pricing information ([#5624](https://github.com/yugabyte/yugabyte-db/issues/5624))
  - No support for regions with zero availability zones (AZs) ([#5628](https://github.com/yugabyte/yugabyte-db/issues/5628))

#### Core Database

- Automatic Tablet Splitting:

  - While a tablet split is occurring, in-flight operations for both YCQL and YSQL APIs would currently receive errors. These would currently have to be retried at the application level currently. In the future, these will be transparently handled underneath the hood. The immediate impact for this would be that certain tools like TPCC or sysbench would fail while tablet splitting is happening.

## Notes

{{< note title="New release versioning" >}}

Starting with v2.2, Yugabyte release versions follow a [new release versioning convention](../../versioning). The latest release series, denoted by `MAJOR.ODD`, incrementally introduces new features and changes and is intended for development and testing only. Revision releases, denoted by `MAJOR.ODD.REVISION` versioning, can include new features and changes that might break backwards compatibility. For more information, see [Supported and planned releases](../../releases-overview).

{{< /note >}}

{{< note title="Upgrading from 1.3" >}}

Prior to v2.0, YSQL was still in beta. Upon release of v2.0, a backward-incompatible file format change was made for YSQL. For existing clusters running pre-2.0 release with YSQL enabled, you cannot upgrade to v2.0 or later. Instead, export your data from existing clusters and then import the data into a new cluster (v2.0 or later).

{{< /note >}}
