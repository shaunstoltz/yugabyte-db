// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.common.*;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;
import org.yb.Common;
import org.yb.client.ChangeMasterClusterConfigResponse;
import org.yb.client.ListTabletServersResponse;
import org.yb.client.YBClient;
import org.yb.client.YBTable;
import play.libs.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.yugabyte.yw.commissioner.tasks.subtasks
                 .KubernetesCommandExecutor.CommandType.CREATE_NAMESPACE;
import static com.yugabyte.yw.commissioner.tasks.subtasks
                 .KubernetesCommandExecutor.CommandType.APPLY_SECRET;
import static com.yugabyte.yw.commissioner.tasks.subtasks
                 .KubernetesCommandExecutor.CommandType.HELM_INSTALL;
import static com.yugabyte.yw.commissioner.tasks.subtasks
                 .KubernetesCommandExecutor.CommandType.POD_INFO;
import static com.yugabyte.yw.commissioner.tasks.subtasks
                 .KubernetesCheckNumPod.CommandType.WAIT_FOR_PODS;
import static com.yugabyte.yw.common.ApiUtils.getTestUserIntent;
import static com.yugabyte.yw.common.AssertHelper.assertJsonEqual;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.models.TaskInfo.State.Failure;
import static com.yugabyte.yw.models.TaskInfo.State.Success;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@RunWith(MockitoJUnitRunner.class)
public class CreateKubernetesUniverseTest extends CommissionerBaseTest {

  @InjectMocks
  Commissioner commissioner;

  Universe defaultUniverse;

  YBClient mockClient;

  String nodePrefix = "demo-universe";
  String nodePrefix1, nodePrefix2, nodePrefix3;
  String ns, ns1, ns2, ns3;

  Map<String, String> config= new HashMap<String, String>();
  Map<String, String> config1 = new HashMap<String, String>();
  Map<String, String> config2 = new HashMap<String, String>();
  Map<String, String> config3 = new HashMap<String, String>();

  private void setupUniverseMultiAZ(boolean setMasters, boolean enabledYEDIS,
                                    boolean setNamespace) {
    Region r = Region.create(defaultProvider, "region-1", "PlacementRegion-1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.create(r, "az-1", "PlacementAZ-1", "subnet-1");
    AvailabilityZone az2 = AvailabilityZone.create(r, "az-2", "PlacementAZ-2", "subnet-2");
    AvailabilityZone az3 = AvailabilityZone.create(r, "az-3", "PlacementAZ-3", "subnet-3");
    InstanceType i = InstanceType.upsert(defaultProvider.uuid, "c3.xlarge",
        10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, defaultProvider, i, 3);
    userIntent.replicationFactor = 3;
    userIntent.masterGFlags = new HashMap<>();
    userIntent.tserverGFlags = new HashMap<>();
    userIntent.universeName = "demo-universe";
    userIntent.ybSoftwareVersion = "1.0.0";
    userIntent.enableYEDIS = enabledYEDIS;
    defaultUniverse = createUniverse(defaultCustomer.getCustomerId());
    Universe.saveDetails(defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(userIntent, nodePrefix, setMasters /* setMasters */));
    defaultUniverse = Universe.get(defaultUniverse.universeUUID);
    defaultUniverse.setConfig(ImmutableMap.of(Universe.HELM2_LEGACY,
                                              Universe.HelmLegacy.V3.toString()));
    nodePrefix1 = String.format("%s-%s", nodePrefix, az1.code);
    nodePrefix2 = String.format("%s-%s", nodePrefix, az2.code);
    nodePrefix3 = String.format("%s-%s", nodePrefix, az3.code);
    ns1 = nodePrefix1;
    ns2 = nodePrefix2;
    ns3 = nodePrefix3;

    if (setNamespace) {
      ns1 = "demo-ns-1";
      ns2 = "demons2";

      config1.put("KUBECONFIG", "test-kc-" + 1);
      config2.put("KUBECONFIG", "test-kc-" + 2);
      config3.put("KUBECONFIG", "test-kc-" + 3);

      config1.put("KUBENAMESPACE", ns1);
      config2.put("KUBENAMESPACE", ns2);

      az1.setConfig(config1);
      az2.setConfig(config2);
      az3.setConfig(config3);
    } else {
      config.put("KUBECONFIG", "test");
      defaultProvider.setConfig(config);
      defaultProvider.save();

      // Copying provider config
      config1.putAll(config);
      config2.putAll(config);
      config3.putAll(config);
    }

    String podInfosMessage =
        "{\"items\": [{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\"," +
            " \"podIP\": \"123.456.78.90\"}, \"spec\": {\"hostname\": \"yb-master-0\"}," +
            " \"metadata\": {\"namespace\": \"%1$s\"}}," +
        "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", " +
            "\"podIP\": \"123.456.78.91\"}, \"spec\": {\"hostname\": \"yb-tserver-0\"}," +
            " \"metadata\": {\"namespace\": \"%1$s\"}}]}";
    ShellResponse shellResponse1 =
        ShellResponse.create(0, String.format(podInfosMessage, ns1));
    when(mockKubernetesManager.getPodInfos(any(), eq(nodePrefix1), eq(ns1))).thenReturn(shellResponse1);
    ShellResponse shellResponse2 =
        ShellResponse.create(0, String.format(podInfosMessage, ns2));
    when(mockKubernetesManager.getPodInfos(any(), eq(nodePrefix2), eq(ns2))).thenReturn(shellResponse2);
    ShellResponse shellResponse3 =
        ShellResponse.create(0, String.format(podInfosMessage, ns3));
    when(mockKubernetesManager.getPodInfos(any(), eq(nodePrefix3), eq(ns3))).thenReturn(shellResponse3);
  }

  private void setupUniverse(boolean setMasters, boolean enabledYEDIS, boolean setNamespace) {
    Region r = Region.create(defaultProvider, "region-1", "PlacementRegion-1", "default-image");
    AvailabilityZone az = AvailabilityZone.create(r, "az-1", "PlacementAZ-1", "subnet-1");
    InstanceType i = InstanceType.upsert(defaultProvider.uuid, "c3.xlarge",
        10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, defaultProvider, i, 3);
    userIntent.replicationFactor = 3;
    userIntent.masterGFlags = new HashMap<>();
    userIntent.tserverGFlags = new HashMap<>();
    userIntent.universeName = "demo-universe";
    userIntent.ybSoftwareVersion = "1.0.0";
    userIntent.enableYEDIS = enabledYEDIS;
    defaultUniverse = createUniverse(defaultCustomer.getCustomerId());
    Universe.saveDetails(defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(userIntent, nodePrefix, setMasters /* setMasters */));
    defaultUniverse = Universe.get(defaultUniverse.universeUUID);
    defaultUniverse.setConfig(ImmutableMap.of(Universe.HELM2_LEGACY,
                                              Universe.HelmLegacy.V3.toString()));

    ns = nodePrefix;
    if (setNamespace) {
      ns = "demo-ns";
      config.put("KUBECONFIG", "test-kc");
      config.put("KUBENAMESPACE", ns);
      az.setConfig(config);
    } else {
      config.put("KUBECONFIG", "test");
      defaultProvider.setConfig(config);
      defaultProvider.save();
    }

    ShellResponse response = new ShellResponse();
    response.message =
        "{\"items\": [{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", " +
            "\"podIP\": \"1.2.3.1\"}, \"spec\": {\"hostname\": \"yb-master-0\"}," +
            " \"metadata\": {\"namespace\": \"" + ns + "\"}}," +
            "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", " +
            "\"podIP\": \"1.2.3.2\"}, \"spec\": {\"hostname\": \"yb-tserver-0\"}," +
            " \"metadata\": {\"namespace\": \"" + ns + "\"}}," +
            "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", " +
            "\"podIP\": \"1.2.3.3\"}, \"spec\": {\"hostname\": \"yb-master-1\"}," +
            " \"metadata\": {\"namespace\": \"" + ns + "\"}}," +
            "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", " +
            "\"podIP\": \"1.2.3.4\"}, \"spec\": {\"hostname\": \"yb-tserver-1\"}," +
            " \"metadata\": {\"namespace\": \"" + ns + "\"}}," +
            "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", " +
            "\"podIP\": \"1.2.3.5\"}, \"spec\": {\"hostname\": \"yb-master-2\"}," +
            " \"metadata\": {\"namespace\": \"" + ns + "\"}}," +
            "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", " +
            "\"podIP\": \"1.2.3.6\"}, \"spec\": {\"hostname\": \"yb-tserver-2\"}," +
            " \"metadata\": {\"namespace\": \"" + ns + "\"}}]}";
    when(mockKubernetesManager.getPodInfos(any(), any(), any())).thenReturn(response);
  }

  private void setupCommon() {
    ShellResponse response = new ShellResponse();
    when(mockKubernetesManager.createNamespace(anyMap(), any())).thenReturn(response);
    when(mockKubernetesManager.helmInstall(anyMap(), any(), any(), any(), any())).thenReturn(response);
    // Table RPCs.
    mockClient = mock(YBClient.class);
    // WaitForTServerHeartBeats mock.
    ListTabletServersResponse mockResponse = mock(ListTabletServersResponse.class);
    when(mockResponse.getTabletServersCount()).thenReturn(3);
    when(mockClient.waitForServer(any(), anyLong())).thenReturn(true);
    when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
    YBTable mockTable = mock(YBTable.class);
    when(mockTable.getName()).thenReturn("redis");
    when(mockTable.getTableType()).thenReturn(Common.TableType.REDIS_TABLE_TYPE);
    ChangeMasterClusterConfigResponse ccr = new ChangeMasterClusterConfigResponse(1111, "", null);
    try {
      when(mockClient.changeMasterClusterConfig(any())).thenReturn(ccr);
      when(mockClient.listTabletServers()).thenReturn(mockResponse);
      when(mockClient.createRedisTable(any())).thenReturn(mockTable);
    } catch (Exception e) {}
    // WaitForServer mock.
    mockWaits(mockClient);
  }

  List<TaskType> KUBERNETES_CREATE_UNIVERSE_TASKS = ImmutableList.of(
      TaskType.KubernetesCommandExecutor,
      TaskType.KubernetesCommandExecutor,
      TaskType.KubernetesCommandExecutor,
      TaskType.KubernetesCheckNumPod,
      TaskType.KubernetesCommandExecutor,
      TaskType.WaitForServer,
      TaskType.WaitForMasterLeader,
      TaskType.UpdatePlacementInfo,
      TaskType.WaitForTServerHeartBeats,
      TaskType.SwamperTargetsFileUpdate,
      TaskType.CreateTable,
      TaskType.UniverseUpdateSucceeded);

  private static final ImmutableMap<String, String> EXPECTED_RESULT_FOR_CREATE_TABLE_TASK =
      ImmutableMap.of("tableType", "REDIS_TABLE_TYPE", "tableName", "redis");

  // Cannot use defaultUniverse.universeUUID in a class field.
  List<JsonNode> getExpectedCreateUniverseTaskResults() {
    return ImmutableList.of(
      Json.toJson(ImmutableMap.of("commandType", CREATE_NAMESPACE.name())),
      Json.toJson(ImmutableMap.of("commandType", APPLY_SECRET.name())),
      Json.toJson(ImmutableMap.of("commandType", HELM_INSTALL.name())),
      Json.toJson(ImmutableMap.of("commandType", WAIT_FOR_PODS.name())),
      Json.toJson(ImmutableMap.of("commandType", POD_INFO.name())),
      Json.toJson(ImmutableMap.of()),
      Json.toJson(ImmutableMap.of()),
      Json.toJson(ImmutableMap.of()),
      Json.toJson(ImmutableMap.of()),
      Json.toJson(ImmutableMap.of("removeFile", false)),
      Json.toJson(EXPECTED_RESULT_FOR_CREATE_TABLE_TASK),
      Json.toJson(ImmutableMap.of())
    );
  }

  List<Integer> getTaskPositionsToSkip(boolean skipNamespace) {
    // 3 is WAIT_FOR_PODS of type KubernetesCheckNumPod task.
    // 0 is CREATE_NAMESPACE of type KubernetesCommandExecutor
    return skipNamespace ? ImmutableList.of(0, 3) : ImmutableList.of(3);
  }

  List<Integer> getTaskCountPerPosition(int namespaceTasks, int parallelTasks) {
    return ImmutableList.of(
      namespaceTasks,
      parallelTasks,
      parallelTasks,
      0,
      1,
      3,
      1,
      1,
      1,
      1,
      1,
      1);
  }

  private void assertTaskSequence(Map<Integer, List<TaskInfo>> subTasksByPosition, int numTasks) {
    assertTaskSequence(subTasksByPosition,
                       KUBERNETES_CREATE_UNIVERSE_TASKS, getExpectedCreateUniverseTaskResults(),
                       getTaskPositionsToSkip(/* skip namespace task */ false),
                       getTaskCountPerPosition(numTasks, numTasks));
  }

  private void assertTaskSequence(Map<Integer, List<TaskInfo>> subTasksByPosition,
                                  List<TaskType> expectedTasks, List<JsonNode> expectedTasksResult,
                                  List<Integer> taskPositionsToSkip,
                                  List<Integer> taskCountPerPosition) {
    int position = 0;
    for (TaskType taskType: expectedTasks) {
      if (taskPositionsToSkip.contains(position)) {
        position++;
        continue;
      }

      List<TaskInfo> tasks = subTasksByPosition.get(position);
      JsonNode expectedResults = expectedTasksResult.get(position);
      List<JsonNode> taskDetails = tasks.stream()
          .map(t -> t.getTaskDetails())
          .collect(Collectors.toList());
      int expectedSize = taskCountPerPosition.get(position);
      assertEquals(expectedSize, tasks.size());
      assertEquals(taskType, tasks.get(0).getTaskType());
      assertJsonEqual(expectedResults, taskDetails.get(0));
      position++;
    }
  }


  private TaskInfo submitTask(UniverseDefinitionTaskParams taskParams) {
    taskParams.universeUUID = defaultUniverse.universeUUID;
    taskParams.nodePrefix = "demo-universe";
    taskParams.expectedUniverseVersion = 2;
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.nodeDetailsSet = defaultUniverse.getUniverseDetails().nodeDetailsSet;

    try {
      UUID taskUUID = commissioner.submit(TaskType.CreateKubernetesUniverse, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  @Test
  public void testCreateKubernetesUniverseSuccessMultiAZ() {
    testCreateKubernetesUniverseSuccessMultiAZBase(false);
  }

  @Test
  public void testCreateKubernetesUniverseSuccessMultiAZWithNamespace() {
    testCreateKubernetesUniverseSuccessMultiAZBase(true);
  }

  private void testCreateKubernetesUniverseSuccessMultiAZBase(boolean setNamespace) {
    setupUniverseMultiAZ(/* Create Masters */ false, /* YEDIS/REDIS enabled */ true, setNamespace);
    setupCommon();

    ArgumentCaptor<String> expectedOverrideFile = ArgumentCaptor.forClass(String.class);
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    TaskInfo taskInfo = submitTask(taskParams);

    if (setNamespace) {
      verify(mockKubernetesManager, times(0)).createNamespace(config1, ns1);
      verify(mockKubernetesManager, times(0)).createNamespace(config2, ns2);
    } else {
      verify(mockKubernetesManager, times(1)).createNamespace(config1, ns1);
      verify(mockKubernetesManager, times(1)).createNamespace(config2, ns2);
    }

    verify(mockKubernetesManager, times(1)).createNamespace(config3, ns3);

    verify(mockKubernetesManager, times(1)).helmInstall(eq(config1), eq(defaultProvider.uuid),
        eq(nodePrefix1), eq(ns1), expectedOverrideFile.capture());
    verify(mockKubernetesManager, times(1)).helmInstall(eq(config2), eq(defaultProvider.uuid),
        eq(nodePrefix2), eq(ns2), expectedOverrideFile.capture());
    verify(mockKubernetesManager, times(1)).helmInstall(eq(config3), eq(defaultProvider.uuid),
        eq(nodePrefix3), eq(ns3), expectedOverrideFile.capture());

    String overrideFileRegex = "(.*)" + defaultUniverse.universeUUID + "(.*).yml";
    assertThat(expectedOverrideFile.getValue(), RegexMatcher.matchesRegex(overrideFileRegex));

    verify(mockKubernetesManager, times(1)).getPodInfos(config1, nodePrefix1, ns1);
    verify(mockKubernetesManager, times(1)).getPodInfos(config2, nodePrefix2, ns2);
    verify(mockKubernetesManager, times(1)).getPodInfos(config3, nodePrefix3, ns3);
    verify(mockSwamperHelper, times(1)).writeUniverseTargetJson(defaultUniverse.universeUUID);

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    int numNamespaces = setNamespace ? 1 : 3;
    assertTaskSequence(subTasksByPosition,
                       KUBERNETES_CREATE_UNIVERSE_TASKS, getExpectedCreateUniverseTaskResults(),
                       getTaskPositionsToSkip(/* skip namespace task */ false),
                       getTaskCountPerPosition(numNamespaces, 3));
    assertEquals(Success, taskInfo.getTaskState());
  }

  @Test
  public void testCreateKubernetesUniverseSuccessSingleAZ() {
    testCreateKubernetesUniverseSuccessSingleAZBase(false);
  }

  @Test
  public void testCreateKubernetesUniverseSuccessSingleAZWithNamespace() {
    testCreateKubernetesUniverseSuccessSingleAZBase(true);
  }

  private void testCreateKubernetesUniverseSuccessSingleAZBase(boolean setNamespace) {
    setupUniverse(/* Create Masters */ false, /* YEDIS/REDIS enabled */ true, setNamespace);
    setupCommon();
    ArgumentCaptor<String> expectedOverrideFile = ArgumentCaptor.forClass(String.class);
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    TaskInfo taskInfo = submitTask(taskParams);

    if (setNamespace) {
      verify(mockKubernetesManager, times(0)).createNamespace(config, ns);
    } else {
      verify(mockKubernetesManager, times(1)).createNamespace(config, ns);
    }

    verify(mockKubernetesManager, times(1)).helmInstall(eq(config), eq(defaultProvider.uuid),
                                                        eq(nodePrefix), eq(ns),
                                                        expectedOverrideFile.capture());

    String overrideFileRegex = "(.*)" + defaultUniverse.universeUUID + "(.*).yml";
    assertThat(expectedOverrideFile.getValue(), RegexMatcher.matchesRegex(overrideFileRegex));

    verify(mockKubernetesManager, times(1)).getPodInfos(config, nodePrefix, ns);
    verify(mockSwamperHelper, times(1)).writeUniverseTargetJson(defaultUniverse.universeUUID);

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    int numNamespaces = setNamespace ? 0 : 1;
    assertTaskSequence(subTasksByPosition,
                       KUBERNETES_CREATE_UNIVERSE_TASKS, getExpectedCreateUniverseTaskResults(),
                       getTaskPositionsToSkip(/* skip namespace task */ setNamespace),
                       getTaskCountPerPosition(numNamespaces, 1));
    assertEquals(Success, taskInfo.getTaskState());
  }

  @Test
  public void testCreateKubernetesUniverseFailure() {
    setupUniverse(/* Create Masters */ true, /* YEDIS/REDIS enabled */ true,
                  /* set namespace */ false);
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Failure, taskInfo.getTaskState());
  }

  @Test
  public void testCreateKubernetesUniverseMultiAZWithoutYedis() {
    setupUniverseMultiAZ(/* Create Masters */ false, /* YEDIS/REDIS disabled */ false,
                         /* set namespace */ false);
    testCreateKubernetesUniverseSubtasksWithoutYedis(3);
  }

  @Test
  public void testCreateKubernetesUniverseSingleAZWithoutYedis() {
    setupUniverse(/* Create Masters */ false, /* YEDIS/REDIS disabled */ false,
                  /* set namespace */ false);
    testCreateKubernetesUniverseSubtasksWithoutYedis(1);
  }

  private void testCreateKubernetesUniverseSubtasksWithoutYedis(int tasksNum) {
    setupCommon();
    TaskInfo taskInfo = submitTask(new UniverseDefinitionTaskParams());

    List<TaskType> createUniverseTasks = new ArrayList<>(KUBERNETES_CREATE_UNIVERSE_TASKS);
    int createTableTaskIndex = createUniverseTasks.indexOf(TaskType.CreateTable);
    createUniverseTasks.remove(TaskType.CreateTable);

    List<JsonNode> expectedResults = new ArrayList<>(getExpectedCreateUniverseTaskResults());
    expectedResults.remove(Json.toJson(EXPECTED_RESULT_FOR_CREATE_TABLE_TASK));

    List<Integer> taskCountPerPosition =
        new ArrayList<>(getTaskCountPerPosition(tasksNum, tasksNum));
    taskCountPerPosition.remove(createTableTaskIndex);

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertTaskSequence(subTasksByPosition,
                       createUniverseTasks, expectedResults,
                       getTaskPositionsToSkip(/* skip namespace task */ false),
                       taskCountPerPosition);
    assertEquals(Success, taskInfo.getTaskState());
  }
}
