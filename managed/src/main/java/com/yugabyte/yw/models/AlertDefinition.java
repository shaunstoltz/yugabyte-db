/*
 * Copyright 2020 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yugabyte.yw.models.filters.AlertDefinitionFilter;
import com.yugabyte.yw.models.helpers.KnownAlertCodes;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import io.ebean.ExpressionList;
import io.ebean.Finder;
import io.ebean.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import play.data.validation.Constraints;

import javax.persistence.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.yugabyte.yw.models.helpers.CommonUtils.*;

@Entity
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AlertDefinition extends Model {

  @Constraints.Required
  @Id
  @Column(nullable = false, unique = true)
  private UUID uuid;

  @Constraints.Required
  @Column(columnDefinition = "Text", nullable = false)
  private String query;

  @Constraints.Required
  @Column(nullable = false)
  private UUID customerUUID;

  @Constraints.Required
  @Column(nullable = false)
  private UUID groupUUID;

  @Constraints.Required
  @Column(nullable = false)
  @JsonIgnore
  private boolean configWritten = false;

  @Version
  @Column(nullable = false)
  private int version;

  @OneToMany(mappedBy = "definition", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<AlertDefinitionLabel> labels;

  private static final Finder<UUID, AlertDefinition> find =
      new Finder<UUID, AlertDefinition>(AlertDefinition.class) {};

  public static ExpressionList<AlertDefinition> createQueryByFilter(AlertDefinitionFilter filter) {
    ExpressionList<AlertDefinition> query = find.query().fetch("labels").where();
    appendInClause(query, "uuid", filter.getUuids());
    if (filter.getCustomerUuid() != null) {
      query.eq("customerUUID", filter.getCustomerUuid());
    }
    appendInClause(query, "groupUUID", filter.getGroupUuids());
    if (filter.getConfigWritten() != null) {
      query.eq("configWritten", filter.getConfigWritten());
    }
    if (filter.getLabel() != null) {
      query
          .eq("labels.key.name", filter.getLabel().getName())
          .eq("labels.value", filter.getLabel().getValue());
    }
    return query;
  }

  public AlertDefinition generateUUID() {
    this.uuid = UUID.randomUUID();
    this.labels.forEach(label -> label.setDefinition(this));
    return this;
  }

  @JsonIgnore
  public boolean isNew() {
    return uuid == null;
  }

  public List<AlertDefinitionLabel> getEffectiveLabels(
      AlertDefinitionGroup group, AlertDefinitionGroup.Severity severity) {
    List<AlertDefinitionLabel> effectiveLabels = new ArrayList<>();
    effectiveLabels.add(
        new AlertDefinitionLabel(this, KnownAlertLabels.GROUP_UUID, group.getUuid().toString()));
    effectiveLabels.add(
        new AlertDefinitionLabel(this, KnownAlertLabels.GROUP_TYPE, group.getTargetType().name()));
    effectiveLabels.add(
        new AlertDefinitionLabel(this, KnownAlertLabels.DEFINITION_UUID, uuid.toString()));
    effectiveLabels.add(
        new AlertDefinitionLabel(this, KnownAlertLabels.DEFINITION_NAME, group.getName()));
    effectiveLabels.add(
        new AlertDefinitionLabel(
            this, KnownAlertLabels.DEFINITION_ACTIVE, String.valueOf(group.isActive())));
    effectiveLabels.add(
        new AlertDefinitionLabel(this, KnownAlertLabels.CUSTOMER_UUID, customerUUID.toString()));
    effectiveLabels.add(
        new AlertDefinitionLabel(
            this, KnownAlertLabels.ERROR_CODE, KnownAlertCodes.CUSTOMER_ALERT.name()));
    effectiveLabels.add(new AlertDefinitionLabel(this, KnownAlertLabels.SEVERITY, severity.name()));
    effectiveLabels.addAll(labels);
    return effectiveLabels;
  }

  public UUID getUniverseUUID() {
    return Optional.ofNullable(getLabelValue(KnownAlertLabels.UNIVERSE_UUID))
        .map(UUID::fromString)
        .orElseThrow(
            () -> new IllegalStateException("Definition " + uuid + " does not have universe UUID"));
  }

  public String getLabelValue(KnownAlertLabels knownLabel) {
    return getLabelValue(knownLabel.labelName());
  }

  public String getLabelValue(String name) {
    return getLabels()
        .stream()
        .filter(label -> name.equals(label.getName()))
        .map(AlertDefinitionLabel::getValue)
        .findFirst()
        .orElse(null);
  }

  public AlertDefinition setLabel(KnownAlertLabels label, String value) {
    return setLabel(label.labelName(), value);
  }

  public AlertDefinition setLabel(String name, String value) {
    AlertDefinitionLabel toAdd = new AlertDefinitionLabel(this, name, value);
    this.labels = setUniqueListValue(labels, toAdd);
    return this;
  }

  public AlertDefinition setLabels(List<AlertDefinitionLabel> labels) {
    this.labels = setUniqueListValues(this.labels, labels);
    this.labels.forEach(label -> label.setDefinition(this));
    return this;
  }

  public List<AlertDefinitionLabel> getLabels() {
    return labels
        .stream()
        .sorted(Comparator.comparing(AlertDefinitionLabel::getName))
        .collect(Collectors.toList());
  }
}
