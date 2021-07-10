/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.params.KMSConfigTaskParams;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.common.kms.EncryptionAtRestManager;
import com.yugabyte.yw.common.kms.services.SmartKeyEARService;
import com.yugabyte.yw.common.kms.util.EncryptionAtRestUtil;
import com.yugabyte.yw.common.kms.util.KeyProvider;
import com.yugabyte.yw.forms.YWResults;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Result;
import io.swagger.annotations.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Api(
    value = "Encryption At Rest",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class EncryptionAtRestController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(EncryptionAtRestController.class);

  private static Set<String> API_URL =
      ImmutableSet.of("api.amer.smartkey.io", "api.eu.smartkey.io", "api.uk.smartkey.io");

  public static final String AWS_ACCESS_KEY_ID_FIELDNAME = "AWS_ACCESS_KEY_ID";
  public static final String AWS_SECRET_ACCESS_KEY_FIELDNAME = "AWS_SECRET_ACCESS_KEY";
  public static final String AWS_REGION_FIELDNAME = "AWS_REGION";

  @Inject EncryptionAtRestManager keyManager;

  @Inject Commissioner commissioner;

  @Inject CloudAPI.Factory cloudAPIFactory;

  private void validateKMSProviderConfigFormData(ObjectNode formData, String keyProvider) {
    if (keyProvider.toUpperCase().equals(KeyProvider.AWS.toString())
        && (formData.get(AWS_ACCESS_KEY_ID_FIELDNAME) != null
            || formData.get(AWS_SECRET_ACCESS_KEY_FIELDNAME) != null)) {
      CloudAPI cloudAPI = cloudAPIFactory.get(KeyProvider.AWS.toString().toLowerCase());
      Map<String, String> config = new HashMap<>();
      config.put(
          AWS_ACCESS_KEY_ID_FIELDNAME, formData.get(AWS_ACCESS_KEY_ID_FIELDNAME).textValue());
      config.put(
          AWS_SECRET_ACCESS_KEY_FIELDNAME,
          formData.get(AWS_SECRET_ACCESS_KEY_FIELDNAME).textValue());
      if (cloudAPI != null
          && !cloudAPI.isValidCreds(config, formData.get(AWS_REGION_FIELDNAME).textValue())) {
        throw new YWServiceException(BAD_REQUEST, "Invalid AWS Credentials.");
      }
    }
    if (keyProvider.toUpperCase().equals(KeyProvider.SMARTKEY.toString())) {
      if (formData.get("base_url") == null
          || !EncryptionAtRestController.API_URL.contains(formData.get("base_url").textValue())) {
        throw new YWServiceException(BAD_REQUEST, "Invalid API URL.");
      }
      if (formData.get("api_key") != null) {
        try {
          Function<ObjectNode, String> token =
              new SmartKeyEARService()::retrieveSessionAuthorization;
          token.apply(formData);
        } catch (Exception e) {
          throw new YWServiceException(BAD_REQUEST, "Invalid API Key.");
        }
      }
    }
  }

  @ApiOperation(value = "Create KMS config", response = YWResults.YWTask.class)
  @ApiImplicitParams({
    @ApiImplicitParam(
        name = "KMS Config",
        value = "KMS Config to be created",
        required = true,
        dataType = "Object",
        paramType = "body")
  })
  public Result createKMSConfig(UUID customerUUID, String keyProvider) {
    LOG.info(
        String.format(
            "Creating KMS configuration for customer %s with %s",
            customerUUID.toString(), keyProvider));
    Customer customer = Customer.getOrBadRequest(customerUUID);
    try {
      TaskType taskType = TaskType.CreateKMSConfig;
      ObjectNode formData = (ObjectNode) request().body().asJson();
      // Validating the KMS Provider config details.
      validateKMSProviderConfigFormData(formData, keyProvider);
      KMSConfigTaskParams taskParams = new KMSConfigTaskParams();
      taskParams.kmsProvider = Enum.valueOf(KeyProvider.class, keyProvider);
      taskParams.providerConfig = formData;
      taskParams.customerUUID = customerUUID;
      taskParams.kmsConfigName = formData.get("name").asText();
      formData.remove("name");
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      LOG.info("Submitted create KMS config for {}, task uuid = {}.", customerUUID, taskUUID);
      // Add this task uuid to the user universe.
      CustomerTask.create(
          customer,
          customerUUID,
          taskUUID,
          CustomerTask.TargetType.KMSConfiguration,
          CustomerTask.TaskType.Create,
          taskParams.getName());
      LOG.info(
          "Saved task uuid " + taskUUID + " in customer tasks table for customer: " + customerUUID);

      auditService().createAuditEntry(ctx(), request(), formData);
      return new YWResults.YWTask(taskUUID).asResult();
    } catch (Exception e) {
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    }
  }

  @ApiOperation(
      value = "KMS config detail by config UUID",
      response = Object.class,
      responseContainer = "Map")
  public Result getKMSConfig(UUID customerUUID, UUID configUUID) {
    LOG.info(String.format("Retrieving KMS configuration %s", configUUID.toString()));
    KmsConfig config = KmsConfig.get(configUUID);
    ObjectNode kmsConfig =
        keyManager.getServiceInstance(config.keyProvider.name()).getAuthConfig(configUUID);
    if (kmsConfig == null) {
      throw new YWServiceException(
          BAD_REQUEST,
          String.format("No KMS configuration found for config %s", configUUID.toString()));
    }
    return YWResults.withRawData(kmsConfig);
  }

  @ApiOperation(value = "List KMS config", response = Object.class, responseContainer = "List")
  public Result listKMSConfigs(UUID customerUUID) {
    LOG.info(String.format("Listing KMS configurations for customer %s", customerUUID.toString()));
    List<JsonNode> kmsConfigs =
        KmsConfig.listKMSConfigs(customerUUID)
            .stream()
            .map(
                configModel -> {
                  ObjectNode result = null;
                  ObjectNode credentials =
                      keyManager
                          .getServiceInstance(configModel.keyProvider.name())
                          .getAuthConfig(configModel.configUUID);
                  if (credentials != null) {
                    result = Json.newObject();
                    ObjectNode metadata = Json.newObject();
                    metadata.put("configUUID", configModel.configUUID.toString());
                    metadata.put("provider", configModel.keyProvider.name());
                    metadata.put(
                        "in_use", EncryptionAtRestUtil.configInUse(configModel.configUUID));
                    metadata.put(
                        "universeDetails",
                        EncryptionAtRestUtil.getUniverses(configModel.configUUID));
                    metadata.put("name", configModel.name);
                    result.put("credentials", CommonUtils.maskConfig(credentials));
                    result.put("metadata", metadata);
                  }
                  return result;
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return YWResults.withData(kmsConfigs);
  }

  @ApiOperation(value = "Delete KMS config", response = YWResults.YWTask.class)
  public Result deleteKMSConfig(UUID customerUUID, UUID configUUID) {
    LOG.info(
        String.format(
            "Deleting KMS configuration %s for customer %s",
            configUUID.toString(), customerUUID.toString()));
    Customer customer = Customer.getOrBadRequest(customerUUID);
    try {
      KmsConfig config = KmsConfig.get(configUUID);
      TaskType taskType = TaskType.DeleteKMSConfig;
      KMSConfigTaskParams taskParams = new KMSConfigTaskParams();
      taskParams.kmsProvider = config.keyProvider;
      taskParams.customerUUID = customerUUID;
      taskParams.configUUID = configUUID;
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      LOG.info("Submitted delete KMS config for {}, task uuid = {}.", customerUUID, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(
          customer,
          customerUUID,
          taskUUID,
          CustomerTask.TargetType.KMSConfiguration,
          CustomerTask.TaskType.Delete,
          taskParams.getName());
      LOG.info(
          "Saved task uuid " + taskUUID + " in customer tasks table for customer: " + customerUUID);
      auditService().createAuditEntry(ctx(), request());
      return new YWResults.YWTask(taskUUID).asResult();
    } catch (Exception e) {
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    }
  }

  @ApiOperation(value = "Retrive KMS key", response = Object.class, responseContainer = "Map")
  public Result retrieveKey(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving universe key for universe %s",
            customerUUID.toString(), universeUUID.toString()));
    ObjectNode formData = (ObjectNode) request().body().asJson();
    byte[] keyRef = Base64.getDecoder().decode(formData.get("reference").asText());
    UUID configUUID = UUID.fromString(formData.get("configUUID").asText());
    byte[] recoveredKey = getRecoveredKeyOrBadRequest(universeUUID, configUUID, keyRef);
    ObjectNode result =
        Json.newObject()
            .put("reference", keyRef)
            .put("value", Base64.getEncoder().encodeToString(recoveredKey));
    auditService().createAuditEntry(ctx(), request(), formData);
    return YWResults.withRawData(result);
  }

  public byte[] getRecoveredKeyOrBadRequest(UUID universeUUID, UUID configUUID, byte[] keyRef) {
    byte[] recoveredKey = keyManager.getUniverseKey(universeUUID, configUUID, keyRef);
    if (recoveredKey == null || recoveredKey.length == 0) {
      final String errMsg =
          String.format("No universe key found for universe %s", universeUUID.toString());
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }
    return recoveredKey;
  }

  @ApiOperation(value = "Get key ref History", response = Object.class, responseContainer = "List")
  public Result getKeyRefHistory(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving key ref history for customer %s and universe %s",
            customerUUID.toString(), universeUUID.toString()));
    return YWResults.withData(
        KmsHistory.getAllTargetKeyRefs(universeUUID, KmsHistoryId.TargetType.UNIVERSE_KEY)
            .stream()
            .map(
                history -> {
                  return Json.newObject()
                      .put("reference", history.uuid.keyRef)
                      .put("configUUID", history.configUuid.toString())
                      .put("timestamp", history.timestamp.toString());
                })
            .collect(Collectors.toList()));
  }

  @ApiOperation(value = "Remove key ref History", response = YWResults.YWSuccess.class)
  public Result removeKeyRefHistory(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Removing key ref for customer %s with universe %s",
            customerUUID.toString(), universeUUID.toString()));
    keyManager.cleanupEncryptionAtRest(customerUUID, universeUUID);
    auditService().createAuditEntry(ctx(), request());
    return YWResults.YWSuccess.withMessage("Key ref was successfully removed");
  }

  @ApiOperation(value = "Get key ref", response = Object.class, responseContainer = "Map")
  public Result getCurrentKeyRef(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving key ref for customer %s and universe %s",
            customerUUID.toString(), universeUUID.toString()));
    KmsHistory activeKey = EncryptionAtRestUtil.getActiveKeyOrBadRequest(universeUUID);
    String keyRef = activeKey.uuid.keyRef;
    if (keyRef == null || keyRef.length() == 0) {
      throw new YWServiceException(
          BAD_REQUEST,
          String.format(
              "Could not retrieve key service for customer %s and universe %s",
              customerUUID.toString(), universeUUID.toString()));
    }
    return YWResults.withRawData(Json.newObject().put("reference", keyRef));
  }
}
