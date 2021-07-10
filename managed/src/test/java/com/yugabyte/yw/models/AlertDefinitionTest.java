// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.models;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.alerts.AlertDefinitionService;
import com.yugabyte.yw.models.filters.AlertDefinitionFilter;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

import javax.persistence.OptimisticLockException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

@RunWith(MockitoJUnitRunner.class)
public class AlertDefinitionTest extends FakeDBApplication {

  private static final String TEST_DEFINITION_QUERY = "some_metric > 100";

  private static final String TEST_LABEL = "test_label";
  private static final String TEST_LABEL_VALUE = "test_value";

  private static final String TEST_LABEL_2 = "test_label_2";
  private static final String TEST_LABEL_VALUE_2 = "test_value2";

  private Customer customer;

  private Universe universe;

  private AlertDefinitionGroup group;

  @InjectMocks private AlertDefinitionService alertDefinitionService;

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer("Customer");
    universe = ModelFactory.createUniverse();
    group = ModelFactory.createAlertDefinitionGroup(customer, universe);
  }

  @Test
  public void testAddAndQueryByUUID() {
    AlertDefinition definition = createTestDefinition1();
    createTestDefinition2();

    AlertDefinition queriedDefinition = alertDefinitionService.get(definition.getUuid());

    assertTestDefinition1(queriedDefinition);
  }

  @Test
  public void testQueryByCustomerUniverse() {
    createTestDefinition1();

    List<AlertDefinition> queriedDefinitions =
        alertDefinitionService.list(
            AlertDefinitionFilter.builder()
                .customerUuid(customer.uuid)
                .label(KnownAlertLabels.UNIVERSE_UUID, universe.universeUUID.toString())
                .build());

    assertThat(queriedDefinitions, hasSize(1));
    assertTestDefinition1(queriedDefinitions.get(0));
  }

  @Test
  public void testQueryByCustomerLabel() {
    createTestDefinition1();
    createTestDefinition2();

    AlertDefinitionLabel label1 = new AlertDefinitionLabel(TEST_LABEL, TEST_LABEL_VALUE);
    List<AlertDefinition> queriedDefinitions =
        alertDefinitionService.list(
            AlertDefinitionFilter.builder().customerUuid(customer.uuid).label(label1).build());

    assertThat(queriedDefinitions, hasSize(1));
    assertTestDefinition1(queriedDefinitions.get(0));
  }

  @Test
  public void testUpdateAndQueryByLabel() {
    AlertDefinition definition = createTestDefinition2();

    AlertDefinitionLabel label2 = new AlertDefinitionLabel(TEST_LABEL_2, TEST_LABEL_VALUE_2);

    String newQuery = "qwewqewqe";
    definition.setQuery(newQuery);
    definition.setLabels(ImmutableList.of(label2));
    alertDefinitionService.save(definition);

    AlertDefinitionLabel label1 = new AlertDefinitionLabel(TEST_LABEL, TEST_LABEL_VALUE);
    List<AlertDefinition> queriedDefinitions =
        alertDefinitionService.list(
            AlertDefinitionFilter.builder().customerUuid(customer.uuid).label(label2).build());

    List<AlertDefinition> queriedByOldLabelDefinitions =
        alertDefinitionService.list(
            AlertDefinitionFilter.builder().customerUuid(customer.uuid).label(label1).build());

    assertThat(queriedDefinitions, hasSize(1));
    assertThat(queriedByOldLabelDefinitions, empty());

    AlertDefinition queriedDefinition = queriedDefinitions.get(0);
    assertThat(queriedDefinition.getCustomerUUID(), equalTo(customer.uuid));
    assertThat(queriedDefinition.getQuery(), equalTo(newQuery));

    assertThat(queriedDefinition.getLabelValue(TEST_LABEL_2), equalTo(TEST_LABEL_VALUE_2));
  }

  @Test
  public void testOptimisticLocking() {
    AlertDefinition definition = createTestDefinition1();

    AlertDefinition createdDefinition = alertDefinitionService.get(definition.getUuid());

    String newQuery = "qwewqewqe";
    definition.setQuery(newQuery);
    alertDefinitionService.save(definition);

    createdDefinition.setConfigWritten(true);
    assertThrows(
        OptimisticLockException.class, () -> alertDefinitionService.save(createdDefinition));
  }

  @Test
  public void testDelete() {
    AlertDefinition definition = createTestDefinition1();

    definition.delete();

    AlertDefinition queriedDefinition = alertDefinitionService.get(definition.getUuid());

    assertThat(queriedDefinition, nullValue());
  }

  private AlertDefinition createTestDefinition1() {
    AlertDefinitionLabel label1 = new AlertDefinitionLabel(TEST_LABEL, TEST_LABEL_VALUE);
    AlertDefinitionLabel knownLabel =
        new AlertDefinitionLabel(KnownAlertLabels.UNIVERSE_UUID, universe.universeUUID.toString());
    AlertDefinition definition =
        new AlertDefinition()
            .setCustomerUUID(customer.uuid)
            .setGroupUUID(group.getUuid())
            .setQuery(TEST_DEFINITION_QUERY)
            .setLabels(Arrays.asList(label1, knownLabel));
    return alertDefinitionService.save(definition);
  }

  private AlertDefinition createTestDefinition2() {
    AlertDefinitionLabel label2 = new AlertDefinitionLabel(TEST_LABEL_2, TEST_LABEL_VALUE_2);
    AlertDefinition definition =
        new AlertDefinition()
            .setCustomerUUID(customer.uuid)
            .setGroupUUID(group.getUuid())
            .setQuery(TEST_DEFINITION_QUERY)
            .setLabels(Collections.singletonList(label2));
    return alertDefinitionService.save(definition);
  }

  private void assertTestDefinition1(AlertDefinition definition) {
    AlertDefinitionLabel label1 =
        new AlertDefinitionLabel(definition, TEST_LABEL, TEST_LABEL_VALUE);
    label1.setDefinition(definition);
    AlertDefinitionLabel knownLabel =
        new AlertDefinitionLabel(
            definition, KnownAlertLabels.UNIVERSE_UUID, universe.universeUUID.toString());
    assertThat(definition.getCustomerUUID(), equalTo(customer.uuid));
    assertThat(definition.getQuery(), equalTo(TEST_DEFINITION_QUERY));
    assertFalse(definition.isConfigWritten());
    assertThat(definition.getLabels(), containsInAnyOrder(label1, knownLabel));
  }
}
