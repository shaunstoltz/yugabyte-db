// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Users;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

import java.io.IOException;
import java.util.UUID;

import static com.yugabyte.yw.common.AssertHelper.assertYWSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static play.mvc.Http.Status.*;
import static play.test.Helpers.*;

public class AuditControllerTest extends FakeDBApplication {
  String baseRoute = "/api/customers/%s/";

  private Customer customer1, customer2;
  private Users user1, user2;
  private String authToken1, authToken2;
  private UUID taskUUID1, taskUUID2;
  private Audit audit1, audit2, audit3, audit4;

  @Before
  public void setUp() {
    customer1 = ModelFactory.testCustomer("tc1", "Test Customer 1");
    customer2 = ModelFactory.testCustomer("tc2", "Test Customer 2");
    user1 = ModelFactory.testUser(customer1, "tc1@test.com");
    user2 = ModelFactory.testUser(customer2, "tc2@test.com");
    authToken1 = user1.createAuthToken();
    authToken2 = user2.createAuthToken();
    ObjectNode params = Json.newObject();
    params.put("foo", "bar");
    audit1 = Audit.create(user1.uuid, customer1.uuid, "/test/call", "GET", params, null);
    taskUUID1 = UUID.randomUUID();
    taskUUID2 = UUID.randomUUID();
    audit2 = Audit.create(user1.uuid, customer1.uuid, "/test/call1", "DELETE", params, taskUUID1);
    audit3 = Audit.create(user2.uuid, customer2.uuid, "/test/call2", "PUT", params, taskUUID2);
    audit4 = Audit.create(user2.uuid, customer2.uuid, "/test/call4", "GET", params, null);
  }

  @Test
  public void testGetAuditListByUser() {
    Http.Cookie validCookie = Http.Cookie.builder("authToken", authToken1).build();
    String route = "/api/customers/%s/users/%s/audit_trail";
    Result result =
        route(
            fakeRequest("GET", String.format(route, customer1.uuid, user1.uuid))
                .cookie(validCookie));
    assertEquals(OK, result.status());
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(json.size(), 2);
  }

  @Test
  public void testGetListFailureIncorrectCustomer() {
    Http.Cookie validCookie = Http.Cookie.builder("authToken", authToken1).build();
    String route = "/api/customers/%s/users/%s/audit_trail";
    Result result =
        route(
            fakeRequest("GET", String.format(route, customer2.uuid, user1.uuid))
                .cookie(validCookie));
    assertEquals(FORBIDDEN, result.status());
  }

  @Test
  public void testGetTaskInfo() throws IOException {
    Http.Cookie validCookie = Http.Cookie.builder("authToken", authToken2).build();
    String route = "/api/customers/%s/tasks/%s/audit_info";
    Result result =
        route(
            fakeRequest("GET", String.format(route, customer2.uuid, taskUUID2))
                .cookie(validCookie));
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(OK, result.status());
    assertTrue(json.path("auditID").asLong() == audit3.getAuditID());
  }

  @Test
  public void testGetTaskInfoInvalidCustomer() throws IOException {
    Http.Cookie validCookie = Http.Cookie.builder("authToken", authToken2).build();
    String route = "/api/customers/%s/tasks/%s/audit_info";
    Result result =
        assertYWSE(
            () ->
                route(
                    fakeRequest("GET", String.format(route, customer2.uuid, taskUUID1))
                        .cookie(validCookie)));
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(BAD_REQUEST, result.status());
  }

  @Test
  public void testUserFromTask() throws IOException {
    Http.Cookie validCookie = Http.Cookie.builder("authToken", authToken2).build();
    String route = "/api/customers/%s/tasks/%s/audit_user";
    Result result =
        route(
            fakeRequest("GET", String.format(route, customer2.uuid, taskUUID2))
                .cookie(validCookie));
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(OK, result.status());
    assertTrue(json.path("uuid").asText().equals(user2.uuid.toString()));
  }

  @Test
  public void testGetUserFromTaskInvalidCustomer() throws IOException {
    Http.Cookie validCookie = Http.Cookie.builder("authToken", authToken2).build();
    String route = "/api/customers/%s/tasks/%s/audit_user";
    Result result =
        assertYWSE(
            () ->
                route(
                    fakeRequest("GET", String.format(route, customer2.uuid, taskUUID1))
                        .cookie(validCookie)));
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(BAD_REQUEST, result.status());
  }
}
