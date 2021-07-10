/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.cloud.PublicCloudConstants;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.tasks.UpgradeUniverse;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.forms.*;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import play.libs.Json;
import play.mvc.Result;

import java.io.File;
import java.util.*;

import static com.yugabyte.yw.common.ApiUtils.getTestUserIntent;
import static com.yugabyte.yw.common.AssertHelper.*;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthToken;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthTokenAndBody;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.common.PlacementInfoUtil.getAzUuidToNumNodes;
import static com.yugabyte.yw.common.PlacementInfoUtil.updateUniverseDefinition;
import static com.yugabyte.yw.forms.UniverseConfigureTaskParams.ClusterOperationType.CREATE;
import static junit.framework.TestCase.assertFalse;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

@RunWith(JUnitParamsRunner.class)
public class UniverseUiOnlyControllerTest extends UniverseControllerTestBase {

  @Test
  @Parameters({
    "FakeUniverse, 0",
    "TestUniverse, 1",
  })
  public void testFind(String name, int expected) {
    Universe u = createUniverse("TestUniverse", customer.getCustomerId());
    String url = "/api/customers/" + customer.uuid + "/universes/find?name=" + name;
    Result result = doRequestWithAuthToken("GET", url, authToken);

    JsonNode json = Json.parse(contentAsString(result));
    assertTrue(json.isArray());
    assertEquals(expected, json.size());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseCreateWithInvalidParams() {
    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, Json.newObject()));
    assertBadRequest(result, "clusters: This field is required");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseCreateWithInvalidUniverseName() {
    String url = "/api/customers/" + customer.uuid + "/universes";
    Provider p = ModelFactory.awsProvider(customer);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "Foo_Bar")
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString());
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "Invalid universe name format, valid characters [a-zA-Z0-9-].");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseCreateWithoutAvailabilityZone() {
    Provider p = ModelFactory.awsProvider(customer);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "SingleUserUniverse")
            .put("instanceType", "a-instance")
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString());
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.put("currentClusterType", "PRIMARY");
    bodyJson.put("clusterOperation", "CREATE");

    String url = "/api/customers/" + customer.uuid + "/universe_configure";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertInternalServerError(result, "No AZ found across regions: [" + r.uuid + "]");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseCreateWithSingleAvailabilityZones() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "SingleUserUniverse")
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    userIntentJson.set("deviceInfo", createValidDeviceInfo(Common.CloudType.aws));
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertNotNull(json.get("universeUUID"));
    assertNotNull(json.get("universeDetails"));
    assertNotNull(json.get("universeConfig"));
    // setTxnTableWaitCountFlag will be false as enableYSQL is false in this case
    assertFalse(json.get("universeDetails").get("setTxnTableWaitCountFlag").asBoolean());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("SingleUserUniverse")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Create)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseCreateWithYsqlEnabled() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "SingleUserUniverse")
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode)
            .put("enableYSQL", "true");
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    userIntentJson.set("deviceInfo", createValidDeviceInfo(Common.CloudType.aws));
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertNotNull(json.get("universeUUID"));
    assertNotNull(json.get("universeDetails"));
    assertNotNull(json.get("universeConfig"));
    // setTxnTableWaitCountFlag should be true as enableYSQL is true in this case
    assertTrue(json.get("universeDetails").get("setTxnTableWaitCountFlag").asBoolean());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("SingleUserUniverse")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Create)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  @Parameters({
    "true, false, false, false, false",
    "true, true, false, true, false",
    "true, false, true, true, true",
    "true, true, true, true, true",
    "false, false, true, false, true",
    "false, true, true, true, true",
  })
  public void testUniverseCreateForSelfSignedTLS(
      boolean rootAndClientRootCASame,
      boolean enableNodeToNodeEncrypt,
      boolean enableClientToNodeEncrypt,
      boolean rootCAExists,
      boolean clientRootCAExists) {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "SingleUserUniverse")
            .put("instanceType", i.getInstanceTypeCode())
            .put("enableNodeToNodeEncrypt", enableNodeToNodeEncrypt)
            .put("enableClientToNodeEncrypt", enableClientToNodeEncrypt)
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);

    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    userIntentJson.set("deviceInfo", createValidDeviceInfo(Common.CloudType.aws));
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    ObjectNode bodyJson = Json.newObject().put("nodePrefix", "demo-node");
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());
    bodyJson.put("rootAndClientRootCASame", rootAndClientRootCASame);

    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    ArgumentCaptor<UniverseTaskParams> taskParams =
        ArgumentCaptor.forClass(UniverseTaskParams.class);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());
    verify(mockCommissioner).submit(eq(TaskType.CreateUniverse), taskParams.capture());
    UniverseDefinitionTaskParams taskParam = (UniverseDefinitionTaskParams) taskParams.getValue();
    if (rootCAExists) assertNotNull(taskParam.rootCA);
    else assertNull(taskParam.rootCA);
    if (clientRootCAExists) assertNotNull(taskParam.clientRootCA);
    else assertNull(taskParam.clientRootCA);
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testK8sUniverseCreateOneClusterPerNamespacedProviderFailure() {
    Provider p = ModelFactory.kubernetesProvider(customer);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    az1.updateConfig(ImmutableMap.of("KUBENAMESPACE", "test-ns1"));
    InstanceType i =
        InstanceType.upsert(p.uuid, "small", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ModelFactory.createUniverse(
        "K8sUniverse1", customer.getCustomerId(), Common.CloudType.kubernetes);

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "K8sUniverse2")
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString());
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    userIntentJson.set("deviceInfo", createValidDeviceInfo(Common.CloudType.kubernetes));
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(
        result,
        "Only one universe can be created with providers having "
            + "KUBENAMESPACE set in the AZ config.");
  }

  @Test
  public void testUniverseCreateWithDisabledYedis() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "SingleUserUniverse")
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("enableYEDIS", "false")
            .put("provider", p.uuid.toString());
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    userIntentJson.put("accessKeyCode", accessKeyCode);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    userIntentJson.set("deviceInfo", createValidDeviceInfo(Common.CloudType.aws));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertOk(result);

    JsonNode json = Json.parse(contentAsString(result));
    assertNotNull(json.get("universeUUID"));
    assertNotNull(json.get("universeConfig"));

    JsonNode universeDetails = json.get("universeDetails");
    assertNotNull(universeDetails);
    JsonNode clustersJson = universeDetails.get("clusters");
    assertNotNull(clustersJson);
    JsonNode primaryClusterJson = clustersJson.get(0);
    assertNotNull(primaryClusterJson);
    JsonNode userIntentJsonNode = primaryClusterJson.get("userIntent");
    assertNotNull(userIntentJsonNode);

    assertEquals("false", userIntentJsonNode.get("enableYEDIS").toString());
  }

  @Test
  @Parameters(method = "parametersToDeviceInfoValidation")
  public void testUniverseCreateDeviceInfoValidation(
      Common.CloudType cloudType,
      String instanceType,
      PublicCloudConstants.StorageType storageType,
      Integer numVolumes,
      Integer volumeSize,
      Integer diskIops,
      Integer throughput,
      String mountPoints,
      String errorMessage) {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p;
    switch (cloudType) {
      case aws:
        p = ModelFactory.awsProvider(customer);
        break;
      case gcp:
        p = ModelFactory.gcpProvider(customer);
        break;
      case azu:
        p = ModelFactory.azuProvider(customer);
        break;
      case kubernetes:
        p = ModelFactory.kubernetesProvider(customer);
        break;
      case onprem:
        p = ModelFactory.onpremProvider(customer);
        break;
      case other:
        p = ModelFactory.newProvider(customer, Common.CloudType.other);
        break;
      default:
        throw new UnsupportedOperationException();
    }
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    InstanceType i =
        InstanceType.upsert(p.uuid, instanceType, 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "SingleUserUniverse")
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    ObjectNode deviceInfo =
        createDeviceInfo(storageType, numVolumes, volumeSize, diskIops, throughput, mountPoints);
    if (deviceInfo.fields().hasNext()) {
      userIntentJson.set("deviceInfo", deviceInfo);
    }
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes";
    if (errorMessage == null) {
      Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
      assertOk(result);
    } else {
      Result result =
          assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
      assertBadRequest(result, errorMessage);
    }
  }

  @SuppressWarnings("unused")
  private Object[] parametersToDeviceInfoValidation() {
    return new Object[][] {
      // Success cases
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP2,
        1,
        100,
        null,
        null,
        null,
        null
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.IO1,
        1,
        100,
        1000,
        null,
        null,
        null
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP3,
        1,
        100,
        1000,
        125,
        null,
        null
      },
      {Common.CloudType.aws, "i3.2xlarge", null, 1, 100, 1000, 125, null, null},
      {Common.CloudType.aws, "c5d.2xlarge", null, 1, 100, 1000, 125, null, null},
      {
        Common.CloudType.gcp,
        "c3.xlarge",
        PublicCloudConstants.StorageType.Persistent,
        1,
        100,
        null,
        null,
        null,
        null
      },
      {
        Common.CloudType.gcp,
        "c3.xlarge",
        PublicCloudConstants.StorageType.Scratch,
        1,
        100,
        null,
        null,
        null,
        null
      },
      {
        Common.CloudType.azu,
        "c3.xlarge",
        PublicCloudConstants.StorageType.StandardSSD_LRS,
        1,
        100,
        null,
        null,
        null,
        null
      },
      {
        Common.CloudType.azu,
        "c3.xlarge",
        PublicCloudConstants.StorageType.Premium_LRS,
        1,
        100,
        null,
        null,
        null,
        null
      },
      {
        Common.CloudType.azu,
        "c3.xlarge",
        PublicCloudConstants.StorageType.UltraSSD_LRS,
        1,
        100,
        null,
        null,
        null,
        null
      },
      {Common.CloudType.kubernetes, "c3.xlarge", null, 1, 100, null, null, null, null},
      {Common.CloudType.onprem, "c3.xlarge", null, 1, 100, null, null, "/var", null},
      {Common.CloudType.other, "c3.xlarge", null, null, null, null, null, null, null},

      //  Failure cases
      {
        Common.CloudType.aws,
        "c3.xlarge",
        null,
        null,
        null,
        null,
        null,
        null,
        "deviceInfo can't be empty for universe on aws provider"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        null,
        1,
        100,
        null,
        null,
        null,
        "storageType can't be empty for universe on aws provider"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP2,
        null,
        100,
        null,
        null,
        null,
        "Number of volumes field is mandatory"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP2,
        1,
        null,
        null,
        null,
        null,
        "Volume size field is mandatory"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.Persistent,
        1,
        100,
        null,
        null,
        null,
        "Cloud type aws is not compatible with storage type Persistent"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.IO1,
        1,
        100,
        null,
        null,
        null,
        "Disk IOPS is mandatory for IO1 storage"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP3,
        1,
        100,
        null,
        125,
        null,
        "Disk IOPS is mandatory for GP3 storage"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP3,
        1,
        100,
        1000,
        null,
        null,
        "Disk throughput is mandatory for GP3 storage"
      },
      {
        Common.CloudType.aws,
        "i3.2xlarge",
        PublicCloudConstants.StorageType.GP2,
        1,
        100,
        null,
        null,
        null,
        "AWS instance with ephemeral storage can't have storageType set"
      },
      {
        Common.CloudType.aws,
        "c5d.2xlarge",
        PublicCloudConstants.StorageType.GP2,
        1,
        100,
        null,
        null,
        null,
        "AWS instance with ephemeral storage can't have storageType set"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP2,
        1,
        -100,
        null,
        null,
        null,
        "Volume size should be positive"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP2,
        -1,
        100,
        null,
        null,
        null,
        "Number of volumes should be positive"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP3,
        1,
        100,
        -1,
        125,
        null,
        "Disk IOPS should be positive"
      },
      {
        Common.CloudType.aws,
        "c3.xlarge",
        PublicCloudConstants.StorageType.GP3,
        1,
        100,
        1000,
        -1,
        null,
        "Disk throughput should be positive"
      },
      {
        Common.CloudType.gcp,
        "c3.xlarge",
        PublicCloudConstants.StorageType.Persistent,
        null,
        100,
        null,
        null,
        null,
        "Number of volumes field is mandatory"
      },
      {
        Common.CloudType.gcp,
        "c3.xlarge",
        PublicCloudConstants.StorageType.Scratch,
        1,
        null,
        null,
        null,
        null,
        "Volume size field is mandatory"
      },
      {
        Common.CloudType.azu,
        "c3.xlarge",
        PublicCloudConstants.StorageType.StandardSSD_LRS,
        null,
        100,
        null,
        null,
        null,
        "Number of volumes field is mandatory"
      },
      {
        Common.CloudType.azu,
        "c3.xlarge",
        PublicCloudConstants.StorageType.Premium_LRS,
        1,
        null,
        null,
        null,
        null,
        "Volume size field is mandatory"
      },
      {
        Common.CloudType.kubernetes,
        "c3.xlarge",
        null,
        null,
        100,
        null,
        null,
        null,
        "Number of volumes field is mandatory"
      },
      {
        Common.CloudType.kubernetes,
        "c3.xlarge",
        null,
        1,
        null,
        null,
        null,
        null,
        "Volume size field is mandatory"
      },
      {
        Common.CloudType.onprem,
        "c3.xlarge",
        null,
        null,
        100,
        null,
        null,
        "/var",
        "Number of volumes field is mandatory"
      },
      {
        Common.CloudType.onprem,
        "c3.xlarge",
        null,
        1,
        null,
        null,
        null,
        "/var",
        "Volume size field is mandatory"
      },
      {
        Common.CloudType.onprem,
        "c3.xlarge",
        null,
        1,
        100,
        null,
        null,
        null,
        "Mount points are mandatory for onprem cluster"
      },
    };
  }

  @Test
  public void testCustomConfigureCreateWithMultiAZMultiRegion() {
    Provider p = ModelFactory.awsProvider(customer);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    UniverseConfigureTaskParams taskParams = new UniverseConfigureTaskParams();
    taskParams.nodePrefix = "univConfCreate";
    taskParams.upsertPrimaryCluster(getTestUserIntent(r, p, i, 5), null);
    taskParams.clusterOperation = CREATE;
    PlacementInfoUtil.updateUniverseDefinition(
        taskParams, customer.getCustomerId(), taskParams.getPrimaryCluster().uuid);
    UniverseDefinitionTaskParams.Cluster primaryCluster = taskParams.getPrimaryCluster();
    // Needed for the universe_resources call.
    DeviceInfo di = new DeviceInfo();
    di.volumeSize = 100;
    di.numVolumes = 2;
    primaryCluster.userIntent.deviceInfo = di;

    List<PlacementInfo.PlacementAZ> azList =
        primaryCluster.placementInfo.cloudList.get(0).regionList.get(0).azList;
    assertEquals(azList.size(), 3);

    PlacementInfo.PlacementAZ paz = azList.get(0);
    paz.numNodesInAZ += 2;
    primaryCluster.userIntent.numNodes += 2;
    final String url = "/api/customers/" + customer.uuid + "/universe_configure";

    ObjectNode noCurClusterTypeJson = (ObjectNode) Json.toJson(taskParams);
    noCurClusterTypeJson.put("clusterOperation", "CREATE");
    noCurClusterTypeJson.put("currentClusterType", "");

    Result result =
        assertYWSE(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, noCurClusterTypeJson));
    assertBadRequest(result, "currentClusterType");

    ObjectNode noCurClusterOp = (ObjectNode) Json.toJson(taskParams);
    noCurClusterOp.remove("clusterOperation");

    result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, noCurClusterOp));
    assertBadRequest(result, "clusterOperation");

    ObjectNode topJson = (ObjectNode) Json.toJson(taskParams);
    topJson.put("currentClusterType", "PRIMARY");
    topJson.put("clusterOperation", "CREATE");

    result = doRequestWithAuthTokenAndBody("POST", url, authToken, topJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertTrue(json.get("nodeDetailsSet").isArray());
    ArrayNode nodeDetailJson = (ArrayNode) json.get("nodeDetailsSet");
    assertEquals(7, nodeDetailJson.size());
    // Now test the resource endpoint also works.
    // TODO: put this in its own test once we refactor the provider+region+az creation and payload
    // generation...
    result =
        doRequestWithAuthTokenAndBody(
            "POST", "/api/customers/" + customer.uuid + "/universe_resources", authToken, topJson);
    assertOk(result);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testConfigureCreateWithReadOnlyClusters() {
    Provider p = ModelFactory.awsProvider(customer);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");

    Region rReadOnly = Region.create(p, "region-readOnly-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(rReadOnly, "az-readOnly-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(rReadOnly, "az-readOnly-2", "PlacementAZ 2", "subnet-2");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    taskParams.nodePrefix = "univWithReadOnlyCreate";
    UUID readOnlyUuid0 = UUID.randomUUID();
    UUID readOnlyUuid1 = UUID.randomUUID();
    taskParams.upsertPrimaryCluster(getTestUserIntent(r, p, i, 5), null);
    taskParams.upsertCluster(getTestUserIntent(rReadOnly, p, i, 5), null, readOnlyUuid0);
    taskParams.upsertCluster(getTestUserIntent(rReadOnly, p, i, 5), null, readOnlyUuid1);

    PlacementInfoUtil.updateUniverseDefinition(
        taskParams, customer.getCustomerId(), taskParams.getPrimaryCluster().uuid, CREATE);
    PlacementInfoUtil.updateUniverseDefinition(
        taskParams, customer.getCustomerId(), readOnlyUuid0, CREATE);
    PlacementInfoUtil.updateUniverseDefinition(
        taskParams, customer.getCustomerId(), readOnlyUuid1, CREATE);

    UniverseDefinitionTaskParams.Cluster primaryCluster = taskParams.getPrimaryCluster();
    List<PlacementInfo.PlacementAZ> azList =
        primaryCluster.placementInfo.cloudList.get(0).regionList.get(0).azList;
    assertEquals(azList.size(), 2);

    UniverseDefinitionTaskParams.Cluster readOnlyCluster0 =
        taskParams.getClusterByUuid(readOnlyUuid0);
    azList = readOnlyCluster0.placementInfo.cloudList.get(0).regionList.get(0).azList;
    assertEquals(azList.size(), 2);

    UniverseDefinitionTaskParams.Cluster readOnlyCluster1 =
        taskParams.getClusterByUuid(readOnlyUuid1);
    azList = readOnlyCluster1.placementInfo.cloudList.get(0).regionList.get(0).azList;
    assertEquals(azList.size(), 2);

    Map<UUID, Integer> azUUIDToNumNodeMap = getAzUuidToNumNodes(primaryCluster.placementInfo);
    Map<UUID, Integer> azUUIDToNumNodeMapReadOnly0 =
        getAzUuidToNumNodes(readOnlyCluster0.placementInfo);
    Map<UUID, Integer> azUUIDToNumNodeMapReadOnly1 =
        getAzUuidToNumNodes(readOnlyCluster1.placementInfo);
    for (Map.Entry<UUID, Integer> entry : azUUIDToNumNodeMapReadOnly0.entrySet()) {
      UUID uuid = entry.getKey();
      int numNodes = entry.getValue();
      if (azUUIDToNumNodeMap.containsKey(uuid)) {
        int prevNumNodes = azUUIDToNumNodeMap.get(uuid);
        azUUIDToNumNodeMap.put(uuid, prevNumNodes + numNodes);
      } else {
        azUUIDToNumNodeMap.put(uuid, numNodes);
      }
    }
    for (Map.Entry<UUID, Integer> entry : azUUIDToNumNodeMapReadOnly1.entrySet()) {
      UUID uuid = entry.getKey();
      int numNodes = entry.getValue();
      if (azUUIDToNumNodeMap.containsKey(uuid)) {
        int prevNumNodes = azUUIDToNumNodeMap.get(uuid);
        azUUIDToNumNodeMap.put(uuid, prevNumNodes + numNodes);
      } else {
        azUUIDToNumNodeMap.put(uuid, numNodes);
      }
    }
    ObjectNode topJson = (ObjectNode) Json.toJson(taskParams);
    topJson.put("currentClusterType", "ASYNC");
    topJson.put("clusterOperation", "CREATE");
    String url = "/api/customers/" + customer.uuid + "/universe_configure";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, topJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertTrue(json.get("nodeDetailsSet").isArray());
    ArrayNode nodeDetailJson = (ArrayNode) json.get("nodeDetailsSet");
    assertEquals(15, nodeDetailJson.size());
    assertTrue(areConfigObjectsEqual(nodeDetailJson, azUUIDToNumNodeMap));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testCustomConfigureEditWithPureExpand() {
    Provider p = ModelFactory.awsProvider(customer);
    Universe u = createUniverse(customer.getCustomerId());

    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    UniverseDefinitionTaskParams utd = new UniverseDefinitionTaskParams();
    utd.universeUUID = u.universeUUID;
    UniverseDefinitionTaskParams.UserIntent ui = getTestUserIntent(r, p, i, 5);
    ui.universeName = u.name;
    ui.ybSoftwareVersion = "1.0";
    ui.preferredRegion = ui.regionList.get(0);
    utd.upsertPrimaryCluster(ui, null);
    PlacementInfoUtil.updateUniverseDefinition(
        utd,
        customer.getCustomerId(),
        utd.getPrimaryCluster().uuid,
        UniverseConfigureTaskParams.ClusterOperationType.CREATE);
    Universe.UniverseUpdater updater = universe -> universe.setUniverseDetails(utd);
    Universe.saveDetails(u.universeUUID, updater);
    u = Universe.getOrBadRequest(u.universeUUID);
    int totalNumNodesAfterExpand = 0;
    Map<UUID, Integer> azUuidToNumNodes =
        getAzUuidToNumNodes(u.getUniverseDetails().nodeDetailsSet);
    for (Map.Entry<UUID, Integer> entry : azUuidToNumNodes.entrySet()) {
      totalNumNodesAfterExpand += entry.getValue() + 1;
      azUuidToNumNodes.put(entry.getKey(), entry.getValue() + 1);
    }
    UniverseDefinitionTaskParams editTestUTD = u.getUniverseDetails();
    UniverseDefinitionTaskParams.Cluster primaryCluster = editTestUTD.getPrimaryCluster();
    primaryCluster.userIntent.numNodes = totalNumNodesAfterExpand;
    primaryCluster.placementInfo = constructPlacementInfoObject(azUuidToNumNodes);

    ObjectNode editJson = (ObjectNode) Json.toJson(editTestUTD);
    editJson.put("currentClusterType", "PRIMARY");
    editJson.put("clusterOperation", "EDIT");
    String url = "/api/customers/" + customer.uuid + "/universe_configure";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, editJson);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertTrue(json.get("nodeDetailsSet").isArray());
    ArrayNode nodeDetailJson = (ArrayNode) json.get("nodeDetailsSet");
    assertEquals(nodeDetailJson.size(), totalNumNodesAfterExpand);
    assertTrue(areConfigObjectsEqual(nodeDetailJson, azUuidToNumNodes));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testOnPremConfigureCreateWithValidAZInstanceTypeComboNotEnoughNodes() {
    Provider p = ModelFactory.newProvider(customer, Common.CloudType.onprem);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    InstanceType i =
        InstanceType.upsert(p.uuid, "type.small", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, p, i, 5);
    userIntent.providerType = Common.CloudType.onprem;
    taskParams.upsertPrimaryCluster(userIntent, null);

    taskParams.nodeDetailsSet = new HashSet<>();

    for (int k = 0; k < 4; ++k) {
      NodeInstanceFormData.NodeInstanceData details = new NodeInstanceFormData.NodeInstanceData();
      details.ip = "10.255.67." + i;
      details.region = r.code;
      details.zone = az1.code;
      details.instanceType = "test_instance_type";
      details.nodeName = "test_name";
      NodeInstance.create(az1.uuid, details);
    }

    ObjectNode topJson = (ObjectNode) Json.toJson(taskParams);
    topJson.put("currentClusterType", "PRIMARY");
    topJson.put("clusterOperation", "CREATE");
    String url = "/api/customers/" + customer.uuid + "/universe_configure";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, topJson));

    assertBadRequest(result, "Invalid Node/AZ combination for given instance type type.small");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testOnPremConfigureCreateInvalidAZNodeComboNonEmptyNodeDetailsSet() {
    Provider p = ModelFactory.newProvider(customer, Common.CloudType.onprem);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone az2 = AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    List<AvailabilityZone> azList = new ArrayList<>();
    azList.add(az1);
    azList.add(az2);

    InstanceType i =
        InstanceType.upsert(p.uuid, "type.small", 10, 5.5, new InstanceType.InstanceTypeDetails());

    UniverseDefinitionTaskParams taskParams = setupOnPremTestData(6, p, r, azList);

    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, p, i, 5);
    userIntent.providerType = Common.CloudType.onprem;
    userIntent.instanceType = "type.small";
    taskParams.upsertPrimaryCluster(userIntent, null);
    taskParams.nodeDetailsSet = new HashSet<>();
    UniverseDefinitionTaskParams.Cluster primaryCluster = taskParams.getPrimaryCluster();

    updateUniverseDefinition(taskParams, customer.getCustomerId(), primaryCluster.uuid, CREATE);

    // Set placement info with number of nodes valid but
    for (int k = 0; k < 5; k++) {
      NodeDetails nd = new NodeDetails();
      nd.state = NodeDetails.NodeState.ToBeAdded;
      nd.azUuid = az1.uuid;
      nd.placementUuid = primaryCluster.uuid;
      taskParams.nodeDetailsSet.add(nd);
    }

    ObjectNode topJson = (ObjectNode) Json.toJson(taskParams);
    topJson.put("currentClusterType", "PRIMARY");
    topJson.put("clusterOperation", "CREATE");

    String url = "/api/customers/" + customer.uuid + "/universe_configure";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, topJson));
    assertBadRequest(result, "Invalid Node/AZ combination for given instance type type.small");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testOnPremConfigureValidAZNodeComboNonEmptyNodeDetailsSet() {
    Provider p = ModelFactory.newProvider(customer, Common.CloudType.onprem);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");

    List<AvailabilityZone> azList = new ArrayList<>();
    azList.add(az1);

    UniverseDefinitionTaskParams taskParams = setupOnPremTestData(6, p, r, azList);

    UniverseDefinitionTaskParams.Cluster primaryCluster = taskParams.getPrimaryCluster();

    updateUniverseDefinition(taskParams, customer.getCustomerId(), primaryCluster.uuid, CREATE);

    ObjectNode topJson = (ObjectNode) Json.toJson(taskParams);
    topJson.put("clusterOperation", "CREATE");
    topJson.put("currentClusterType", "PRIMARY");
    String url = "/api/customers/" + customer.uuid + "/universe_configure";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, topJson);
    assertOk(result);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testConfigureEditOnPremInvalidNodeAZCombo() {
    Provider p = ModelFactory.newProvider(customer, Common.CloudType.onprem);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone az2 = AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");

    List<AvailabilityZone> azList = new ArrayList<>();
    azList.add(az1);
    azList.add(az2);

    UniverseDefinitionTaskParams taskParams = setupOnPremTestData(6, p, r, azList);

    InstanceType i =
        InstanceType.upsert(p.uuid, "type.small", 10, 5.5, new InstanceType.InstanceTypeDetails());

    taskParams.nodePrefix = "test_uni";
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, p, i, 5);
    userIntent.providerType = Common.CloudType.onprem;
    userIntent.instanceType = "type.small";
    taskParams.upsertPrimaryCluster(userIntent, null);
    UniverseDefinitionTaskParams.Cluster primaryCluster = taskParams.getPrimaryCluster();
    updateUniverseDefinition(taskParams, customer.getCustomerId(), primaryCluster.uuid, CREATE);

    // Set the nodes state to inUse
    int k = 0;
    for (NodeInstance ni : NodeInstance.listByProvider(p.uuid)) {
      if (k < 5) {
        k++;
        ni.inUse = true;
        ni.save();
      } else {
        break;
      }
    }

    // Simulate a running universe by setting existing nodes to Live state.
    for (NodeDetails nd : taskParams.nodeDetailsSet) {
      nd.state = NodeDetails.NodeState.Live;
    }

    // Set placement info with addition of nodes that is more than what has been configured
    for (int m = 0; m < 7; m++) {
      NodeDetails nd = new NodeDetails();
      nd.state = NodeDetails.NodeState.ToBeAdded;
      nd.azUuid = az1.uuid;
      nd.placementUuid = primaryCluster.uuid;
      taskParams.nodeDetailsSet.add(nd);
    }

    ObjectNode topJson = (ObjectNode) Json.toJson(taskParams);
    topJson.put("clusterOperation", "EDIT");
    topJson.put("currentClusterType", "PRIMARY");
    String url = "/api/customers/" + customer.uuid + "/universe_configure";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, topJson));
    assertBadRequest(result, "Invalid Node/AZ combination for given instance type type.small");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testCreateUniverseEncryptionAtRestNoKMSConfig() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());

    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    ObjectNode bodyJson = (ObjectNode) Json.toJson(taskParams);

    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "encryptionAtRestUniverse")
            .put("instanceType", i.getInstanceTypeCode())
            .put("enableNodeToNodeEncrypt", true)
            .put("enableClientToNodeEncrypt", true)
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);

    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    userIntentJson.set("deviceInfo", createValidDeviceInfo(Common.CloudType.aws));
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    ObjectNode cloudInfo = Json.newObject();
    cloudInfo.put("region", "region1");
    ObjectNode nodeDetails = Json.newObject();
    nodeDetails.put("nodeName", "testing-1");
    nodeDetails.set("cloudInfo", cloudInfo);
    ArrayNode nodeDetailsSet = Json.newArray().add(nodeDetails);
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", nodeDetailsSet);
    bodyJson.put("nodePrefix", "demo-node");

    // TODO: (Daniel) - Add encryptionAtRestConfig to the payload to actually
    //  test what this unit test says it is testing for

    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    JsonNode json = Json.parse(contentAsString(result));
    assertOk(result);

    // Check that the encryption key file was not created in file system
    File key =
        new File(
            "/tmp/certs/"
                + customer.uuid.toString()
                + "/universe."
                + json.get("universeUUID").asText()
                + "-1.key");
    assertFalse(key.exists());
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<UniverseTaskParams> argCaptor =
        ArgumentCaptor.forClass(UniverseTaskParams.class);
    verify(mockCommissioner).submit(eq(TaskType.CreateUniverse), argCaptor.capture());

    // The KMS provider service should not begin to make any requests since there is no KMS config
    verify(mockApiHelper, times(0)).postRequest(any(String.class), any(JsonNode.class), anyMap());
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testCreateUniverseEncryptionAtRestWithKMSConfigExists() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    ObjectNode bodyJson = (ObjectNode) Json.toJson(taskParams);

    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "encryptionAtRestUniverse")
            .put("instanceType", i.getInstanceTypeCode())
            .put("enableNodeToNodeEncrypt", true)
            .put("enableClientToNodeEncrypt", true)
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);

    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    userIntentJson.set("deviceInfo", createValidDeviceInfo(Common.CloudType.aws));
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));

    ObjectNode cloudInfo = Json.newObject();
    cloudInfo.put("region", "region1");
    ObjectNode nodeDetails = Json.newObject();
    nodeDetails.put("nodeName", "testing-1");
    nodeDetails.set("cloudInfo", cloudInfo);
    ArrayNode nodeDetailsSet = Json.newArray().add(nodeDetails);
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", nodeDetailsSet);
    bodyJson.put("nodePrefix", "demo-node");
    bodyJson.put(
        "encryptionAtRestConfig",
        Json.newObject()
            .put("configUUID", kmsConfig.configUUID.toString())
            .put("key_op", "ENABLE"));
    String url = "/api/customers/" + customer.uuid + "/universes";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    JsonNode json = Json.parse(contentAsString(result));
    assertOk(result);

    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<UniverseTaskParams> argCaptor =
        ArgumentCaptor.forClass(UniverseTaskParams.class);
    verify(mockCommissioner).submit(eq(TaskType.CreateUniverse), argCaptor.capture());
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseCreateWithInvalidTServerJson() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    Universe u = createUniverse(customer.getCustomerId());
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("masterGFlags", "abcd")
            .put("universeName", u.name)
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID;
    Result result = doRequestWithAuthTokenAndBody("PUT", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "universeUUID", u.universeUUID.toString());
    assertNotNull(json.get("universeDetails"));
    assertTrue(json.get("universeConfig").asText().isEmpty());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Update)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseExpand() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    Universe u = createUniverse(customer.getCustomerId());
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", u.name)
            .put("numNodes", 5)
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID;
    Result result = doRequestWithAuthTokenAndBody("PUT", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "universeUUID", u.universeUUID.toString());
    JsonNode universeDetails = json.get("universeDetails");
    assertNotNull(universeDetails);
    JsonNode clustersJson = universeDetails.get("clusters");
    assertNotNull(clustersJson);
    JsonNode primaryClusterJson = clustersJson.get(0);
    assertNotNull(primaryClusterJson);
    assertNotNull(primaryClusterJson.get("userIntent"));
    assertAuditEntry(1, customer.uuid);

    fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    // Try universe expand only, and re-check.
    userIntentJson.put("numNodes", 9);
    result = doRequestWithAuthTokenAndBody("PUT", url, authToken, bodyJson);
    assertOk(result);
    json = Json.parse(contentAsString(result));
    assertValue(json, "universeUUID", u.universeUUID.toString());
    universeDetails = json.get("universeDetails");
    assertNotNull(universeDetails);
    clustersJson = universeDetails.get("clusters");
    assertNotNull(clustersJson);
    primaryClusterJson = clustersJson.get(0);
    assertNotNull(primaryClusterJson);
    assertNotNull(primaryClusterJson.get("userIntent"));
    assertAuditEntry(2, customer.uuid);
  }

  @Test
  public void testUniverseExpandWithTransitNodes() {
    UUID fakeTaskUUID = UUID.randomUUID();

    Provider p = ModelFactory.awsProvider(customer);
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    Universe u = createUniverse(customer.getCustomerId());
    Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    setInTransitNode(u.universeUUID);

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", u.name)
            .put("numNodes", 5)
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("provider", p.uuid.toString());
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID;
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("PUT", url, authToken, bodyJson));
    assertBadRequest(result, "as it has nodes in one of");
    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseUpdateWithInvalidParams() {
    Universe u = createUniverse(customer.getCustomerId());
    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID;
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("PUT", url, authToken, Json.newObject()));
    assertBadRequest(result, "clusters: This field is required");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseUpdateWithValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Provider p = ModelFactory.awsProvider(customer);
    String accessKeyCode = "someKeyCode";
    AccessKey.create(p.uuid, accessKeyCode, new AccessKey.KeyInfo());
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ 3", "subnet-3");
    Universe u = createUniverse(customer.getCustomerId());
    InstanceType i =
        InstanceType.upsert(p.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());

    ObjectNode bodyJson = Json.newObject();
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", u.name)
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("numNodes", 3)
            .put("provider", p.uuid.toString())
            .put("accessKeyCode", accessKeyCode);
    ArrayNode regionList = Json.newArray().add(r.uuid.toString());
    userIntentJson.set("regionList", regionList);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    bodyJson.set("nodeDetailsSet", Json.newArray());

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID;
    Result result = doRequestWithAuthTokenAndBody("PUT", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "universeUUID", u.universeUUID.toString());
    assertNotNull(json.get("universeDetails"));

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Update)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testVMImageUpgradeWithUnsupportedProvider() {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    UUID fakeTaskUUID = UUID.randomUUID();
    UUID uUUID = createUniverse(customer.getCustomerId()).universeUUID;
    Universe.saveDetails(uUUID, ApiUtils.mockUniverseUpdater());

    Provider p = ModelFactory.onpremProvider(customer);
    ObjectNode bodyJson = setupVMImageUpgradeParams(uUUID, p, "type.small");

    String url = "/api/customers/" + customer.uuid + "/universes/" + uUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "VM image upgrade is only supported for AWS / GCP");
    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testVMImageUpgradeWithEphemerals() {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    UUID fakeTaskUUID = UUID.randomUUID();
    UUID uUUID = createUniverse(customer.getCustomerId()).universeUUID;
    Universe.saveDetails(uUUID, ApiUtils.mockUniverseUpdater());

    Provider p = ModelFactory.awsProvider(customer);
    ObjectNode bodyJson = setupVMImageUpgradeParams(uUUID, p, "i3.xlarge");

    String url = "/api/customers/" + customer.uuid + "/universes/" + uUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "Cannot upgrade a universe with ephemeral storage");
    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testVMImageUpgradeWithNoImage() {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    UUID fakeTaskUUID = UUID.randomUUID();
    UUID uUUID = createUniverse(customer.getCustomerId()).universeUUID;
    Universe.saveDetails(uUUID, ApiUtils.mockUniverseUpdater());

    Provider p = ModelFactory.awsProvider(customer);
    ObjectNode bodyJson = setupVMImageUpgradeParams(uUUID, p, "c5.xlarge");

    String url = "/api/customers/" + customer.uuid + "/universes/" + uUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "machineImages param is required for taskType: VMImage");
    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testVMImageUpgradeValidParams() {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Universe u = createUniverse(customer.getCustomerId());
    Provider p = ModelFactory.awsProvider(customer);
    ObjectNode bodyJson = setupVMImageUpgradeParams(u.universeUUID, p, "c5.xlarge");
    ObjectNode images = Json.newObject();
    UUID r = UUID.randomUUID();
    images.put(r.toString(), "image-" + r.toString());
    bodyJson.set("machineImages", images);
    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), any(UniverseTaskParams.class));

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.UpgradeVMImage)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseUpgradeWithEmptyParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    UUID uUUID = createUniverse(customer.getCustomerId()).universeUUID;
    Universe.saveDetails(uUUID, ApiUtils.mockUniverseUpdater());

    String url = "/api/customers/" + customer.uuid + "/universes/" + uUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, Json.newObject()));
    assertBadRequest(result, "clusters: This field is required");
    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  @Parameters({
    "Rolling",
    "Non-Rolling",
    "Non-Restart",
  })
  public void testUniverseUpgradeWithNodesInTransit(String upgradeOption) {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    UUID uUUID = createUniverse(customer.getCustomerId()).universeUUID;
    Universe.saveDetails(uUUID, ApiUtils.mockUniverseUpdater());

    setInTransitNode(uUUID);

    ObjectNode bodyJson = getValidPayload(uUUID, upgradeOption);
    String url = "/api/customers/" + customer.uuid + "/universes/" + uUUID + "/upgrade";
    if (upgradeOption.equals("Rolling")) {
      Result result =
          assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
      assertBadRequest(result, "as it has nodes in one of");
      assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
      assertAuditEntry(0, customer.uuid);
    } else {
      Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
      assertOk(result);
      JsonNode json = Json.parse(contentAsString(result));
      assertValue(json, "taskUUID", fakeTaskUUID.toString());
      assertAuditEntry(1, customer.uuid);
    }
  }

  ObjectNode getValidPayload(UUID univUUID, String upgradeOption) {
    ObjectNode bodyJson =
        Json.newObject()
            .put("universeUUID", univUUID.toString())
            .put("taskType", "Software")
            .put("upgradeOption", upgradeOption)
            .put("ybSoftwareVersion", "0.0.1");
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "Single UserUniverse")
            .put("ybSoftwareVersion", "0.0.1");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    return bodyJson;
  }

  protected ObjectNode setupVMImageUpgradeParams(UUID uUUID, Provider p, String instanceType) {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    ObjectNode bodyJson = getValidPayload(uUUID, "Rolling");
    bodyJson.put("taskType", UpgradeUniverse.UpgradeTaskType.VMImage.toString());
    InstanceType i =
        InstanceType.upsert(p.uuid, instanceType, 10, 5.5, new InstanceType.InstanceTypeDetails());
    ObjectNode userIntentJson =
        Json.newObject()
            .put("numNodes", 3)
            .put("instanceType", i.getInstanceTypeCode())
            .put("replicationFactor", 3)
            .put("providerType", p.code)
            .put("provider", p.uuid.toString());
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    return bodyJson;
  }

  @Test
  public void testUniverseSoftwareUpgradeValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson = getValidPayload(u.universeUUID, "Rolling");
    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), any(UniverseTaskParams.class));

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.UpgradeSoftware)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseRollingRestartValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject()
            .put("universeUUID", u.universeUUID.toString())
            .put("taskType", "Restart")
            .put("upgradeOption", "Rolling");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Single UserUniverse");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), any(UniverseTaskParams.class));

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Restart)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseRollingRestartNonRolling() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject()
            .put("universeUUID", u.universeUUID.toString())
            .put("taskType", "Restart")
            .put("upgradeOption", "Non-Rolling");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Single UserUniverse");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);
    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "Rolling restart has to be a ROLLING UPGRADE.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseSoftwareUpgradeWithInvalidParams() {
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject().put("universeUUID", u.universeUUID.toString()).put("taskType", "Software");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Single UserUniverse");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "ybSoftwareVersion param is required for taskType: Software");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseGFlagsUpgradeValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject().put("universeUUID", u.universeUUID.toString()).put("taskType", "GFlags");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Single UserUniverse");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    JsonNode masterGFlags = Json.parse("[{ \"name\": \"master-flag\", \"value\": \"123\"}]");
    JsonNode tserverGFlags = Json.parse("[{ \"name\": \"tserver-flag\", \"value\": \"456\"}]");
    userIntentJson.set("masterGFlags", masterGFlags);
    userIntentJson.set("tserverGFlags", tserverGFlags);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());
    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), any(UniverseTaskParams.class));

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.UpgradeGflags)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseGFlagsUpgradeWithTrimParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject().put("universeUUID", u.universeUUID.toString()).put("taskType", "GFlags");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Single UserUniverse");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    JsonNode masterGFlags = Json.parse("[{ \"name\": \" master-flag \", \"value\": \" 123 \"}]");
    JsonNode tserverGFlags = Json.parse("[{ \"name\": \" tserver-flag \", \"value\": \" 456 \"}]");
    userIntentJson.set("masterGFlags", masterGFlags);
    userIntentJson.set("tserverGFlags", tserverGFlags);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());
    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), any(UniverseTaskParams.class));

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.UpgradeGflags)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseGFlagsUpgradeWithInvalidParams() {
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject().put("universeUUID", u.universeUUID.toString()).put("taskType", "GFlags");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Test Universe");
    userIntentJson.set("masterGFlags", Json.parse("[\"gflag1\", \"123\"]"));
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "Neither master nor tserver gflags changed.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseGFlagsUpgradeWithSameGFlags() {
    Universe u = createUniverse(customer.getCustomerId());

    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          UniverseDefinitionTaskParams.UserIntent userIntent =
              universeDetails.getPrimaryCluster().userIntent;
          userIntent.masterGFlags = ImmutableMap.of("master-flag", "123");
          userIntent.tserverGFlags = ImmutableMap.of("tserver-flag", "456");
          universe.setUniverseDetails(universeDetails);
        };
    Universe.saveDetails(u.universeUUID, updater);

    ObjectNode bodyJson =
        Json.newObject()
            .put("universeUUID", u.universeUUID.toString())
            .put("taskType", "GFlags")
            .put("upgradeOption", "Non-Rolling");
    ObjectNode userIntentJson = Json.newObject().put("universeName", u.name);
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    JsonNode masterGFlags = Json.parse("[{ \"name\": \"master-flag\", \"value\": \"123\"}]");
    JsonNode tserverGFlags = Json.parse("[{ \"name\": \"tserver-flag\", \"value\": \"456\"}]");
    userIntentJson.set("masterGFlags", masterGFlags);
    userIntentJson.set("tserverGFlags", tserverGFlags);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "Neither master nor tserver gflags changed");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseGFlagsUpgradeWithMissingGflags() {
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJsonMissingGFlags =
        Json.newObject().put("universeUUID", u.universeUUID.toString()).put("taskType", "GFlags");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Single UserUniverse");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJsonMissingGFlags.set("clusters", clustersJsonArray);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertYWSE(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJsonMissingGFlags));

    assertBadRequest(result, "Neither master nor tserver gflags changed.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseGFlagsUpgradeWithMalformedTServerFlags() {
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject().put("universeUUID", u.universeUUID.toString()).put("taskType", "GFlags");
    ObjectNode userIntentJson =
        Json.newObject().put("universeName", "Single UserUniverse").put("tserverGFlags", "abcd");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "Neither master nor tserver gflags changed.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseGFlagsUpgradeWithMalformedMasterGFlags() {
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject().put("universeUUID", u.universeUUID.toString()).put("taskType", "GFlags");
    ObjectNode userIntentJson =
        Json.newObject().put("universeName", "Single UserUniverse").put("masterGFlags", "abcd");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));

    assertBadRequest(result, "Neither master nor tserver gflags changed.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseNonRollingGFlagsUpgrade() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject()
            .put("universeUUID", u.universeUUID.toString())
            .put("taskType", "GFlags")
            .put("upgradeOption", "Non-Rolling");
    ObjectNode userIntentJson = Json.newObject().put("universeName", "Single UserUniverse");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    JsonNode masterGFlags = Json.parse("[{ \"name\": \"master-flag\", \"value\": \"123\"}]");
    JsonNode tserverGFlags = Json.parse("[{ \"name\": \"tserver-flag\", \"value\": \"456\"}]");
    userIntentJson.set("masterGFlags", masterGFlags);
    userIntentJson.set("tserverGFlags", tserverGFlags);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    ArgumentCaptor<UniverseTaskParams> taskParams =
        ArgumentCaptor.forClass(UniverseTaskParams.class);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());
    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), taskParams.capture());
    UpgradeParams taskParam = (UpgradeParams) taskParams.getValue();
    assertEquals(taskParam.upgradeOption, UpgradeParams.UpgradeOption.NON_ROLLING_UPGRADE);
    assertEquals(taskParam.masterGFlags, ImmutableMap.of("master-flag", "123"));
    assertEquals(taskParam.tserverGFlags, ImmutableMap.of("tserver-flag", "456"));
    UniverseDefinitionTaskParams.UserIntent primaryClusterIntent =
        taskParam.getPrimaryCluster().userIntent;
    assertEquals(primaryClusterIntent.masterGFlags, taskParam.masterGFlags);
    assertEquals(primaryClusterIntent.tserverGFlags, taskParam.tserverGFlags);
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseNonRollingSoftwareUpgrade() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson =
        Json.newObject()
            .put("universeUUID", u.universeUUID.toString())
            .put("taskType", "Software")
            .put("upgradeOption", "Non-Rolling")
            .put("ybSoftwareVersion", "new-version");
    ObjectNode userIntentJson =
        Json.newObject()
            .put("universeName", "Single UserUniverse")
            .put("ybSoftwareVersion", "new-version");
    ArrayNode clustersJsonArray =
        Json.newArray().add(Json.newObject().set("userIntent", userIntentJson));
    bodyJson.set("clusters", clustersJsonArray);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    ArgumentCaptor<UniverseTaskParams> taskParams =
        ArgumentCaptor.forClass(UniverseTaskParams.class);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());
    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), taskParams.capture());
    UpgradeParams taskParam = (UpgradeParams) taskParams.getValue();
    assertEquals(taskParam.upgradeOption, UpgradeParams.UpgradeOption.NON_ROLLING_UPGRADE);
    assertEquals("new-version", taskParam.ybSoftwareVersion);
    assertAuditEntry(1, customer.uuid);
  }

  private ObjectNode getResizeNodeValidPayload(Universe u, Provider p) {
    UUID fakeClusterUUID = UUID.randomUUID();
    ObjectNode bodyJson =
        Json.newObject()
            .put("universeUUID", u.universeUUID.toString())
            .put("taskType", UpgradeUniverse.UpgradeTaskType.ResizeNode.toString())
            .put("upgradeOption", "Rolling");

    ObjectNode deviceInfoJson = Json.newObject().put("volumeSize", 600);

    ObjectNode userIntentJson = Json.newObject().put("instanceType", "test-instance-type");
    userIntentJson.set("deviceInfo", deviceInfoJson);

    if (p != null) {
      userIntentJson.put("providerType", p.code).put("provider", p.uuid.toString());
    }

    ObjectNode primaryCluster =
        Json.newObject().put("uuid", fakeClusterUUID.toString()).put("clusterType", "PRIMARY");
    primaryCluster.set("userIntent", (ObjectNode) userIntentJson);

    ArrayNode clustersJsonArray = Json.newArray().add(primaryCluster);
    bodyJson.set("clusters", clustersJsonArray);

    return bodyJson;
  }

  @Test
  public void testResizeNodeWithUnsupportedProvider() {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Universe u = createUniverse(customer.getCustomerId());

    Provider p = ModelFactory.newProvider(customer, Common.CloudType.other);
    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          UniverseDefinitionTaskParams.UserIntent userIntent =
              universeDetails.getPrimaryCluster().userIntent;
          userIntent.providerType = Common.CloudType.other;
          userIntent.provider = p.uuid.toString();
          universe.setUniverseDetails(universeDetails);
        };
    Universe.saveDetails(u.universeUUID, updater);

    ObjectNode bodyJson = getResizeNodeValidPayload(u, p);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result =
        assertThrows(
                YWServiceException.class,
                () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson))
            .getResult();

    assertBadRequest(result, "Smart resizing is only supported for AWS / GCP, It is: " + p.code);

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testResizeNodeValidParams() {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Universe u = createUniverse(customer.getCustomerId());

    ObjectNode bodyJson = getResizeNodeValidPayload(u, null);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/upgrade";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    verify(mockCommissioner).submit(eq(TaskType.UpgradeUniverse), any(UniverseTaskParams.class));

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.ResizeNode)));
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testExpandDiskSizeFailureInvalidSize() {
    Universe u = createUniverse(customer.getCustomerId());
    customer.addUniverseUUID(u.universeUUID);
    customer.save();
    setupDiskUpdateTest(100, "c4.xlarge", PublicCloudConstants.StorageType.GP2, u);
    u = Universe.getOrBadRequest(u.universeUUID);

    ObjectNode bodyJson = (ObjectNode) Json.toJson(u.getUniverseDetails());
    bodyJson.put("size", 50);

    String url =
        "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/disk_update";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "Size can only be increased.");
  }

  @Test
  public void testExpandDiskSizeFailureInvalidStorage() {
    Universe u = createUniverse(customer.getCustomerId());
    customer.addUniverseUUID(u.universeUUID);
    customer.save();
    setupDiskUpdateTest(100, "c4.xlarge", PublicCloudConstants.StorageType.Scratch, u);
    u = Universe.getOrBadRequest(u.universeUUID);

    ObjectNode bodyJson = (ObjectNode) Json.toJson(u.getUniverseDetails());
    bodyJson.put("size", 150);

    String url =
        "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/disk_update";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "Scratch type disk cannot be modified.");
  }

  @Test
  public void testExpandDiskSizeFailureInvalidInstance() {
    Universe u = createUniverse(customer.getCustomerId());
    customer.addUniverseUUID(u.universeUUID);
    customer.save();
    setupDiskUpdateTest(100, "i3.xlarge", PublicCloudConstants.StorageType.GP2, u);
    u = Universe.getOrBadRequest(u.universeUUID);

    ObjectNode bodyJson = (ObjectNode) Json.toJson(u.getUniverseDetails());
    bodyJson.put("size", 150);

    String url =
        "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/disk_update";
    Result result =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "Cannot modify instance volumes.");
  }

  @Test
  public void testExpandDiskSizeSuccess() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(
            Matchers.any(TaskType.class), Matchers.any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());
    customer.addUniverseUUID(u.universeUUID);
    customer.save();
    setupDiskUpdateTest(100, "c4.xlarge", PublicCloudConstants.StorageType.GP2, u);
    u = Universe.getOrBadRequest(u.universeUUID);

    ObjectNode bodyJson = (ObjectNode) Json.toJson(u.getUniverseDetails());
    bodyJson.put("size", 150);

    String url =
        "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/disk_update";
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);
    assertOk(result);
    ArgumentCaptor<UniverseTaskParams> argCaptor =
        ArgumentCaptor.forClass(UniverseTaskParams.class);
    verify(mockCommissioner).submit(eq(TaskType.UpdateDiskSize), argCaptor.capture());
    assertAuditEntry(1, customer.uuid);
  }

  private void setupDiskUpdateTest(
      int diskSize, String instanceType, PublicCloudConstants.StorageType storageType, Universe u) {

    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = new UniverseDefinitionTaskParams();
          UniverseDefinitionTaskParams.UserIntent userIntent =
              new UniverseDefinitionTaskParams.UserIntent();
          userIntent.instanceType = instanceType;
          userIntent.providerType = Common.CloudType.aws;
          DeviceInfo di = new DeviceInfo();
          di.volumeSize = diskSize;
          di.numVolumes = 2;
          di.storageType = storageType;
          userIntent.deviceInfo = di;
          universeDetails.upsertPrimaryCluster(userIntent, null);
          universe.setUniverseDetails(universeDetails);
        };
    // Save the updates to the universe.
    Universe.saveDetails(u.universeUUID, updater);
  }

  private UniverseDefinitionTaskParams setupOnPremTestData(
      int numNodesToBeConfigured, Provider p, Region r, List<AvailabilityZone> azList) {
    int numAZsToBeConfigured = azList.size();
    InstanceType i =
        InstanceType.upsert(p.uuid, "type.small", 10, 5.5, new InstanceType.InstanceTypeDetails());

    for (int k = 0; k < numNodesToBeConfigured; ++k) {
      NodeInstanceFormData.NodeInstanceData details = new NodeInstanceFormData.NodeInstanceData();
      details.ip = "10.255.67." + k;
      details.region = r.code;

      if (numAZsToBeConfigured == 2) {
        if (k % 2 == 0) {
          details.zone = azList.get(0).code;
        } else {
          details.zone = azList.get(1).code;
        }
      } else {
        details.zone = azList.get(0).code;
      }
      details.instanceType = "type.small";
      details.nodeName = "test_name" + k;

      if (numAZsToBeConfigured == 2) {
        if (k % 2 == 0) {
          NodeInstance.create(azList.get(0).uuid, details);
        } else {
          NodeInstance.create(azList.get(0).uuid, details);
        }
      } else {
        NodeInstance.create(azList.get(0).uuid, details);
      }
    }

    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, p, i, 3);
    userIntent.providerType = Common.CloudType.onprem;
    userIntent.instanceType = "type.small";
    taskParams.nodeDetailsSet = new HashSet<>();

    taskParams.upsertPrimaryCluster(userIntent, null);

    return taskParams;
  }
}
