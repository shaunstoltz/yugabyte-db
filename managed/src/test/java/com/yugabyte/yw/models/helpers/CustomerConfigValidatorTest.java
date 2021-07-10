// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import play.libs.Json;

import static org.junit.Assert.assertEquals;

@RunWith(JUnitParamsRunner.class)
public class CustomerConfigValidatorTest {

  private CustomerConfigValidator customerConfigValidator;

  @Before
  public void setUp() {
    customerConfigValidator = new CustomerConfigValidator(null);
  }

  @Test
  // @formatter:off
  @Parameters({
    "NFS, BACKUP_LOCATION, /tmp, true",
    "NFS, BACKUP_LOCATION, tmp, false",
    "NFS, BACKUP_LOCATION, /mnt/storage, true",
    "NFS, BACKUP_LOCATION, //, true",
    "NFS, BACKUP_LOCATION, $(ping -c1 google.com.ru > /tmp/ping_log)/tmp/some/nfs/dir, false",
    "NFS, BACKUP_LOCATION,, false",
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, true",
    "S3, AWS_HOST_BASE, s3://backups.yugabyte.com/test/itest, false", // BACKUP_LOCATION undefined
    "S3, BACKUP_LOCATION, s3.amazonaws.com, true",
    "S3, BACKUP_LOCATION, ftp://s3.amazonaws.com, false",
    "S3, BACKUP_LOCATION,, false",
    "GCS, BACKUP_LOCATION, gs://itest-backup, true",
    "GCS, BACKUP_LOCATION, gcp.test.com, true",
    "GCS, BACKUP_LOCATION, ftp://gcp.test.com, false",
    "GCS, BACKUP_LOCATION,, false",
    "AZ, BACKUP_LOCATION, https://www.microsoft.com/azure, true",
    "AZ, BACKUP_LOCATION, http://www.microsoft.com/azure, true",
    "AZ, BACKUP_LOCATION, www.microsoft.com/azure, true",
    "AZ, BACKUP_LOCATION, ftp://www.microsoft.com/azure, false",
    "AZ, BACKUP_LOCATION,, false",
  })
  // @formatter:on
  public void testValidateDataContent_Storage_OneParamToCheck(
      String storageType, String fieldName, String fieldValue, boolean expectedResult) {
    ObjectNode data = Json.newObject().put(fieldName, fieldValue);
    ObjectNode result =
        customerConfigValidator.validateDataContent(createFormData("STORAGE", storageType, data));
    assertEquals(expectedResult, result.size() == 0);
  }

  @Test
  // @formatter:off
  @Parameters({
    // location - correct, aws_host_base - empty -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, AWS_HOST_BASE,, true",
    // location - correct, aws_host_base - incorrect -> disallowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, ftp://s3.amazonaws.com, false",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, s3.amazonaws.com, true",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, cloudstorage.onefs.dell.com, true",
    // location - incorrect, aws_host_base - correct -> disallowed
    "S3, BACKUP_LOCATION, ftp://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, s3.amazonaws.com, false",
    // location - incorrect, aws_host_base - empty -> disallowed
    "S3, BACKUP_LOCATION, ftp://backups.yugabyte.com/test/itest, AWS_HOST_BASE,, false",
    // location - empty, aws_host_base - correct -> disallowed
    "S3, BACKUP_LOCATION,, AWS_HOST_BASE, s3.amazonaws.com, false",
    // location - empty, aws_host_base - empty -> disallowed
    "S3, BACKUP_LOCATION,, AWS_HOST_BASE,, false",
  })
  // @formatter:on
  public void testValidateDataContent_Storage_TwoParamsToCheck(
      String storageType,
      String fieldName1,
      String fieldValue1,
      String fieldName2,
      String fieldValue2,
      boolean expectedResult) {
    ObjectNode data = Json.newObject();
    data.put(fieldName1, fieldValue1);
    data.put(fieldName2, fieldValue2);
    ObjectNode result =
        customerConfigValidator.validateDataContent(createFormData("STORAGE", storageType, data));
    assertEquals(expectedResult, result.size() == 0);
  }

  @Parameters({
    // Check invalid AWS Credentials -> disallowed
    "s3://test, The AWS Access Key Id you provided does not exist in our records.",
    // BACKUP_LOCATION - incorrect -> disallowed
    "s://abc, Invalid s3UriPath format: s://abc",
  })
  @Test
  public void testValidateDataContent_Storage_S3PreflightCheckValidator(
      String backupLocation, String expectedMessage) {
    ObjectNode data = Json.newObject();
    data.put(CustomerConfigValidator.BACKUP_LOCATION_FIELDNAME, backupLocation);
    data.put(CustomerConfigValidator.AWS_ACCESS_KEY_ID_FIELDNAME, "testAccessKey");
    data.put(CustomerConfigValidator.AWS_SECRET_ACCESS_KEY_FIELDNAME, "SecretKey");
    ObjectNode result =
        customerConfigValidator.validateDataContent(createFormData("STORAGE", "S3", data));
    assertEquals(1, result.size());
    assertEquals(
        expectedMessage,
        result.get(CustomerConfigValidator.BACKUP_LOCATION_FIELDNAME).get(0).asText());
  }

  @Parameters({
    // BACKUP_LOCATION - incorrect -> disallowed
    "g://abc, {}, Invalid gsUriPath format: g://abc",
    // Check empty GCP Credentials Json -> disallowed
    "gs://test, {}, Invalid GCP Credential Json.",
  })
  @Test
  public void testValidateDataContent_Storage_GCSPreflightCheckValidator(
      String backupLocation, String crdentialsJson, String expectedMessage) {
    ObjectNode data = Json.newObject();
    data.put(CustomerConfigValidator.BACKUP_LOCATION_FIELDNAME, backupLocation);
    data.put(CustomerConfigValidator.GCS_CREDENTIALS_JSON_FIELDNAME, crdentialsJson);
    ObjectNode result =
        customerConfigValidator.validateDataContent(createFormData("STORAGE", "GCS", data));
    assertEquals(1, result.size());
    assertEquals(
        expectedMessage,
        result.get(CustomerConfigValidator.BACKUP_LOCATION_FIELDNAME).get(0).asText());
  }

  private JsonNode createFormData(String type, String name, JsonNode data) {
    ObjectNode formData = Json.newObject();
    formData.put("type", type);
    formData.put("name", name);
    formData.put("data", data);
    return formData;
  }
}
