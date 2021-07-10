/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks.subtasks.cloud;

import com.google.common.base.Strings;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.tasks.CloudBootstrap;
import com.yugabyte.yw.commissioner.tasks.CloudTaskBase;
import com.yugabyte.yw.common.AccessManager;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Region;
import play.api.Play;

import javax.inject.Inject;

public class CloudAccessKeySetup extends CloudTaskBase {
  @Inject
  protected CloudAccessKeySetup(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  public static class Params extends CloudBootstrap.Params {
    public String regionCode;
  }

  @Override
  protected Params taskParams() {
    return (Params) taskParams;
  }

  @Override
  public void run() {
    String regionCode = taskParams().regionCode;
    Region region = Region.getByCode(getProvider(), regionCode);
    if (region == null) {
      throw new RuntimeException("Region " + regionCode + " not setup.");
    }
    AccessManager accessManager = Play.current().injector().instanceOf(AccessManager.class);

    // TODO(bogdan): validation at higher level?
    String accessKeyCode = taskParams().keyPairName;
    if (Strings.isNullOrEmpty(accessKeyCode)) {
      String sanitizedProviderName = getProvider().name.replaceAll("\\s+", "-").toLowerCase();
      accessKeyCode =
          String.format(
              "yb-%s-%s_%s-key",
              Customer.get(getProvider().customerUUID).code,
              sanitizedProviderName,
              taskParams().providerUUID);
    }

    if (!Strings.isNullOrEmpty(taskParams().sshPrivateKeyContent)) {
      accessManager.saveAndAddKey(
          region.uuid,
          taskParams().sshPrivateKeyContent,
          accessKeyCode,
          AccessManager.KeyType.PRIVATE,
          taskParams().sshUser,
          taskParams().sshPort,
          taskParams().airGapInstall,
          false);
    } else {
      accessManager.addKey(
          region.uuid,
          accessKeyCode,
          null,
          taskParams().sshUser,
          taskParams().sshPort,
          taskParams().airGapInstall,
          false);
    }
  }
}
