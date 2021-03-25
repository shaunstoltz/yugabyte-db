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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.inject.Inject;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.common.ha.PlatformReplicationManager;
import com.yugabyte.yw.forms.DemoteInstanceFormData;
import com.yugabyte.yw.models.HighAvailabilityConfig;
import com.yugabyte.yw.models.PlatformInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.data.FormFactory;
import play.libs.Files;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.With;

import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

@With(HAAuthenticator.class)
public class InternalHAController extends Controller {

  public static final Logger LOG = LoggerFactory.getLogger(InternalHAController.class);

  private final PlatformReplicationManager replicationManager;
  private final FormFactory formFactory;

  @Inject
  InternalHAController(PlatformReplicationManager replicationManager, FormFactory formFactory) {
    this.replicationManager = replicationManager;
    this.formFactory = formFactory;
  }

  private String getClusterKey() {
    return ctx().request().header(HAAuthenticator.HA_CLUSTER_KEY_TOKEN_HEADER).get();
  }

  public Result getHAConfigByClusterKey() {
    try {
      Optional<HighAvailabilityConfig> config =
        HighAvailabilityConfig.getByClusterKey(this.getClusterKey());

      if (!config.isPresent()) {
        return ApiResponse.error(NOT_FOUND, "Could not find HA Config by cluster key");
      }

      return ApiResponse.success(config.get());
    } catch (Exception e) {
      LOG.error("Error retrieving HA config");

      return ApiResponse.error(INTERNAL_SERVER_ERROR, "Error retrieving HA config");
    }
  }

  public Result syncInstances(long timestamp) {
    try {
      Optional<HighAvailabilityConfig> config =
        HighAvailabilityConfig.getByClusterKey(this.getClusterKey());
      if (!config.isPresent()) {
        return ApiResponse.error(NOT_FOUND, "Invalid config UUID");
      }

      Optional<PlatformInstance> localInstance = config.get().getLocal();

      if (!localInstance.isPresent()) {
        LOG.warn("No local instance configured");

        return ApiResponse.error(BAD_REQUEST, "No local instance configured");
      }

      if (localInstance.get().getIsLeader()) {
        LOG.warn(
          "Rejecting request to import instances due to this process being designated a leader"
        );

        return ApiResponse.error(BAD_REQUEST, "Cannot import instances for a leader");
      }

      Date requestLastFailover = new Date(timestamp);
      Date localLastFailover = config.get().getLastFailover();

      // Reject the request if coming from a platform instance that was failed over to earlier.
      if (localLastFailover != null && localLastFailover.after(requestLastFailover)) {
        LOG.warn("Rejecting request to import instances due to request lastFailover being stale");

        return ApiResponse.error(BAD_REQUEST, "Cannot import instances from stale leader");
      }

      Set<PlatformInstance> processedInstances = replicationManager.importPlatformInstances(
        config.get(),
        (ArrayNode) request().body().asJson()
      );

      return ApiResponse.success(processedInstances);
    } catch (Exception e) {
      LOG.error("Error importing platform instances", e);

      return ApiResponse.error(INTERNAL_SERVER_ERROR, "Error importing platform instances");
    }
  }

  public Result syncBackups() throws Exception {
    Http.MultipartFormData<Files.TemporaryFile> body = request().body().asMultipartFormData();

    Map<String, String[]> reqParams = body.asFormUrlEncoded();
    String[] leaders = reqParams.getOrDefault("leader", new String[0]);
    String[] senders = reqParams.getOrDefault("sender", new String[0]);
    if (reqParams.size() != 2 || leaders.length != 1 || senders.length != 1) {
      return ApiResponse.error(BAD_REQUEST,
        "Expected exactly 2 (leader and sender) argument in 'application/x-www-form-urlencoded' " +
          "data part. Received: " + reqParams);
    }
    Http.MultipartFormData.FilePart<Files.TemporaryFile> filePart = body.getFile("backup");
    if (filePart == null) {
      return ApiResponse.error(BAD_REQUEST, "backup file not found in request");
    }
    String fileName = filePart.getFilename();
    File temporaryFile = (File) filePart.getFile();
    String leader = leaders[0];
    String sender = senders[0];

    if (!leader.equals(sender)) {
      return ApiResponse.error(BAD_REQUEST, "Sender: " + sender +
        " does not match leader: " + leader);
    }

    Optional<HighAvailabilityConfig> config =
      HighAvailabilityConfig.getByClusterKey(this.getClusterKey());
    if (!config.isPresent()) {
      return ApiResponse.error(BAD_REQUEST, "Could not find HA Config");
    }

    Optional<PlatformInstance> localInstance = config.get().getLocal();
    if (localInstance.isPresent() && leader.equals(localInstance.get().getAddress())) {
      return ApiResponse.error(BAD_REQUEST,
        "Backup originated on the node itself. Leader: " + leader);
    }

    URL leaderUrl = new URL(leader);

    // For all the other cases we will accept the backup without checking local config state.
    boolean success = replicationManager.saveReplicationData(
      fileName, temporaryFile, leaderUrl, new URL(sender));
    if (success) {
      // TODO: (Daniel) - Need to cleanup backups in non-current leader dir too.
      replicationManager.cleanupReceivedBackups(leaderUrl);
      return ApiResponse.success("File uploaded");
    } else {
      return ApiResponse.error(INTERNAL_SERVER_ERROR, "failed to copy backup");
    }
  }

  public Result demoteLocalLeader(long timestamp) {
    try {
      Optional<HighAvailabilityConfig> config =
        HighAvailabilityConfig.getByClusterKey(this.getClusterKey());
      if (!config.isPresent()) {
        LOG.warn("No HA configuration configured, skipping request");

        return ApiResponse.error(NOT_FOUND, "Invalid config UUID");
      }

      Form<DemoteInstanceFormData> formData =
        formFactory.form(DemoteInstanceFormData.class).bindFromRequest();
      if (formData.hasErrors()) {
        return ApiResponse.error(BAD_REQUEST, formData.errorsAsJson());
      }

      Optional<PlatformInstance> localInstance = config.get().getLocal();

      if (!localInstance.isPresent()) {
        LOG.warn("No local instance configured");

        return ApiResponse.error(BAD_REQUEST, "No local instance configured");
      }

      Date requestLastFailover = new Date(timestamp);
      Date localLastFailover = config.get().getLastFailover();

      // Reject the request if coming from a platform instance that was failed over to earlier.
      if (localLastFailover != null && localLastFailover.after(requestLastFailover)) {
        LOG.warn("Rejecting demote request due to request lastFailover being stale");

        return ApiResponse.error(BAD_REQUEST, "Rejecting demote request from stale leader");
      } else if (localLastFailover == null || localLastFailover.before(requestLastFailover)) {
        // Otherwise, update the last failover timestamp and proceed with demotion request.
        config.get().setLastFailover(requestLastFailover);
      }

      // Demote the local instance.
      replicationManager.demoteLocalInstance(localInstance.get(), formData.get().leader_address);

      return ApiResponse.success(localInstance);
    } catch (Exception e) {
      LOG.error("Error demoting platform instance", e);

      return ApiResponse.error(INTERNAL_SERVER_ERROR, "Error demoting platform instance");
    }
  }
}
