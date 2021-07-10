package com.yugabyte.yw.common;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import com.yugabyte.yw.forms.DatabaseSecurityFormData;
import com.yugabyte.yw.forms.DatabaseUserFormData;
import com.yugabyte.yw.forms.RunQueryFormData;
import com.yugabyte.yw.models.Universe;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Http;
import static play.libs.Json.newObject;
import static play.libs.Json.toJson;

@Singleton
public class YcqlQueryExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(YcqlQueryExecutor.class);
  private static final String DEFAULT_DB_USER = "cassandra";
  private static final String DEFAULT_DB_PASSWORD = "cassandra";

  public void createUser(Universe universe, DatabaseUserFormData data) {
    // Create user for customer CQL.

    // This part of code works only when TServer is started with
    // --use_cassandra_authentication=true
    // This is always true if the universe was created via cloud.
    RunQueryFormData ycqlQuery = new RunQueryFormData();
    ycqlQuery.query =
        String.format(
            "CREATE ROLE '%s' WITH SUPERUSER=true AND LOGIN=true AND PASSWORD='%s'",
            Util.escapeSingleQuotesOnly(data.username), Util.escapeSingleQuotesOnly(data.password));
    JsonNode ycqlResponse =
        executeQuery(universe, ycqlQuery, true, data.ycqlAdminUsername, data.ycqlAdminPassword);
    LOG.info("Creating YCQL user, result: " + ycqlResponse.toString());
    if (ycqlResponse.has("error")) {
      throw new YWServiceException(Http.Status.BAD_REQUEST, ycqlResponse.get("error").asText());
    }
  }

  public void updateAdminPassword(Universe universe, DatabaseSecurityFormData data) {
    // Update admin user password CQL.

    // This part of code works only when TServer is started with
    // --use_cassandra_authentication=true
    // This is always true if the universe was created via cloud.
    RunQueryFormData ycqlQuery = new RunQueryFormData();
    ycqlQuery.query =
        String.format(
            "ALTER ROLE '%s' WITH PASSWORD='%s'",
            Util.escapeSingleQuotesOnly(data.ycqlAdminUsername),
            Util.escapeSingleQuotesOnly(data.ycqlAdminPassword));
    JsonNode ycqlResponse =
        executeQuery(universe, ycqlQuery, true, data.ycqlAdminUsername, data.ycqlCurrAdminPassword);
    LOG.info("Updating YCQL user, result: " + ycqlResponse.toString());
    if (ycqlResponse.has("error")) {
      throw new YWServiceException(Http.Status.BAD_REQUEST, ycqlResponse.get("error").asText());
    }
  }

  private static class CassandraConnection implements Closeable {
    Cluster cluster = null;
    Session session = null;

    @Override
    public void close() {
      session.close();
      cluster.close();
    }
  }

  private CassandraConnection createCassandraConnection(
      UUID universeUUID, Boolean authEnabled, String username, String password) {
    CassandraConnection cc = new CassandraConnection();
    List<InetSocketAddress> addresses = Util.getNodesAsInet(universeUUID);
    if (addresses.isEmpty()) {
      return cc;
    }
    Cluster.Builder builder = Cluster.builder().addContactPointsWithPorts(addresses);
    if (authEnabled) {
      builder.withCredentials(username.trim(), password.trim());
    }
    String certificate = Universe.getOrBadRequest(universeUUID).getCertificateClientToNode();
    if (certificate != null) {
      builder.withSSL(SslHelper.getSSLOptions(certificate));
    }
    cc.cluster = builder.build();

    cc.session = cc.cluster.connect();
    return cc;
  }

  private List<Map<String, Object>> resultSetToMap(ResultSet result) {
    List<Map<String, Object>> rows = new ArrayList<>();
    ColumnDefinitions rsmd = result.getColumnDefinitions();
    int columnCount = rsmd.size();
    for (Row currRow : result) {
      // Represent a row in DB. Key: Column name, Value: Column value
      Map<String, Object> row = new HashMap<>();
      for (int i = 0; i < columnCount; i++) {
        // Note that the index is 1-based
        String colName = rsmd.getName(i);
        Object colVal = currRow.getObject(i);
        row.put(colName, colVal);
      }
      rows.add(row);
    }
    return rows;
  }

  private String getQueryType(String queryString) {
    String[] queryParts = queryString.split(" ");
    String command = queryParts[0].toUpperCase();
    if (command.equals("TRUNCATE") || command.equals("DROP"))
      return command + " " + queryParts[1].toUpperCase();
    return command;
  }

  public JsonNode executeQuery(
      Universe universe, RunQueryFormData queryParams, Boolean authEnabled) {
    return executeQuery(universe, queryParams, authEnabled, DEFAULT_DB_USER, DEFAULT_DB_PASSWORD);
  }

  public JsonNode executeQuery(
      Universe universe,
      RunQueryFormData queryParams,
      Boolean authEnabled,
      String username,
      String password) {
    ObjectNode response = newObject();
    CassandraConnection cc =
        createCassandraConnection(universe.universeUUID, authEnabled, username, password);
    try {
      ResultSet rs = cc.session.execute(queryParams.query);
      if (rs.iterator().hasNext()) {
        List<Map<String, Object>> rows = resultSetToMap(rs);
        response.set("result", toJson(rows));
      } else {
        // For commands without a result we return only executed command identifier
        // (SELECT/UPDATE/...). We can't return query itself to avoid logging of
        // sensitive data.
        response.put("queryType", getQueryType(queryParams.query));
      }
    } catch (Exception e) {
      response.put("error", e.getMessage());
    } finally {
      cc.close();
    }
    return response;
  }
}
