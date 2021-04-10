/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.TableManager;
import com.yugabyte.yw.forms.AbstractTaskParams;
import com.yugabyte.yw.forms.BackupTableParams;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.models.Backup;
import play.api.Play;
import play.libs.Json;

import java.util.List;
import java.util.UUID;


public class DeleteBackup extends AbstractTaskBase {

  public static class Params extends AbstractTaskParams {
    public UUID customerUUID;
    public UUID backupUUID;
  }

  public Params params() {
    return (Params) taskParams;
  }

  private TableManager tableManager;

  @Override
  public void initialize(ITaskParams params) {
    super.initialize(params);
    tableManager = Play.current().injector().instanceOf(TableManager.class);
  }

  @Override
  public void run() {
    Backup backup = Backup.get(params().customerUUID, params().backupUUID);
    if (backup.state != Backup.BackupState.Completed) {
      // TODO: Allow deletion of InProgress backups. But not sure if backend supports it
      //  and may not be worth the effort.
      LOG.error("Cannot delete backup in any other state other than completed.");
      return;
    }
    try {
      BackupTableParams backupParams = Json.fromJson(backup.backupInfo, BackupTableParams.class);
      List<BackupTableParams> backupList =
        backupParams.backupList == null ? ImmutableList.of(backupParams) : backupParams.backupList;
      if (deleteAllBackups(backupList)) {
        transitionState(backup, Backup.BackupState.Deleted);
        return;
      }
    } catch (Exception ex) {
      LOG.error("Unexpected error in DeleteBackup {}. We will ignore the error and Mark the " +
        "backup as failed to be deleted and remove it from scheduled cleanup.",
        params().backupUUID, ex);
    }
    transitionState(backup, Backup.BackupState.FailedToDelete);
  }

  private static void transitionState(Backup backup, Backup.BackupState newState) {
    if (backup != null) {
      backup.transitionState(newState);
    }
  }

  private boolean deleteAllBackups(List<BackupTableParams> backupList) {
    boolean success = true;
    for (BackupTableParams childBackupParams : backupList) {
      if (!deleteBackup(childBackupParams)) {
        success = false;
      }
    }
    return success;
  }

  private boolean deleteBackup(BackupTableParams backupTableParams) {
    backupTableParams.actionType = BackupTableParams.ActionType.DELETE;
    ShellResponse response = tableManager.deleteBackup(backupTableParams);
    JsonNode jsonNode = Json.parse(response.message);
    if (response.code != 0 || jsonNode.has("error")) {
      LOG.error("Delete Backup failed for {}. Response code={}, hasError={}.",
        backupTableParams.storageLocation, response.code, jsonNode.has("error"));
      return false;
    } else {
      LOG.info("[" + getName() + "] STDOUT: " + response.message);
      return true;
    }
  }
}
