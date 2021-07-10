// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.common.YWServiceException;
import io.ebean.Finder;
import io.ebean.Model;
import io.ebean.annotation.CreatedTimestamp;
import io.ebean.annotation.DbJson;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.validation.Constraints;
import play.mvc.Http;

import javax.persistence.*;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static io.swagger.annotations.ApiModelProperty.AccessMode.*;
import static play.mvc.Http.Status.BAD_REQUEST;

@ApiModel(description = "Audit for audit logging of the requests and responses.")
@Entity
public class Audit extends Model {

  public static final Logger LOG = LoggerFactory.getLogger(Audit.class);

  // An auto incrementing, user-friendly id for the audit entry.
  @ApiModelProperty(value = "Audit uuid", accessMode = READ_ONLY)
  @Id
  @SequenceGenerator(name = "audit_id_seq", sequenceName = "audit_id_seq", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_id_seq")
  private Long id;

  public Long getAuditID() {
    return this.id;
  }

  @ApiModelProperty(value = "User uuid", accessMode = READ_ONLY)
  @Constraints.Required
  @Column(nullable = false)
  private UUID userUUID;

  public UUID getUserUUID() {
    return this.userUUID;
  }

  @ApiModelProperty(value = "Customer uuid", accessMode = READ_ONLY)
  @Constraints.Required
  @Column(nullable = false)
  private UUID customerUUID;

  public UUID getCustomerUUID() {
    return this.customerUUID;
  }

  // The task creation time.
  @CreatedTimestamp private final Date timestamp;

  public Date getTimestamp() {
    return this.timestamp;
  }

  @ApiModelProperty(value = "Audit uuid", accessMode = READ_ONLY)
  @Column(columnDefinition = "TEXT")
  @DbJson
  private JsonNode payload;

  public JsonNode getPayload() {
    return this.payload;
  }

  public void setPayload(JsonNode payload) {
    this.payload = payload;
    this.save();
  }

  @ApiModelProperty(
      value = "Api call",
      example = "/api/v1/customers/<496fdea8-df25-11eb-ba80-0242ac130004>/providers",
      accessMode = READ_ONLY)
  @Constraints.Required
  @Column(columnDefinition = "TEXT", nullable = false)
  private String apiCall;

  public String getApiCall() {
    return this.apiCall;
  }

  @ApiModelProperty(value = "API method", example = "GET", accessMode = READ_ONLY)
  @Constraints.Required
  @Column(columnDefinition = "TEXT", nullable = false)
  private String apiMethod;

  public String getApiMethod() {
    return this.apiMethod;
  }

  @ApiModelProperty(value = "Task UUID", accessMode = READ_ONLY)
  @Column(unique = true)
  private UUID taskUUID;

  public void setTaskUUID(UUID uuid) {
    this.taskUUID = uuid;
    this.save();
  }

  public UUID getTaskUUID() {
    return this.taskUUID;
  }

  public Audit() {
    this.timestamp = new Date();
  }

  public static final Finder<UUID, Audit> find = new Finder<UUID, Audit>(Audit.class) {};

  /**
   * Create new audit entry.
   *
   * @return Newly Created Audit table entry.
   */
  public static Audit create(
      UUID userUUID,
      UUID customerUUID,
      String apiCall,
      String apiMethod,
      JsonNode body,
      UUID taskUUID) {
    Audit entry = new Audit();
    entry.customerUUID = customerUUID;
    entry.userUUID = userUUID;
    entry.apiCall = apiCall;
    entry.apiMethod = apiMethod;
    entry.taskUUID = taskUUID;
    entry.payload = body;
    entry.save();
    return entry;
  }

  public static List<Audit> getAll(UUID customerUUID) {
    return find.query().where().eq("customer_uuid", customerUUID).findList();
  }

  public static Audit getFromTaskUUID(UUID taskUUID) {
    return find.query().where().eq("task_uuid", taskUUID).findOne();
  }

  public static Audit getOrBadRequest(UUID customerUUID, UUID taskUUID) {
    Customer.getOrBadRequest(customerUUID);
    Audit entry =
        find.query().where().eq("task_uuid", taskUUID).eq("customer_uuid", customerUUID).findOne();
    if (entry == null) {
      throw new YWServiceException(
          BAD_REQUEST, "Task " + taskUUID + " does not belong to customer " + customerUUID);
    }
    return entry;
  }

  public static List<Audit> getAllUserEntries(UUID userUUID) {
    return find.query().where().eq("user_uuid", userUUID).findList();
  }

  public static void forEachEntry(Consumer<Audit> consumer) {
    find.query().findEach(consumer);
  }
}
