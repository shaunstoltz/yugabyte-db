/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.commissioner;

import com.typesafe.config.Config;
import com.yugabyte.yw.common.ConfigHelper;
import com.yugabyte.yw.common.TableManager;
import com.yugabyte.yw.common.alerts.AlertDefinitionGroupService;
import com.yugabyte.yw.common.alerts.AlertDefinitionService;
import com.yugabyte.yw.common.alerts.AlertService;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.services.YBClientService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import play.Application;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@Getter
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class BaseTaskDependencies {

  private final Application application;
  private final play.Environment environment;
  private final Config config;
  private final ConfigHelper configHelper;
  private final RuntimeConfigFactory runtimeConfigFactory;
  private final AlertService alertService;
  private final AlertDefinitionService alertDefinitionService;
  private final AlertDefinitionGroupService alertDefinitionGroupService;
  private final YBClientService ybService;
  private final TableManager tableManager;
}
