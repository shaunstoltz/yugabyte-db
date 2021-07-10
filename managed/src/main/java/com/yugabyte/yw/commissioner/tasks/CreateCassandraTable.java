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
import com.yugabyte.yw.commissioner.SubTaskGroupQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.forms.TableDefinitionTaskParams;
import lombok.extern.slf4j.Slf4j;
import org.yb.Common;

import javax.inject.Inject;

@Slf4j
public class CreateCassandraTable extends UniverseTaskBase {

  @Inject
  protected CreateCassandraTable(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  protected TableDefinitionTaskParams taskParams() {
    return (TableDefinitionTaskParams) taskParams;
  }

  @Override
  public void run() {
    try {
      // Create the task list sequence.
      subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening. Does not alter 'updateSucceeded' flag so as not
      // to lock out the universe completely in case this task fails.
      lockUniverse(-1 /* expectedUniverseVersion */);

      // Create table task
      createTableTask(
              Common.TableType.YQL_TABLE_TYPE,
              taskParams().tableDetails.tableName,
              taskParams().tableDetails)
          .setSubTaskGroupType(SubTaskGroupType.CreatingTable);

      // TODO: wait for table creation

      // Run all the tasks.
      subTaskGroupQueue.run();
    } catch (Throwable t) {
      log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      throw t;
    } finally {
      // Mark the update of the universe as done. This will allow any other pending tasks against
      // the universe to execute.
      unlockUniverseForUpdate();
    }
    log.info("Finished {} task.", getName());
  }
}
