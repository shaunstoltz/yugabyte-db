/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1
 * .0.0.txt
 */

package com.yugabyte.yw.forms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yugabyte.yw.common.YWServiceException;
import play.libs.Json;
import play.mvc.Http;

import java.util.UUID;

import static play.mvc.Http.Status.BAD_REQUEST;

public class EncryptionAtRestKeyParams extends UniverseTaskParams {

  public static EncryptionAtRestKeyParams bindFromFormData(
      UUID universeUUID, Http.Request request) {
    EncryptionAtRestKeyParams taskParams = new EncryptionAtRestKeyParams();
    taskParams.universeUUID = universeUUID;
    try {
      taskParams.encryptionAtRestConfig =
          Json.mapper().treeToValue(request.body().asJson(), EncryptionAtRestConfig.class);
    } catch (JsonProcessingException e) {
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    }
    return taskParams;
  }
}
