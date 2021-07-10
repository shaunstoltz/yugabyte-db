/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.SubTaskGroup;
import com.yugabyte.yw.commissioner.SubTaskGroupQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.RemoveUniverseEntry;
import com.yugabyte.yw.common.DnsManager;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.models.AlertDefinitionGroup;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.filters.AlertDefinitionFilter;
import com.yugabyte.yw.models.filters.AlertDefinitionGroupFilter;
import com.yugabyte.yw.models.filters.AlertFilter;
import com.yugabyte.yw.models.helpers.EntityOperation;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.yugabyte.yw.models.helpers.EntityOperation.DELETE;
import static com.yugabyte.yw.models.helpers.EntityOperation.UPDATE;

@Slf4j
public class DestroyUniverse extends UniverseTaskBase {

  @Inject
  public DestroyUniverse(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  public static class Params extends UniverseTaskParams {
    public UUID customerUUID;
    public Boolean isForceDelete;
    public Boolean isDeleteBackups;
  }

  public Params params() {
    return (Params) taskParams;
  }

  @Override
  public void run() {
    try {
      // Create the task list sequence.
      subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      Universe universe;
      if (params().isForceDelete) {
        universe = forceLockUniverseForUpdate(-1, true);
      } else {
        universe = lockUniverseForUpdate(-1, true);
      }

      if (params().isDeleteBackups) {
        List<Backup> backupList =
            Backup.fetchByUniverseUUID(params().customerUUID, universe.universeUUID);
        createDeleteBackupTasks(backupList, params().customerUUID)
            .setSubTaskGroupType(SubTaskGroupType.DeletingBackup);
      }

      // Cleanup the kms_history table
      createDestroyEncryptionAtRestTask()
          .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      if (!universe.getUniverseDetails().isImportedUniverse()) {
        // Update the DNS entry for primary cluster to mirror creation.
        Cluster primaryCluster = universe.getUniverseDetails().getPrimaryCluster();
        createDnsManipulationTask(
                DnsManager.DnsCommandType.Delete, params().isForceDelete, primaryCluster.userIntent)
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

        if (primaryCluster.userIntent.providerType.equals(CloudType.onprem)) {
          // Stop master and tservers.
          createStopServerTasks(universe.getNodes(), "master", params().isForceDelete)
              .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
          createStopServerTasks(universe.getNodes(), "tserver", params().isForceDelete)
              .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
        }

        // Create tasks to destroy the existing nodes.
        createDestroyServerTasks(
                universe.getNodes(), params().isForceDelete, true /* delete node */)
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
      }

      // Create tasks to remove the universe entry from the Universe table.
      createRemoveUniverseEntryTask().setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      // Update the swamper target file.
      createSwamperTargetUpdateTask(true /* removeFile */);

      // Run all the tasks.
      subTaskGroupQueue.run();

      handleAlerts(universe);
    } catch (Throwable t) {
      // If for any reason destroy fails we would just unlock the universe for update
      try {
        unlockUniverseForUpdate();
      } catch (Throwable t1) {
        // Ignore the error
      }
      log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      throw t;
    }
    log.info("Finished {} task.", getName());
  }

  private void handleAlerts(Universe universe) {

    AlertDefinitionGroupFilter filter =
        AlertDefinitionGroupFilter.builder()
            .customerUuid(params().customerUUID)
            .targetType(AlertDefinitionGroup.TargetType.UNIVERSE)
            .build();

    List<AlertDefinitionGroup> groups =
        alertDefinitionGroupService
            .list(filter)
            .stream()
            .filter(
                group ->
                    group.getTarget().isAll()
                        || group.getTarget().getUuids().remove(universe.getUniverseUUID()))
            .collect(Collectors.toList());

    Map<EntityOperation, List<AlertDefinitionGroup>> toUpdateAndDelete =
        groups
            .stream()
            .collect(
                Collectors.groupingBy(
                    group ->
                        group.getTarget().isAll() || !group.getTarget().getUuids().isEmpty()
                            ? UPDATE
                            : DELETE));
    // Just need to save - service will create definition itself.
    alertDefinitionGroupService.save(toUpdateAndDelete.get(UPDATE));
    alertDefinitionGroupService.delete(toUpdateAndDelete.get(DELETE));

    // TODO - remove that once all alerts will be based on groups and definitions.
    AlertFilter alertFilter =
        AlertFilter.builder()
            .customerUuid(params().customerUUID)
            .label(KnownAlertLabels.TARGET_UUID, params().universeUUID.toString())
            .build();
    alertService.markResolved(alertFilter);
    alertDefinitionService.delete(
        AlertDefinitionFilter.builder()
            .customerUuid(params().customerUUID)
            .label(KnownAlertLabels.TARGET_UUID, params().universeUUID.toString())
            .build());
  }

  public SubTaskGroup createRemoveUniverseEntryTask() {
    SubTaskGroup subTaskGroup = new SubTaskGroup("RemoveUniverseEntry", executor);
    Params params = new Params();
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    params.customerUUID = params().customerUUID;
    params.isForceDelete = params().isForceDelete;

    // Create the Ansible task to destroy the server.
    RemoveUniverseEntry task = createTask(RemoveUniverseEntry.class);
    task.initialize(params);
    // Add it to the task list.
    subTaskGroup.addTask(task);
    subTaskGroupQueue.add(subTaskGroup);
    return subTaskGroup;
  }
}
