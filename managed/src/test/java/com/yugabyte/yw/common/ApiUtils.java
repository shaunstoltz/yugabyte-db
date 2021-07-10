// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ClusterType;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.Universe.UniverseUpdater;
import com.yugabyte.yw.models.helpers.*;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import org.yb.ColumnSchema.SortOrder;

import java.util.*;

public class ApiUtils {
  public static String UTIL_INST_TYPE = "m3.medium";

  public static Universe.UniverseUpdater mockUniverseUpdater() {
    return mockUniverseUpdater("host", null);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(Common.CloudType cloudType) {
    return mockUniverseUpdater("host", cloudType);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(String nodePrefix) {
    return mockUniverseUpdater(nodePrefix, null);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      final String nodePrefix, final Common.CloudType cloudType) {
    return mockUniverseUpdater(nodePrefix, cloudType, false);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      final String nodePrefix, final Common.CloudType cloudType, final boolean backupState) {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
        userIntent.providerType = cloudType;
        userIntent.accessKeyCode = "yugabyte-default";
        // Add a desired number of nodes.
        userIntent.numNodes = userIntent.replicationFactor;
        universeDetails.upsertPrimaryCluster(userIntent, null);
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        for (int idx = 1; idx <= userIntent.numNodes; idx++) {
          // TODO: This state needs to be ToBeAdded as Create(k8s)Univ runtime sets it to Live
          // and nodeName should be null for ToBeAdded.
          NodeDetails node =
              getDummyNodeDetails(
                  idx, NodeDetails.NodeState.Live, idx <= userIntent.replicationFactor);
          node.placementUuid = universeDetails.getPrimaryCluster().uuid;
          universeDetails.nodeDetailsSet.add(node);
        }
        universeDetails.nodePrefix = nodePrefix;
        universeDetails.backupInProgress = backupState;
        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(UserIntent userIntent) {
    return mockUniverseUpdater(userIntent, "host", false /* setMasters */);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      final UserIntent userIntent, final PlacementInfo placementInfo) {
    return mockUniverseUpdater(userIntent, "host", false);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      final UserIntent userIntent, final PlacementInfo placementInfo, boolean setMasters) {
    return mockUniverseUpdater(userIntent, "host", setMasters, false, placementInfo);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      UserIntent userIntent, boolean setMasters) {
    return mockUniverseUpdater(userIntent, "host", setMasters);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      UserIntent userIntent, String nodePrefix) {
    return mockUniverseUpdater(userIntent, nodePrefix, false /* setMasters */);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      final UserIntent userIntent, final String nodePrefix, final boolean setMasters) {
    return mockUniverseUpdater(userIntent, nodePrefix, setMasters, false /* updateInProgress */);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      final UserIntent userIntent,
      final String nodePrefix,
      final boolean setMasters,
      final boolean updateInProgress) {
    PlacementInfo placementInfo =
        PlacementInfoUtil.getPlacementInfo(
            ClusterType.PRIMARY, userIntent, userIntent.replicationFactor);
    return mockUniverseUpdater(userIntent, nodePrefix, setMasters, updateInProgress, placementInfo);
  }

  public static Universe.UniverseUpdater mockUniverseUpdater(
      final UserIntent userIntent,
      final String nodePrefix,
      final boolean setMasters,
      final boolean updateInProgress,
      final PlacementInfo placementInfo) {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = new UniverseDefinitionTaskParams();
        universeDetails.upsertPrimaryCluster(userIntent, placementInfo);
        universeDetails.nodeDetailsSet = new HashSet<>();
        universeDetails.updateInProgress = updateInProgress;
        for (int idx = 1; idx <= userIntent.numNodes; idx++) {
          // TODO: This state needs to be ToBeAdded as Create(k8s)Univ runtime sets it to Live
          // and nodeName should be null for ToBeAdded.
          NodeDetails node =
              getDummyNodeDetails(
                  idx,
                  NodeDetails.NodeState.Live,
                  setMasters && idx <= userIntent.replicationFactor);
          node.placementUuid = universeDetails.getPrimaryCluster().uuid;
          if (placementInfo != null) {
            List<PlacementInfo.PlacementAZ> azList =
                placementInfo.cloudList.get(0).regionList.get(0).azList;
            int azIndex = (idx - 1) % azList.size();
            node.azUuid = azList.get(azIndex).uuid;
          }
          universeDetails.nodeDetailsSet.add(node);
        }
        universeDetails.nodePrefix = nodePrefix;
        universeDetails.rootCA = universe.getUniverseDetails().rootCA;
        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWithReadReplica(
      final UserIntent userIntent, final PlacementInfo placementInfo) {

    return universe -> {
      UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
      UniverseDefinitionTaskParams.Cluster readReplica =
          universeDetails.upsertCluster(userIntent, placementInfo, UUID.randomUUID());
      int currentNodes = universeDetails.nodeDetailsSet.size();
      for (int idx = currentNodes + 1; idx <= currentNodes + userIntent.numNodes; idx++) {
        NodeDetails node = getDummyNodeDetails(idx, NodeState.Live, false);
        node.placementUuid = readReplica.uuid;
        if (placementInfo != null) {
          List<PlacementInfo.PlacementAZ> azList =
              placementInfo.cloudList.get(0).regionList.get(0).azList;
          int azIndex = (idx - 1) % azList.size();
          node.azUuid = azList.get(azIndex).uuid;
        }
        universeDetails.nodeDetailsSet.add(node);
      }
      universe.setUniverseDetails(universeDetails);
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWithInactiveNodes() {
    return mockUniverseUpdaterWithInactiveNodes(false);
  }

  public static Universe insertInstanceTags(UUID univUUID) {
    UniverseUpdater updater =
        new UniverseUpdater() {
          @Override
          public void run(Universe universe) {
            UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
            userIntent.instanceTags.put("Cust", "Test");
          }
        };
    return Universe.saveDetails(univUUID, updater);
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWithInactiveNodes(
      final boolean setMasters) {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
        // Add a desired number of nodes.
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        userIntent.numNodes = userIntent.replicationFactor;
        for (int idx = 1; idx <= userIntent.numNodes; idx++) {
          NodeDetails node =
              getDummyNodeDetails(
                  idx,
                  NodeDetails.NodeState.Live,
                  setMasters && idx <= userIntent.replicationFactor);
          universeDetails.nodeDetailsSet.add(node);
        }
        universeDetails.upsertPrimaryCluster(userIntent, null);

        NodeDetails node =
            getDummyNodeDetails(userIntent.numNodes + 1, NodeDetails.NodeState.Removed);
        universeDetails.nodeDetailsSet.add(node);
        universeDetails.nodePrefix = "host";
        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWithYSQLNodes(
      final boolean enableYSQL) {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
        // Add a desired number of nodes.
        userIntent.enableYSQL = enableYSQL;
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        userIntent.numNodes = userIntent.replicationFactor;
        for (int idx = 1; idx <= userIntent.numNodes; idx++) {
          NodeDetails node = getDummyNodeDetails(idx, NodeDetails.NodeState.Live, true, enableYSQL);
          universeDetails.nodeDetailsSet.add(node);
        }
        universeDetails.upsertPrimaryCluster(userIntent, null);

        NodeDetails node =
            getDummyNodeDetails(userIntent.numNodes + 1, NodeDetails.NodeState.Removed);
        universeDetails.nodeDetailsSet.add(node);
        universeDetails.nodePrefix = "host";
        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWith1TServer0Masters() {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
        // Add a desired number of nodes.
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        universeDetails.nodeDetailsSet.add(getDummyNodeDetails(0, NodeDetails.NodeState.Live));
        userIntent.numNodes = 1;
        universeDetails.upsertPrimaryCluster(userIntent, null);
        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWithActiveYSQLNode() {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
        PlacementInfo pi = universeDetails.getPrimaryCluster().placementInfo;
        userIntent.enableYSQL = true;
        userIntent.numNodes = 1;
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        universeDetails.nodeDetailsSet.add(
            getDummyNodeDetailsWithPlacement(universeDetails.getPrimaryCluster().uuid));
        universeDetails.upsertPrimaryCluster(userIntent, pi);
        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWithActivePods(
      int numMasters, int numTservers) {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
        PlacementInfo pi = universeDetails.getPrimaryCluster().placementInfo;
        userIntent.enableYSQL = true;
        userIntent.numNodes = 1;
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        universeDetails.nodeDetailsSet.addAll(
            getDummyNodeDetailSet(
                universeDetails.getPrimaryCluster().uuid, numMasters, numTservers));
        universeDetails.upsertPrimaryCluster(userIntent, pi);
        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static Universe.UniverseUpdater mockUniverseUpdaterWithInactiveAndReadReplicaNodes(
      boolean setMasters, int readOnlyNodes) {
    return new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
        // Add a desired number of nodes.
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        userIntent.numNodes = userIntent.replicationFactor;
        UUID primaryClusterUUID = universeDetails.getPrimaryCluster().uuid;
        for (int idx = 1; idx <= userIntent.numNodes; idx++) {
          NodeDetails node =
              getDummyNodeDetails(
                  idx,
                  NodeDetails.NodeState.Live,
                  setMasters && idx <= userIntent.replicationFactor);
          node.placementUuid = primaryClusterUUID;
          universeDetails.nodeDetailsSet.add(node);
        }
        universeDetails.upsertPrimaryCluster(userIntent, null);

        NodeDetails node =
            getDummyNodeDetails(userIntent.numNodes + 1, NodeDetails.NodeState.Removed);
        node.placementUuid = primaryClusterUUID;
        universeDetails.nodeDetailsSet.add(node);
        universeDetails.nodePrefix = "host";

        UUID readonlyClusterUUID = UUID.randomUUID();
        Set<NodeDetails> readReplicaNodesSet =
            getDummyNodeDetailSet(readonlyClusterUUID, 0, readOnlyNodes);
        for (NodeDetails roNode : readReplicaNodesSet) {
          roNode.state = NodeState.Live;
        }

        universeDetails.nodeDetailsSet.addAll(readReplicaNodesSet);
        universeDetails.upsertCluster(userIntent, null, readonlyClusterUUID);

        universe.setUniverseDetails(universeDetails);
      }
    };
  }

  public static UserIntent getDefaultUserIntent(Customer customer) {
    Provider p = ModelFactory.awsProvider(customer);
    return getDefaultUserIntent(p);
  }

  public static UserIntent getDefaultUserIntent(Provider p) {
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UserIntent ui = getTestUserIntent(r, p, i, 3);
    ui.replicationFactor = 3;
    ui.masterGFlags = new HashMap<>();
    ui.tserverGFlags = new HashMap<>();
    return ui;
  }

  public static UserIntent getDefaultUserIntentSingleAZ(Provider p) {
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UserIntent ui = getTestUserIntent(r, p, i, 3);
    ui.replicationFactor = 3;
    ui.masterGFlags = new HashMap<>();
    ui.tserverGFlags = new HashMap<>();
    return ui;
  }

  public static UserIntent getTestUserIntent(Region r, Provider p, InstanceType i, int numNodes) {
    UserIntent ui = new UserIntent();
    ui.regionList = ImmutableList.of(r.uuid);
    ui.provider = p.uuid.toString();
    ui.providerType = Common.CloudType.valueOf(p.code);
    ui.numNodes = numNodes;
    ui.instanceType = i.getInstanceTypeCode();
    return ui;
  }

  public static NodeDetails getDummyNodeDetailsWithPlacement(UUID placementUUID) {
    NodeDetails node = new NodeDetails();
    node.nodeIdx = 1;
    node.placementUuid = placementUUID;
    node.nodeName = "yb-tserver-2";
    node.isMaster = true;
    node.isTserver = true;
    node.cloudInfo = new CloudSpecificInfo();
    node.cloudInfo.private_ip = "1.2.3.4";
    return node;
  }

  public static Set<NodeDetails> getDummyNodeDetailSet(
      UUID placementUUID, int numMasters, int numTservers) {
    Set<NodeDetails> nodeDetailsSet = new HashSet<>();
    int counter = 1;
    for (int i = 0; i < numMasters; i++) {
      NodeDetails node = new NodeDetails();
      node.nodeIdx = counter;
      node.placementUuid = placementUUID;
      node.nodeName = "yb-master-" + i;
      node.isMaster = true;
      node.isTserver = false;
      node.cloudInfo = new CloudSpecificInfo();
      node.cloudInfo.private_ip = "1.2.3.4";
      counter++;
      nodeDetailsSet.add(node);
    }
    for (int i = 0; i < numTservers; i++) {
      NodeDetails node = new NodeDetails();
      node.nodeIdx = counter;
      node.placementUuid = placementUUID;
      node.nodeName = "yb-tserver-" + i;
      node.isMaster = false;
      node.isTserver = true;
      node.cloudInfo = new CloudSpecificInfo();
      node.cloudInfo.private_ip = "1.2.3.4";
      counter++;
      nodeDetailsSet.add(node);
    }
    return nodeDetailsSet;
  }

  public static NodeDetails getDummyNodeDetails(int idx, NodeDetails.NodeState state) {
    return getDummyNodeDetails(idx, state, false /* isMaster */, false);
  }

  private static NodeDetails getDummyNodeDetails(
      int idx, NodeDetails.NodeState state, boolean isMaster) {
    return getDummyNodeDetails(idx, state, isMaster, false);
  }

  private static NodeDetails getDummyNodeDetails(
      int idx, NodeDetails.NodeState state, boolean isMaster, boolean isYSQL) {
    return getDummyNodeDetails(
        idx, state, isMaster, isYSQL, "aws", "test-region", "az-" + idx, "subnet-" + idx);
  }

  public static NodeDetails getDummyNodeDetails(
      int idx,
      NodeDetails.NodeState state,
      boolean isMaster,
      boolean isYSQL,
      String cloud,
      String region,
      String zone,
      String subnet) {
    return getDummyNodeDetails(idx, state, isMaster, isYSQL, cloud, region, zone, subnet, null);
  }

  public static NodeDetails getDummyNodeDetails(
      int idx,
      NodeDetails.NodeState state,
      boolean isMaster,
      boolean isYSQL,
      String cloud,
      String region,
      String zone,
      String subnet,
      UUID azUUID) {
    NodeDetails node = new NodeDetails();
    // TODO: Set nodeName to null for ToBeAdded state
    node.nodeName = "host-n" + idx;
    node.cloudInfo = new CloudSpecificInfo();
    node.cloudInfo.cloud = cloud;
    node.cloudInfo.az = zone;
    node.cloudInfo.region = region;
    node.cloudInfo.subnet_id = subnet;
    node.cloudInfo.private_ip = "host-n" + idx;
    node.cloudInfo.instance_type = UTIL_INST_TYPE;
    node.isTserver = true;
    node.state = state;
    node.isMaster = isMaster;
    if (azUUID != null) {
      node.azUuid = azUUID;
    }
    node.nodeIdx = idx;
    node.isYsqlServer = isYSQL;
    return node;
  }

  public static TableDetails getDummyCollectionsTableDetails(ColumnDetails.YQLDataType dataType) {
    TableDetails table = getDummyTableDetails(1, 0, -1L, SortOrder.NONE);
    ColumnDetails collectionsColumn = new ColumnDetails();
    collectionsColumn.name = "v2";
    collectionsColumn.columnOrder = 2;
    collectionsColumn.type = dataType;
    collectionsColumn.keyType = ColumnDetails.YQLDataType.UUID;
    if (dataType.equals(ColumnDetails.YQLDataType.MAP)) {
      collectionsColumn.valueType = ColumnDetails.YQLDataType.VARCHAR;
    }
    table.columns.add(collectionsColumn);
    return table;
  }

  public static TableDetails getDummyTableDetailsNoClusteringKey(int partitionKeyCount, long ttl) {
    return getDummyTableDetails(partitionKeyCount, 0, ttl, SortOrder.NONE);
  }

  public static TableDetails getDummyTableDetails(
      int partitionKeyCount, int clusteringKeyCount, long ttl, SortOrder sortOrder) {
    TableDetails table = new TableDetails();
    table.tableName = "dummy_table";
    table.keyspace = "dummy_ks";
    table.ttlInSeconds = ttl;
    table.columns = new LinkedList<>();
    for (int i = 0; i < partitionKeyCount + clusteringKeyCount; ++i) {
      ColumnDetails column = new ColumnDetails();
      column.name = "k" + i;
      column.columnOrder = i;
      column.type = ColumnDetails.YQLDataType.INT;
      column.isPartitionKey = i < partitionKeyCount;
      column.isClusteringKey = !column.isPartitionKey;
      if (column.isClusteringKey) {
        column.sortOrder = sortOrder;
      }
      table.columns.add(column);
    }
    ColumnDetails column = new ColumnDetails();
    column.name = "v";
    column.columnOrder = partitionKeyCount + clusteringKeyCount;
    column.type = ColumnDetails.YQLDataType.VARCHAR;
    column.isPartitionKey = false;
    column.isClusteringKey = false;
    table.columns.add(column);
    return table;
  }

  public static DeviceInfo getDummyDeviceInfo(int numVolumes, int volumeSize) {
    DeviceInfo deviceInfo = new DeviceInfo();
    deviceInfo.numVolumes = numVolumes;
    deviceInfo.volumeSize = volumeSize;
    return deviceInfo;
  }

  public static UserIntent getDummyUserIntent(
      DeviceInfo deviceInfo, Provider provider, String instanceType) {
    UserIntent userIntent = new UserIntent();
    userIntent.provider = provider.uuid.toString();
    userIntent.providerType = Common.CloudType.valueOf(provider.code);
    userIntent.instanceType = instanceType;
    userIntent.deviceInfo = deviceInfo;
    return userIntent;
  }
}
