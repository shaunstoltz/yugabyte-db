// Copyright 2020 YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.ITask;
import com.yugabyte.yw.commissioner.tasks.subtasks.CreatePrometheusSwamperConfig;
import com.yugabyte.yw.commissioner.tasks.subtasks.check.CheckMasterLeader;
import com.yugabyte.yw.commissioner.tasks.subtasks.check.CheckMasters;
import com.yugabyte.yw.commissioner.tasks.subtasks.check.CheckTServers;
import com.yugabyte.yw.commissioner.tasks.subtasks.check.WaitForTServerHBs;
import com.yugabyte.yw.common.ApiHelper;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.common.ConfigHelper;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.common.ValidatingFormFactory;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.forms.ImportUniverseFormData;
import com.yugabyte.yw.forms.ImportUniverseResponseData;
import com.yugabyte.yw.forms.ImportUniverseFormData.State;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Capability;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ImportedState;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.forms.YWResults;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.InstanceType.InstanceTypeDetails;
import com.yugabyte.yw.models.Universe.UniverseUpdater;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementAZ;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementCloud;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementRegion;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import lombok.extern.slf4j.Slf4j;
import org.yb.client.ListTabletServersResponse;
import org.yb.client.YBClient;
import org.yb.util.ServerInfo;
import play.data.Form;
import play.data.FormFactory;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

@Api(value = "Import", authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
@Slf4j
public class ImportController extends AuthenticatedController {

  // Threadpool to run user submitted tasks.
  static ExecutorService executor;

  // Size of thread pool.
  private static final int TASK_THREADS = 200;

  // Expected string for node exporter http request.
  private static final String NODE_EXPORTER_RESP = "Node Exporter";

  @Inject ValidatingFormFactory formFactory;

  @Inject YBClientService ybService;

  @Inject ApiHelper apiHelper;

  @Inject ConfigHelper configHelper;

  @ApiOperation(value = "import", response = ImportUniverseFormData.class)
  public Result importUniverse(UUID customerUUID) {
    // Get the submitted form data.
    Form<ImportUniverseFormData> formData =
        formFactory.getFormDataOrBadRequest(ImportUniverseFormData.class);
    if (formData.hasErrors()) {
      return ApiResponse.error(BAD_REQUEST, formData.errorsAsJson());
    }
    ImportUniverseResponseData results = new ImportUniverseResponseData();
    ImportUniverseFormData importForm = formData.get();

    Customer customer = Customer.getOrBadRequest(customerUUID);
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));

    if (importForm.singleStep) {
      importForm.currentState = State.BEGIN;
      Result res = importUniverseMasters(importForm, customer, results);
      if (res.status() != Http.Status.OK) {
        return res;
      }
      res = importUniverseTservers(importForm, customer, results);
      if (res.status() != Http.Status.OK) {
        return res;
      }
      return finishUniverseImport(importForm, customer, results);
    } else {
      switch (importForm.currentState) {
        case BEGIN:
          return importUniverseMasters(importForm, customer, results);
        case IMPORTED_MASTERS:
          return importUniverseTservers(importForm, customer, results);
        case IMPORTED_TSERVERS:
          return finishUniverseImport(importForm, customer, results);
        case FINISHED:
          return YWResults.withData(results);
        default:
          throw new YWServiceException(
              BAD_REQUEST, "Unknown current state: " + importForm.currentState.toString());
      }
    }
  }

  // Helper function to convert comma seperated list of host:port into a list of host ips.
  // Returns null if there are parsing or invalid port errors.
  private Map<String, Integer> getMastersList(String masterAddresses) {
    Map<String, Integer> userMasterIpPorts = new HashMap<>();
    String[] nodesList = masterAddresses.split(",");
    for (String hostPort : nodesList) {
      String[] parts = hostPort.split(":");
      if (parts.length != 2) {
        log.error("Incorrect host:port format: " + hostPort);
        return null;
      }

      int portInt = 0;
      try {
        portInt = Integer.parseInt(parts[1]);
      } catch (NumberFormatException nfe) {
        log.error("Incorrect master rpc port '" + parts[1] + "', cannot be converted to integer.");
        return null;
      }

      userMasterIpPorts.put(parts[0], portInt);
    }
    return userMasterIpPorts;
  }

  // Helper function to verify masters are up and running on the saved set of nodes.
  // Returns true if there are no errors.
  private boolean verifyMastersRunning(
      UniverseDefinitionTaskParams taskParams, ImportUniverseResponseData results) {
    CheckMasters checkMasters = AbstractTaskBase.createTask(CheckMasters.class);
    checkMasters.initialize(taskParams);
    // Execute the task. If it fails, sets the error in the results.
    return executeITask(checkMasters, "check_masters_are_running", results);
  }

  // Helper function to check that a master leader exists.
  // Returns true if there are no errors.
  private boolean verifyMasterLeaderExists(
      UniverseDefinitionTaskParams taskParams, ImportUniverseResponseData results) {
    CheckMasterLeader checkMasterLeader = AbstractTaskBase.createTask(CheckMasterLeader.class);
    checkMasterLeader.initialize(taskParams);
    // Execute the task. If it fails, return an error.
    return executeITask(checkMasterLeader, "check_master_leader_election", results);
  }

  /** Given the master addresses, create a basic universe object. */
  private Result importUniverseMasters(
      ImportUniverseFormData importForm, Customer customer, ImportUniverseResponseData results) {
    String universeName = importForm.universeName;
    String masterAddresses = importForm.masterAddresses;

    if (universeName == null || universeName.isEmpty()) {
      throw new YWServiceException(BAD_REQUEST, "Null or empty universe name.");
    }

    if (!Util.isValidUniverseNameFormat(universeName)) {
      throw new YWServiceException(BAD_REQUEST, Util.UNIV_NAME_ERROR_MESG);
    }

    if (masterAddresses == null || masterAddresses.isEmpty()) {
      throw new YWServiceException(BAD_REQUEST, "Invalid master addresses list.");
    }

    masterAddresses = masterAddresses.replaceAll("\\s+", "");

    // ---------------------------------------------------------------------------------------------
    // Get the user specified master node ips.
    // ---------------------------------------------------------------------------------------------
    Map<String, Integer> userMasterIpPorts = getMastersList(masterAddresses);
    if (userMasterIpPorts == null) {
      throw new YWServiceException(
          BAD_REQUEST, "Could not parse host:port from masterAddresseses: " + masterAddresses);
    }

    // ---------------------------------------------------------------------------------------------
    // Create a universe object in the DB with the information so far: master info, cloud, etc.
    // ---------------------------------------------------------------------------------------------
    Universe universe = null;
    UniverseDefinitionTaskParams taskParams = null;
    // Attempt to find an existing universe with this id
    if (importForm.universeUUID != null) {
      universe = Universe.maybeGet(importForm.universeUUID).orElse(null);
    }

    try {
      if (null == universe) {
        universe =
            createNewUniverseForImport(
                customer,
                importForm,
                Util.getNodePrefix(customer.getCustomerId(), universeName),
                universeName,
                userMasterIpPorts);
      }

      List<Provider> providerList = Provider.get(customer.uuid, importForm.providerType);
      Provider provider = null;
      if (!providerList.isEmpty()) {
        provider = providerList.get(0);
      } else {
        // Understand about this better.
        results.checks.put("is_provider_present", "FAILURE");
        results.error =
            String.format(
                "Providers for the customer: %s and type: %s" + " are not present",
                customer.uuid, importForm.providerType);
        throw new YWServiceException(INTERNAL_SERVER_ERROR, results.error);
      }

      Region region = Region.getByCode(provider, importForm.regionCode);
      AvailabilityZone zone = AvailabilityZone.getByCode(provider, importForm.zoneCode);
      taskParams = universe.getUniverseDetails();
      // Update the universe object and refresh it.
      universe =
          addServersToUniverse(
              userMasterIpPorts, taskParams, provider, region, zone, true /*isMaster*/);
      results.checks.put("create_db_entry", "OK");
      results.universeUUID = universe.universeUUID;
    } catch (Exception e) {
      results.checks.put("create_db_entry", "FAILURE");
      results.error = e.getMessage();
      throw new YWServiceException(INTERNAL_SERVER_ERROR, Json.toJson(results));
    }
    taskParams = universe.getUniverseDetails();

    // ---------------------------------------------------------------------------------------------
    // Verify the master processes are running on the master nodes.
    // ---------------------------------------------------------------------------------------------
    if (!verifyMastersRunning(taskParams, results)) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, Json.toJson(results));
    }

    // ---------------------------------------------------------------------------------------------
    // Wait for the master leader election if needed.
    // ---------------------------------------------------------------------------------------------
    if (!verifyMasterLeaderExists(taskParams, results)) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, Json.toJson(results));
    }

    setImportedState(universe, ImportedState.MASTERS_ADDED);

    log.info("Done importing masters " + masterAddresses);
    results.state = State.IMPORTED_MASTERS;
    results.universeName = universeName;
    results.masterAddresses = masterAddresses;

    return YWResults.withData(results);
  }

  private void setImportedState(Universe universe, ImportedState newState) {
    UniverseUpdater updater =
        new UniverseUpdater() {
          public void run(Universe universe) {
            UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
            universeDetails.importedState = newState;
            universe.setUniverseDetails(universeDetails);
          }
        };
    Universe.saveDetails(universe.universeUUID, updater, false);
  }

  /** Import the various tablet servers from the masters. */
  private Result importUniverseTservers(
      ImportUniverseFormData importForm, Customer customer, ImportUniverseResponseData results) {
    String masterAddresses = importForm.masterAddresses;
    if (importForm.universeUUID == null || importForm.universeUUID.toString().isEmpty()) {
      results.error = "Valid universe uuid needs to be set instead of " + importForm.universeUUID;
      throw new YWServiceException(BAD_REQUEST, Json.toJson(results));
    }
    masterAddresses = masterAddresses.replaceAll("\\s+", "");

    Universe universe = Universe.getOrBadRequest(importForm.universeUUID);

    ImportedState curState = universe.getUniverseDetails().importedState;
    if (curState != ImportedState.MASTERS_ADDED) {
      results.error =
          "Unexpected universe state "
              + curState.name()
              + " expecteed "
              + ImportedState.MASTERS_ADDED.name();
      throw new YWServiceException(BAD_REQUEST, Json.toJson(results));
    }

    UniverseDefinitionTaskParams taskParams = universe.getUniverseDetails();
    // TODO: move this into a common location.
    results.universeUUID = universe.universeUUID;

    // ---------------------------------------------------------------------------------------------
    // Verify tservers count and list.
    // ---------------------------------------------------------------------------------------------
    Map<String, Integer> tservers_list = getTServers(masterAddresses, results);
    if (tservers_list.isEmpty()) {
      results.error = "No tservers known to the master leader in " + masterAddresses;
      throw new YWServiceException(INTERNAL_SERVER_ERROR, Json.toJson(results));
    }

    // Record the count of the tservers.
    results.tservers_count = tservers_list.size();
    for (String tserver : tservers_list.keySet()) {
      results.tservers_list.add(tserver);
    }

    // ---------------------------------------------------------------------------------------------
    // Update the universe object in the DB with new information : complete set of nodes.
    // ---------------------------------------------------------------------------------------------
    // Find the provider, region and zone. These should have been created during master info update.
    List<Provider> providerList = Provider.get(customer.uuid, importForm.providerType);
    Provider provider = null;
    if (!providerList.isEmpty()) {
      provider = providerList.get(0);
    } else {
      // Understand about this better.
      results.checks.put("is_provider_present", "FAILURE");
      results.error =
          String.format(
              "Providers for the customer: %s and type: %s" + " are not present",
              customer.uuid, importForm.providerType);
      throw new YWServiceException(INTERNAL_SERVER_ERROR, Json.toJson(results));
    }

    Region region = Region.getByCode(provider, importForm.regionCode);
    AvailabilityZone zone = AvailabilityZone.getByCode(provider, importForm.zoneCode);
    // Update the universe object and refresh it.
    universe =
        addServersToUniverse(tservers_list, taskParams, provider, region, zone, false /*isMaster*/);
    // Refresh the universe definition object as well.
    taskParams = universe.getUniverseDetails();

    // ---------------------------------------------------------------------------------------------
    // Verify that the tservers processes are running.
    // ---------------------------------------------------------------------------------------------
    CheckTServers checkTservers = AbstractTaskBase.createTask(CheckTServers.class);
    checkTservers.initialize(taskParams);
    // Execute the task. If it fails, return an error.
    if (!executeITask(checkTservers, "check_tservers_are_running", results)) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, results.error);
    }

    // ---------------------------------------------------------------------------------------------
    // Wait for all these tservers to heartbeat to the master leader.
    // ---------------------------------------------------------------------------------------------
    WaitForTServerHBs waitForTserverHBs = AbstractTaskBase.createTask(WaitForTServerHBs.class);
    waitForTserverHBs.initialize(taskParams);
    // Execute the task. If it fails, return an error.
    if (!executeITask(waitForTserverHBs, "check_tserver_heartbeats", results)) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, Json.toJson(results));
    }

    log.info("Verified " + tservers_list.size() + " tservers present and imported them.");

    setImportedState(universe, ImportedState.TSERVERS_ADDED);

    results.state = State.IMPORTED_TSERVERS;
    results.masterAddresses = masterAddresses;
    results.universeName = importForm.universeName;

    return YWResults.withData(results);
  }

  /**
   * Finalizes the universe in the database by: - setting up Prometheus config for metrics. - adding
   * the universe to the active list of universes for this customer. - checking if node_exporter is
   * reachable on all the nodes.
   */
  private Result finishUniverseImport(
      ImportUniverseFormData importForm, Customer customer, ImportUniverseResponseData results) {
    if (importForm.universeUUID == null || importForm.universeUUID.toString().isEmpty()) {
      results.error = "Valid universe uuid needs to be set.";
      throw new YWServiceException(BAD_REQUEST, results.error);
    }

    Universe universe = Universe.getOrBadRequest(importForm.universeUUID);

    ImportedState curState = universe.getUniverseDetails().importedState;
    if (curState != ImportedState.TSERVERS_ADDED) {
      results.error =
          "Unexpected universe state "
              + curState.name()
              + " expecteed "
              + ImportedState.TSERVERS_ADDED.name();
      return ApiResponse.error(BAD_REQUEST, results.error);
    }

    UniverseDefinitionTaskParams taskParams = universe.getUniverseDetails();
    // TODO: move to common location.
    results.universeUUID = universe.universeUUID;

    // ---------------------------------------------------------------------------------------------
    // Configure metrics.
    // ---------------------------------------------------------------------------------------------

    // TODO: verify we can reach the various YB ports.

    CreatePrometheusSwamperConfig createPrometheusSwamperConfig =
        AbstractTaskBase.createTask(CreatePrometheusSwamperConfig.class);
    createPrometheusSwamperConfig.initialize(taskParams);
    // Execute the task. If it fails, return an error.
    if (!executeITask(createPrometheusSwamperConfig, "create_prometheus_config", results)) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, Json.toJson(results));
    }

    // ---------------------------------------------------------------------------------------------
    // Check if node_exporter is enabled on all nodes.
    // ---------------------------------------------------------------------------------------------
    Map<String, String> nodeExporterIPsToError = new HashMap<String, String>();
    for (NodeDetails node : universe.getTServers()) {
      String nodeIP = node.cloudInfo.private_ip;
      int nodeExporterPort = universe.getUniverseDetails().communicationPorts.nodeExporterPort;
      String basePage = "http://" + nodeIP + ":" + nodeExporterPort;
      ObjectNode resp = apiHelper.getHeaderStatus(basePage + "/metrics");
      if (resp.isNull() || !resp.get("status").asText().equals("OK")) {
        nodeExporterIPsToError.put(
            nodeIP, resp.isNull() ? "Invalid response" : resp.get("status").asText());
      } else {
        String body = apiHelper.getBody(basePage);
        if (!body.contains(NODE_EXPORTER_RESP)) {
          nodeExporterIPsToError.put(
              nodeIP, basePage + "response does not contain '" + NODE_EXPORTER_RESP + "'");
        }
      }
    }
    results.checks.put("node_exporter", "OK");
    log.info("Errors per node: " + nodeExporterIPsToError.toString());
    if (!nodeExporterIPsToError.isEmpty()) {
      results.checks.put("node_exporter_ip_error_map", nodeExporterIPsToError.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // Create a simple redis table if needed.
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Update the DNS entry for all the clusters in this universe.
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Finalize universe entry in DB
    // ---------------------------------------------------------------------------------------------
    // Add the universe to the current user account
    customer.addUniverseUUID(universe.universeUUID);
    customer.save();

    results.state = State.FINISHED;

    log.info("Completed " + universe.universeUUID + " import.");

    return YWResults.withData(results);
  }

  /**
   * Helper function to add a list of servers into an existing universe. It creates a new node entry
   * if the node does not exist, otherwise it sets the appropriate flag (master/tserver) on the
   * existing node.
   */
  private Universe addServersToUniverse(
      Map<String, Integer> tserverList,
      UniverseDefinitionTaskParams taskParams,
      Provider provider,
      Region region,
      AvailabilityZone zone,
      boolean isMaster) {
    // Update the node details and persist into the DB.
    UniverseUpdater updater =
        new UniverseUpdater() {
          @Override
          public void run(Universe universe) {
            // Get the details of the universe to be updated.
            UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
            Cluster cluster = universeDetails.getPrimaryCluster();
            int index = cluster.index;

            for (Map.Entry<String, Integer> entry : tserverList.entrySet()) {
              // Check if this node is already present.
              NodeDetails node = universe.getNodeByPrivateIP(entry.getKey());
              if (node == null) {
                // If the node is not present, create the node details and add it.
                node =
                    createAndAddNode(
                        universeDetails,
                        entry.getKey(),
                        provider,
                        region,
                        zone,
                        index,
                        cluster.userIntent.instanceType);

                node.isMaster = isMaster;
              }

              UniverseTaskParams.CommunicationPorts.setCommunicationPorts(
                  universeDetails.communicationPorts, node);

              node.isTserver = !isMaster;
              if (isMaster) {
                node.masterRpcPort = entry.getValue();
              } else {
                node.tserverRpcPort = entry.getValue();
              }
              index++;
            }

            // Update the number of nodes in the user intent. TODO: update the correct cluster.
            cluster.userIntent.numNodes = tserverList.size();
            cluster.index = index;

            // Update the node details.
            universe.setUniverseDetails(universeDetails);
          }
        };
    // Save the updated universe object and return the updated universe.
    // saveUniverseDetails(taskParams.universeUUID);
    return Universe.saveDetails(taskParams.universeUUID, updater, false);
  }

  /**
   * This method queries the master leader and returns a list of tserver ip addresses. TODO: We need
   * to get the number of nodes information also from the end user and check that count matches what
   * master leader provides, to ensure no unreachable/failed tservers.
   */
  private Map<String, Integer> getTServers(
      String masterAddresses, ImportUniverseResponseData results) {
    Map<String, Integer> tservers_list = new HashMap<>();
    YBClient client = null;
    try {
      // Get the client to the YB master service.
      client = ybService.getClient(masterAddresses);

      // Fetch the tablet servers.
      ListTabletServersResponse listTServerResp = client.listTabletServers();

      for (ServerInfo tserver : listTServerResp.getTabletServersList()) {
        tservers_list.put(tserver.getHost(), tserver.getPort());
      }
      results.checks.put("find_tservers_list", "OK");
    } catch (Exception e) {
      log.error("Hit error: ", e);
      results.checks.put("find_tservers_list", "FAILURE");
    } finally {
      ybService.closeClient(client, masterAddresses);
    }

    return tservers_list;
  }

  /**
   * This method executes the passed in task on a threadpool and waits for it to complete. Upon a
   * successful completion of the task, it returns true and adds the stage that completed to the
   * results object. Upon a failure, it returns false and adds the appropriate error message into
   * the results object.
   */
  private boolean executeITask(ITask task, String taskName, ImportUniverseResponseData results) {
    // Initialize the threadpool if needed.
    initializeThreadpool();
    // Submit the task, and get a future object.
    Future<?> future = executor.submit(task);
    try {
      // Wait for the task to complete.
      future.get();
      // Indicate that this task executed successfully.
      results.checks.put(taskName, "OK");
    } catch (Exception e) {
      // If this task failed, return the failure and the reason.
      results.checks.put(taskName, "FAILURE");
      results.error = e.getMessage();
      log.error("Failed to execute " + taskName, e);
      return false;
    }
    return true;
  }

  /**
   * Creates a new universe object for the purpose of importing it. Note that we would not have all
   * the information about the universe at this point, so we are just creating the master ip parts.
   */
  private Universe createNewUniverseForImport(
      Customer customer,
      ImportUniverseFormData importForm,
      String nodePrefix,
      String universeName,
      Map<String, Integer> userMasterIpPorts) {
    // Find the provider by the code given, or create a new provider if one does not exist.
    List<Provider> providerList = Provider.get(customer.uuid, importForm.providerType);
    Provider provider = null;
    if (!providerList.isEmpty()) {
      provider = providerList.get(0);
    }
    if (provider == null) {
      provider = Provider.create(customer.uuid, importForm.providerType, importForm.cloudName);
    }
    // Find the region by the code given, or create a new provider if one does not exist.
    Region region = getOrCreateRegion(importForm, provider);
    // Find the zone by the code given, or create a new provider if one does not exist.
    AvailabilityZone zone =
        AvailabilityZone.maybeGetByCode(provider, importForm.zoneCode)
            .orElseGet(
                () ->
                    AvailabilityZone.createOrThrow(
                        region, importForm.zoneCode, importForm.zoneName, null));

    // Create the universe definition task params.
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    // We will assign this universe a random UUID if not given one.
    if (importForm.universeUUID == null) {
      taskParams.universeUUID = UUID.randomUUID();
    } else {
      taskParams.universeUUID = importForm.universeUUID;
    }
    taskParams.nodePrefix = nodePrefix;
    taskParams.importedState = ImportedState.STARTED;
    taskParams.capability = Capability.READ_ONLY;

    // Set the various details in the user intent.
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.universeName = universeName;
    userIntent.provider = provider.uuid.toString();
    userIntent.regionList = new ArrayList<UUID>();
    userIntent.regionList.add(region.uuid);
    userIntent.providerType = importForm.providerType;
    userIntent.instanceType = importForm.instanceType;
    userIntent.replicationFactor = importForm.replicationFactor;
    userIntent.enableYSQL = true;
    // Currently using YW version instead of YB version.
    // TODO: #1842: Create YBClient endpoint for getting ybSoftwareVersion.
    userIntent.ybSoftwareVersion =
        (String) configHelper.getConfig(ConfigHelper.ConfigType.SoftwareVersion).get("version");

    InstanceType.upsert(
        provider.uuid,
        importForm.instanceType,
        0 /* numCores */,
        0.0 /* memSizeGB */,
        new InstanceTypeDetails());

    // Placement info for imports default to the current cluster.
    PlacementInfo placementInfo = new PlacementInfo();

    PlacementCloud placementCloud = new PlacementCloud();
    placementCloud.uuid = provider.uuid;
    placementCloud.code = provider.code;
    placementCloud.regionList = new ArrayList<>();

    PlacementRegion placementRegion = new PlacementRegion();
    placementRegion.uuid = region.uuid;
    placementRegion.name = region.name;
    placementRegion.code = region.code;
    placementRegion.azList = new ArrayList<>();
    placementCloud.regionList.add(placementRegion);

    PlacementAZ placementAZ = new PlacementAZ();
    placementAZ.name = zone.name;
    placementAZ.subnet = zone.subnet;
    placementAZ.replicationFactor = 1;
    placementAZ.uuid = zone.uuid;
    placementAZ.numNodesInAZ = 1;
    placementRegion.azList.add(placementAZ);

    placementInfo.cloudList.add(placementCloud);

    // Add the placement info and user intent.
    Cluster cluster = taskParams.upsertPrimaryCluster(userIntent, placementInfo);
    // Create the node details set. This is a partial set that contains only the master nodes.
    taskParams.nodeDetailsSet = new HashSet<>();

    cluster.index = 1;

    // Return the universe we just created.
    return Universe.create(taskParams, customer.getCustomerId());
  }

  private Region getOrCreateRegion(ImportUniverseFormData importForm, Provider provider) {
    Region region = Region.getByCode(provider, importForm.regionCode);
    if (region == null) {
      // TODO: Find real coordinates instead of assuming somwhere in US west.
      double defaultLat = 37;
      double defaultLong = -121;
      region =
          Region.create(
              provider,
              importForm.regionCode,
              importForm.regionName,
              null,
              defaultLat,
              defaultLong);
    }
    return region;
  }

  // TODO: (Daniel) - Do I need to add communictionPorts here?
  // Create a new node with the given placement information.
  private NodeDetails createAndAddNode(
      UniverseDefinitionTaskParams taskParams,
      String nodeIP,
      Provider provider,
      Region region,
      AvailabilityZone zone,
      int index,
      String instanceType) {
    NodeDetails nodeDetails = new NodeDetails();
    // Set the node index and node details.
    nodeDetails.nodeIdx = index;
    nodeDetails.nodeName = taskParams.nodePrefix + Universe.NODEIDX_PREFIX + nodeDetails.nodeIdx;
    nodeDetails.azUuid = zone.uuid;

    // Set this node as a part of the primary cluster.
    nodeDetails.placementUuid = taskParams.getPrimaryCluster().uuid;

    // Set this node as live and running.
    nodeDetails.state = NodeDetails.NodeState.Live;

    // Set the node name and the private ip address. TODO: Set public ip.
    nodeDetails.cloudInfo = new CloudSpecificInfo();
    nodeDetails.cloudInfo.private_ip = nodeIP;
    nodeDetails.cloudInfo.cloud = provider.code;
    nodeDetails.cloudInfo.region = region.code;
    nodeDetails.cloudInfo.az = zone.code;
    nodeDetails.cloudInfo.instance_type = instanceType;

    taskParams.nodeDetailsSet.add(nodeDetails);

    return nodeDetails;
  }

  private void initializeThreadpool() {
    if (executor != null) {
      return;
    }

    // Initialize the tasks threadpool.
    ThreadFactory namedThreadFactory =
        new ThreadFactoryBuilder().setNameFormat("Import-Pool-%d").build();
    executor = Executors.newFixedThreadPool(TASK_THREADS, namedThreadFactory);
    log.trace("Started Import Thread Pool.");
  }
}
