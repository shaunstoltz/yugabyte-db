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
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.controllers.handlers.UniverseCRUDHandler;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.Universe;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;
import play.libs.Json;
import play.mvc.Result;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static com.yugabyte.yw.common.ApiUtils.getDefaultUserIntent;
import static com.yugabyte.yw.common.AssertHelper.*;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthToken;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.FORBIDDEN;
import static play.test.Helpers.contentAsString;

@RunWith(JUnitParamsRunner.class)
public class UniverseControllerTest extends UniverseControllerTestBase {

  @Test
  public void testUniverseTrimFlags() {
    Map<String, String> data = new HashMap<>();
    data.put(" Test ", " One ");
    data.put(" Test 2 ", " Two ");

    Map<String, String> result = UniverseCRUDHandler.trimFlags(data);
    assertEquals(result.size(), 2);
    assertEquals(result.get("Test"), "One");
    assertEquals(result.get("Test 2"), "Two");
  }

  @Test
  @Parameters({
    "list universes, true, , GET",
    "get universe, false, , GET",
    "get universe leader, false, /leader, GET"
  })
  public void invalidCustomerUUID(
      String testDescription, boolean isList, String urlSuffix, String httpMethod) {
    UUID invalidCustomerUUID = UUID.randomUUID();
    String universesPath = isList ? "/universes" : "/universes/" + UUID.randomUUID();
    String url = "/api/customers/" + invalidCustomerUUID + universesPath + urlSuffix;
    Result result = doRequestWithAuthToken(httpMethod, url, authToken);
    assertEquals(url, FORBIDDEN, result.status());

    String resultString = contentAsString(result);
    assertThat(resultString, allOf(notNullValue(), equalTo("Unable To Authenticate User")));
    assertAuditEntry(0, customer.uuid);
  }

  // TODO(vineeth) Decide: Should these result in FORBIDDEN after RBAC?
  @Test
  @Parameters({
    "get universe, , GET",
    "delete universe, , DELETE",
    "get universe status, /status, GET",
    "pause universe, /pause, POST",
    "resume universe, /resume, POST",
    "get universe leader, /leader, GET",
    "setup 2dc universe, /setup_universe_2dc, PUT"
  })
  public void invalidUniverseUUID(String testDescription, String urlSuffix, String httpMethod) {
    UUID randomUUID = UUID.randomUUID();
    String url = "/api/customers/" + customer.uuid + "/universes/" + randomUUID + urlSuffix;
    Result result = assertYWSE(() -> doRequestWithAuthToken(httpMethod, url, authToken));
    assertBadRequest(result, "Cannot find universe " + randomUUID);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testEmptyUniverseListWithValidUUID() {
    Result result =
        doRequestWithAuthToken("GET", "/api/customers/" + customer.uuid + "/universes", authToken);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertTrue(json.isArray());
    assertEquals(json.size(), 0);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseListWithValidUUID() {
    Universe u = createUniverse(customer.getCustomerId());
    customer.addUniverseUUID(u.universeUUID);
    customer.save();

    Result result =
        doRequestWithAuthToken("GET", "/api/customers/" + customer.uuid + "/universes", authToken);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertNotNull(json);
    assertTrue(json.isArray());
    assertEquals(1, json.size());
    assertValue(json.get(0), "universeUUID", u.universeUUID.toString());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseGetWithValidUniverseUUID() {
    UniverseDefinitionTaskParams.UserIntent ui = getDefaultUserIntent(customer);
    UUID uUUID = createUniverse(customer.getCustomerId()).universeUUID;
    Universe.saveDetails(uUUID, ApiUtils.mockUniverseUpdater(ui));

    String url = "/api/customers/" + customer.uuid + "/universes/" + uUUID;
    Result result = doRequestWithAuthToken("GET", url, authToken);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    JsonNode universeDetails = json.get("universeDetails");
    assertNotNull(universeDetails);
    JsonNode clustersJson = universeDetails.get("clusters");
    assertNotNull(clustersJson);
    JsonNode primaryClusterJson = clustersJson.get(0);
    assertNotNull(primaryClusterJson);
    JsonNode userIntentJson = primaryClusterJson.get("userIntent");
    assertNotNull(userIntentJson);
    assertThat(userIntentJson.get("replicationFactor").asInt(), allOf(notNullValue(), equalTo(3)));

    JsonNode nodeDetailsMap = universeDetails.get("nodeDetailsSet");
    assertNotNull(nodeDetailsMap);
    assertNotNull(json.get("resources"));
    for (Iterator<JsonNode> it = nodeDetailsMap.elements(); it.hasNext(); ) {
      JsonNode node = it.next();
      int nodeIdx = node.get("nodeIdx").asInt();
      assertValue(node, "nodeName", "host-n" + nodeIdx);
    }
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseDestroyValidUUID() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    // Add the cloud info into the universe.
    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = new UniverseDefinitionTaskParams();
          UniverseDefinitionTaskParams.UserIntent userIntent =
              new UniverseDefinitionTaskParams.UserIntent();
          userIntent.providerType = Common.CloudType.aws;
          universeDetails.upsertPrimaryCluster(userIntent, null);
          universe.setUniverseDetails(universeDetails);
        };
    // Save the updates to the universe.
    Universe.saveDetails(u.universeUUID, updater);

    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID;
    Result result = doRequestWithAuthToken("DELETE", url, authToken);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Delete)));

    assertTrue(customer.getUniverseUUIDs().isEmpty());
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testUniverseDestroyValidUUIDIsForceDelete() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    UUID randUUID = UUID.randomUUID();
    CustomerTask.create(
        customer,
        u.universeUUID,
        randUUID,
        CustomerTask.TargetType.Backup,
        CustomerTask.TaskType.Create,
        "test");

    // Add the cloud info into the universe.
    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = new UniverseDefinitionTaskParams();
          UniverseDefinitionTaskParams.UserIntent userIntent =
              new UniverseDefinitionTaskParams.UserIntent();
          userIntent.providerType = Common.CloudType.aws;
          universeDetails.upsertPrimaryCluster(userIntent, null);
          universe.setUniverseDetails(universeDetails);
        };
    // Save the updates to the universe.
    Universe.saveDetails(u.universeUUID, updater);

    String url =
        "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "?isForceDelete=true";
    Result result = doRequestWithAuthToken("DELETE", url, authToken);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    CustomerTask th = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(th);
    assertThat(th.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(th.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(th.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Delete)));
    assertNotNull(CustomerTask.findByTaskUUID(randUUID).getCompletionTime());

    assertTrue(customer.getUniverseUUIDs().isEmpty());
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  // @formatter:off
  @Parameters({
    "true, true",
    "false, true",
    "true, false",
    "false, false",
    "null, true",
  })
  // @formatter:on
  public void testUniverseDestroyValidUUIDIsForceDeleteAndDeleteBackup(
      Boolean isDeleteBackups, Boolean isForceDelete) {
    UUID fakeTaskUUID = UUID.randomUUID();
    String url;
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Universe u = createUniverse(customer.getCustomerId());

    // Add the cloud info into the universe.
    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = new UniverseDefinitionTaskParams();
          UniverseDefinitionTaskParams.UserIntent userIntent =
              new UniverseDefinitionTaskParams.UserIntent();
          userIntent.providerType = Common.CloudType.aws;
          universeDetails.upsertPrimaryCluster(userIntent, null);
          universe.setUniverseDetails(universeDetails);
        };
    // Save the updates to the universe.
    Universe.saveDetails(u.universeUUID, updater);

    Backup b = ModelFactory.createBackup(customer.uuid, u.universeUUID, s3StorageConfig.configUUID);
    b.transitionState(Backup.BackupState.Completed);
    if (isDeleteBackups == null) {
      url =
          "/api/customers/"
              + customer.uuid
              + "/universes/"
              + u.universeUUID
              + "?isForceDelete="
              + isForceDelete;
    } else {
      url =
          "/api/customers/"
              + customer.uuid
              + "/universes/"
              + u.universeUUID
              + "?isForceDelete="
              + isForceDelete
              + "&isDeleteBackups="
              + isDeleteBackups;
    }
    Result result = doRequestWithAuthToken("DELETE", url, authToken);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    CustomerTask customerTask =
        CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(customerTask);
    assertThat(customerTask.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertAuditEntry(1, customer.uuid);
  }
}
