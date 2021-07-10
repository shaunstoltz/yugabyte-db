// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.forms.YWResults;
import com.yugabyte.yw.models.Audit;
import org.junit.function.ThrowingRunnable;
import play.libs.Json;
import play.mvc.Result;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.*;
import static play.mvc.Http.Status.*;
import static play.test.Helpers.contentAsString;

public class AssertHelper {
  public static void assertOk(Result result) {
    assertEquals(contentAsString(result), OK, result.status());
  }

  public static void assertBadRequest(Result result, String errorStr) {
    assertEquals(BAD_REQUEST, result.status());
    assertErrorResponse(result, errorStr);
  }

  public static void assertInternalServerError(Result result, String errorStr) {
    assertEquals(INTERNAL_SERVER_ERROR, result.status());
    assertErrorResponse(result, errorStr);
  }

  public static void assertUnauthorized(Result result, String errorStr) {
    assertEquals(UNAUTHORIZED, result.status());
    assertErrorResponse(result, errorStr);
  }

  public static void assertForbidden(Result result, String errorStr) {
    assertEquals(FORBIDDEN, result.status());
    assertEquals(errorStr, contentAsString(result));
  }

  public static void assertNotFound(Result result, String errorStr) {
    assertEquals(NOT_FOUND, result.status());
    assertErrorResponse(result, errorStr);
  }

  public static void assertErrorResponse(Result result, String errorStr) {
    if (errorStr != null) {
      JsonNode json = Json.parse(contentAsString(result));
      assertThat(json.get("error").toString(), allOf(notNullValue(), containsString(errorStr)));
    }
  }

  public static void assertValue(JsonNode json, String key, String value) {
    JsonNode targetNode = json.path(key);
    assertFalse(targetNode.isMissingNode());
    if (targetNode.isObject()) {
      assertEquals(value, targetNode.toString());
    } else {
      assertEquals(value, targetNode.asText());
    }
  }

  // Allows specifying a JsonPath to the expected node.
  // For example "/foo/bar" locates node bar within node foo in json.
  public static void assertValueAtPath(JsonNode json, String key, String value) {
    JsonNode targetNode = json.at(key);
    assertFalse(targetNode.isMissingNode());
    if (targetNode.isObject()) {
      assertEquals(value, targetNode.toString());
    } else {
      assertEquals(value, targetNode.asText());
    }
  }

  public static void assertValues(JsonNode json, String key, List<String> values) {
    json.findValues(key).forEach((node) -> assertTrue(values.contains(node.asText())));
  }

  public static void assertArrayNode(JsonNode json, String key, List<String> expectedValues) {
    assertTrue(json.get(key).isArray());
    json.get(key).forEach((value) -> assertTrue(expectedValues.contains(value.asText())));
  }

  public static void assertErrorNodeValue(JsonNode json, String key, String value) {
    JsonNode errorJson = json.get("error");
    assertNotNull(errorJson);
    if (key == null) {
      assertThat(errorJson.toString(), errorJson.asText(), allOf(notNullValue(), equalTo(value)));
    } else {
      assertThat(errorJson.toString() + "[" + key + "]", errorJson.get(key), is(notNullValue()));
      assertThat(
          errorJson.toString(),
          errorJson.get(key).get(0).asText(),
          allOf(notNullValue(), equalTo(value)));
    }
  }

  public static void assertErrorNodeValue(JsonNode json, String value) {
    assertErrorNodeValue(json, null, value);
  }

  public static void assertJsonEqual(JsonNode expectedJson, JsonNode actualJson) {
    expectedJson
        .fieldNames()
        .forEachRemaining(field -> assertEquals(expectedJson.get(field), actualJson.get(field)));
  }

  public static void assertAuditEntry(int expectedNumEntries, UUID uuid) {
    int actual = Audit.getAll(uuid).size();
    assertEquals(expectedNumEntries, actual);
  }

  public static void assertYWSuccess(Result result, String expectedMessage) {
    assertOk(result);
    YWResults.YWSuccess ywSuccess =
        Json.fromJson(Json.parse(contentAsString(result)), YWResults.YWSuccess.class);
    assertEquals(expectedMessage, ywSuccess.message);
  }

  private static void assertYWSuccessNoMessage(Result result) {
    assertOk(result);
    YWResults.YWSuccess ywSuccess =
        Json.fromJson(Json.parse(contentAsString(result)), YWResults.YWSuccess.class);
    assertNull(ywSuccess.message);
  }

  public static Result assertYWSE(ThrowingRunnable runnable) {
    return assertThrows(YWServiceException.class, runnable).getResult();
  }
}
