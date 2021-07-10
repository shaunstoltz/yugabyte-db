// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Provider;

import org.pac4j.oidc.client.KeycloakOidcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static play.mvc.Http.Status.*;

@Singleton
public class AccessManager extends DevopsBase {
  public static final Logger LOG = LoggerFactory.getLogger(AccessManager.class);

  @Inject play.Configuration appConfig;

  private static final String YB_CLOUD_COMMAND_TYPE = "access";
  private static final String PEM_PERMISSIONS = "r--------";
  private static final String PUB_PERMISSIONS = "rw-r--r--";

  @Override
  protected String getCommandType() {
    return YB_CLOUD_COMMAND_TYPE;
  }

  public enum KeyType {
    PUBLIC,
    PRIVATE;

    public String getExtension() {
      switch (this) {
        case PUBLIC:
          return ".pub";
        case PRIVATE:
          return ".pem";
        default:
          return null;
      }
    }
  }

  private String getOrCreateKeyFilePath(UUID providerUUID) {
    File keyBasePathName = new File(appConfig.getString("yb.storage.path"), "/keys");
    // Protect against multi-threaded access and validate that we only error out if mkdirs fails
    // correctly, by NOT creating the final dir path.
    synchronized (this) {
      if (!keyBasePathName.exists() && !keyBasePathName.mkdirs() && !keyBasePathName.exists()) {
        throw new RuntimeException(
            "Key path " + keyBasePathName.getAbsolutePath() + " doesn't exist.");
      }
    }

    File keyFilePath = new File(keyBasePathName.getAbsoluteFile(), providerUUID.toString());
    if (keyFilePath.isDirectory() || keyFilePath.mkdirs()) {
      return keyFilePath.getAbsolutePath();
    }

    throw new RuntimeException("Unable to create key file path " + keyFilePath.getAbsolutePath());
  }

  private String getOrCreateKeyFilePath(String path) {
    File keyBasePathName = new File(appConfig.getString("yb.storage.path"), "/keys");
    // Protect against multi-threaded access and validate that we only error out if mkdirs fails
    // correctly, by NOT creating the final dir path.
    synchronized (this) {
      if (!keyBasePathName.exists() && !keyBasePathName.mkdirs() && !keyBasePathName.exists()) {
        throw new RuntimeException(
            "Key path " + keyBasePathName.getAbsolutePath() + " doesn't exist.");
      }
    }

    File keyFilePath = new File(keyBasePathName.getAbsoluteFile(), path);
    if (keyFilePath.isDirectory() || keyFilePath.mkdirs()) {
      return keyFilePath.getAbsolutePath();
    }

    throw new RuntimeException("Unable to create key file path " + keyFilePath.getAbsolutePath());
  }

  // This method would upload the provided key file to the provider key file path.
  public AccessKey uploadKeyFile(
      UUID regionUUID,
      File uploadedFile,
      String keyCode,
      KeyType keyType,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning)
      throws IOException {
    Region region = Region.get(regionUUID);
    String keyFilePath = getOrCreateKeyFilePath(region.provider.uuid);
    // Removing paths from keyCode.
    keyCode = Util.getFileName(keyCode);
    AccessKey accessKey = AccessKey.get(region.provider.uuid, keyCode);
    if (accessKey != null) {
      throw new YWServiceException(BAD_REQUEST, "Duplicate Access KeyCode: " + keyCode);
    }
    Path source = Paths.get(uploadedFile.getAbsolutePath());
    Path destination = Paths.get(keyFilePath, keyCode + keyType.getExtension());
    if (!Files.exists(source)) {
      throw new YWServiceException(
          INTERNAL_SERVER_ERROR, "Key file " + source.getFileName() + " not found.");
    }
    if (Files.exists(destination)) {
      throw new YWServiceException(
          INTERNAL_SERVER_ERROR, "File " + destination.getFileName() + " already exists.");
    }

    Files.move(source, destination);
    Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(PEM_PERMISSIONS);
    if (keyType == AccessManager.KeyType.PUBLIC) {
      permissions = PosixFilePermissions.fromString(PUB_PERMISSIONS);
    }
    Files.setPosixFilePermissions(destination, permissions);

    AccessKey.KeyInfo keyInfo = new AccessKey.KeyInfo();
    if (keyType == AccessManager.KeyType.PUBLIC) {
      keyInfo.publicKey = destination.toAbsolutePath().toString();
    } else {
      keyInfo.privateKey = destination.toAbsolutePath().toString();
    }
    JsonNode vaultResponse = createVault(regionUUID, keyInfo.privateKey);
    if (vaultResponse.has("error")) {
      throw new YWServiceException(
          INTERNAL_SERVER_ERROR,
          "Vault Creation failed with : " + vaultResponse.get("error").asText());
    }
    keyInfo.vaultFile = vaultResponse.get("vault_file").asText();
    keyInfo.vaultPasswordFile = vaultResponse.get("vault_password").asText();
    keyInfo.sshUser = sshUser;
    keyInfo.sshPort = sshPort;
    keyInfo.airGapInstall = airGapInstall;
    keyInfo.skipProvisioning = skipProvisioning;
    return AccessKey.create(region.provider.uuid, keyCode, keyInfo);
  }

  public AccessKey saveAndAddKey(
      UUID regionUUID,
      String keyContents,
      String keyCode,
      KeyType keyType,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning) {
    AccessKey key = null;
    Path tempFile = null;

    try {
      tempFile = Files.createTempFile(keyCode, keyType.getExtension());
      Files.write(tempFile, keyContents.getBytes());

      key =
          uploadKeyFile(
              regionUUID,
              tempFile.toFile(),
              keyCode,
              keyType,
              sshUser,
              sshPort,
              airGapInstall,
              skipProvisioning);

      File pemFile = new File(key.getKeyInfo().privateKey);
      key = addKey(regionUUID, keyCode, pemFile, sshUser, sshPort, airGapInstall, skipProvisioning);
    } catch (NoSuchFileException ioe) {
      LOG.error(ioe.getMessage(), ioe);
    } catch (IOException ioe) {
      LOG.error(ioe.getMessage(), ioe);
      throw new RuntimeException("Could not create AccessKey", ioe);
    } finally {
      try {
        if (tempFile != null) {
          Files.delete(tempFile);
        }
      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
      }
    }

    return key;
  }
  // This method would create a public/private key file and upload that to
  // the provider cloud account. And store the credentials file in the keyFilePath
  // and return the file names. It will also create the vault file.
  public AccessKey addKey(
      UUID regionUUID,
      String keyCode,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning) {
    return addKey(regionUUID, keyCode, null, null, sshPort, airGapInstall, skipProvisioning);
  }

  public AccessKey addKey(
      UUID regionUUID,
      String keyCode,
      File privateKeyFile,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall) {
    return addKey(regionUUID, keyCode, privateKeyFile, sshUser, sshPort, airGapInstall, false);
  }

  public AccessKey addKey(
      UUID regionUUID,
      String keyCode,
      File privateKeyFile,
      String sshUser,
      Integer sshPort,
      boolean airGapInstall,
      boolean skipProvisioning) {
    List<String> commandArgs = new ArrayList<String>();
    Region region = Region.get(regionUUID);
    String keyFilePath = getOrCreateKeyFilePath(region.provider.uuid);
    AccessKey accessKey = AccessKey.get(region.provider.uuid, keyCode);

    commandArgs.add("--key_pair_name");
    commandArgs.add(keyCode);
    commandArgs.add("--key_file_path");
    commandArgs.add(keyFilePath);

    String privateKeyFilePath = null;
    if (accessKey != null && accessKey.getKeyInfo().privateKey != null) {
      privateKeyFilePath = accessKey.getKeyInfo().privateKey;
    } else if (privateKeyFile != null) {
      privateKeyFilePath = privateKeyFile.getAbsolutePath();
    }
    // If we have a private key file to use, add in the param.
    if (privateKeyFilePath != null) {
      commandArgs.add("--private_key_file");
      commandArgs.add(privateKeyFilePath);
    }

    JsonNode response = execAndParseCommandRegion(regionUUID, "add-key", commandArgs);
    if (response.has("error")) {
      throw new YWServiceException(
          INTERNAL_SERVER_ERROR,
          "Parsing of Region failed with : " + response.get("error").asText());
    }

    if (accessKey == null) {
      AccessKey.KeyInfo keyInfo = new AccessKey.KeyInfo();
      keyInfo.publicKey = response.get("public_key").asText();
      keyInfo.privateKey = response.get("private_key").asText();
      JsonNode vaultResponse = createVault(regionUUID, keyInfo.privateKey);
      if (response.has("error")) {
        throw new YWServiceException(
            INTERNAL_SERVER_ERROR,
            "Vault Creation failed with : " + response.get("error").asText());
      }
      keyInfo.vaultFile = vaultResponse.get("vault_file").asText();
      keyInfo.vaultPasswordFile = vaultResponse.get("vault_password").asText();
      if (sshUser != null) {
        keyInfo.sshUser = sshUser;
      }
      keyInfo.sshPort = sshPort;
      keyInfo.airGapInstall = airGapInstall;
      keyInfo.skipProvisioning = skipProvisioning;
      accessKey = AccessKey.create(region.provider.uuid, keyCode, keyInfo);
    }
    return accessKey;
  }

  public JsonNode createVault(UUID regionUUID, String privateKeyFile) {
    List<String> commandArgs = new ArrayList<String>();

    if (!new File(privateKeyFile).exists()) {
      throw new RuntimeException("File " + privateKeyFile + " doesn't exists.");
    }
    commandArgs.add("--private_key_file");
    commandArgs.add(privateKeyFile);
    return execAndParseCommandRegion(regionUUID, "create-vault", commandArgs);
  }

  public JsonNode listKeys(UUID regionUUID) {
    return execAndParseCommandRegion(regionUUID, "list-keys", Collections.emptyList());
  }

  public JsonNode deleteKey(UUID regionUUID, String keyCode) {
    Region region = Region.get(regionUUID);
    if (region == null) {
      throw new RuntimeException("Invalid Region UUID: " + regionUUID);
    }

    switch (Common.CloudType.valueOf(region.provider.code)) {
      case aws:
      case azu:
      case gcp:
      case onprem:
        return deleteKey(region.provider.uuid, region.uuid, keyCode);
      default:
        return null;
    }
  }

  public JsonNode deleteKeyByProvider(Provider provider, String keyCode) {
    List<Region> regions = Region.getByProvider(provider.uuid);
    if (regions == null || regions.isEmpty()) {
      return null;
    }

    if (Common.CloudType.valueOf(provider.code) == Common.CloudType.aws) {
      ObjectMapper mapper = play.libs.Json.newDefaultMapper();
      ArrayNode ret = mapper.getNodeFactory().arrayNode();
      regions
          .stream()
          .map(r -> deleteKey(provider.uuid, r.uuid, keyCode))
          .collect(Collectors.toList())
          .forEach(ret::add);
      return ret;
    } else {
      return deleteKey(provider.uuid, regions.get(0).uuid, keyCode);
    }
  }

  private JsonNode deleteKey(UUID providerUUID, UUID regionUUID, String keyCode) {
    List<String> commandArgs = new ArrayList<String>();
    String keyFilePath = getOrCreateKeyFilePath(providerUUID);

    commandArgs.add("--key_pair_name");
    commandArgs.add(keyCode);
    commandArgs.add("--key_file_path");
    commandArgs.add(keyFilePath);
    JsonNode response = execAndParseCommandRegion(regionUUID, "delete-key", commandArgs);
    if (response.has("error")) {
      throw new RuntimeException(response.get("error").asText());
    }
    return response;
  }

  public String createCredentialsFile(UUID providerUUID, JsonNode credentials) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    String credentialsFilePath = getOrCreateKeyFilePath(providerUUID) + "/credentials.json";
    mapper.writeValue(new File(credentialsFilePath), credentials);
    return credentialsFilePath;
  }

  public String createKubernetesConfig(String path, Map<String, String> config, boolean edit)
      throws IOException {
    // Grab the kubernetes config file name and file content and create the physical file.
    String configFileName = config.remove("KUBECONFIG_NAME");
    String configFileContent = config.remove("KUBECONFIG_CONTENT");

    // In case of edit, don't throw exception if conf file isn't provided.
    if (edit && (configFileName == null || configFileContent == null)) {
      return null;
    }

    if (configFileName == null) {
      throw new RuntimeException("Missing KUBECONFIG_NAME data in the provider config.");
    } else if (configFileContent == null) {
      throw new RuntimeException("Missing KUBECONFIG_CONTENT data in the provider config.");
    }
    String configFilePath = getOrCreateKeyFilePath(path);
    Path configFile = Paths.get(configFilePath, Util.getFileName(configFileName));
    if (!edit && Files.exists(configFile)) {
      throw new RuntimeException("File " + configFile.getFileName() + " already exists.");
    }
    Files.write(configFile, configFileContent.getBytes());

    return configFile.toAbsolutePath().toString();
  }

  public String createPullSecret(UUID providerUUID, Map<String, String> config, boolean edit)
      throws IOException {
    // Grab the kubernetes config file name and file content and create the physical file.
    String pullSecretFileName = config.remove("KUBECONFIG_PULL_SECRET_NAME");
    String pullSecretFileContent = config.remove("KUBECONFIG_PULL_SECRET_CONTENT");
    if (pullSecretFileName == null) {
      throw new RuntimeException(
          "Missing KUBECONFIG_PULL_SECRET_NAME data in the provider config.");
    } else if (pullSecretFileContent == null) {
      throw new RuntimeException(
          "Missing KUBECONFIG_PULL_SECRET_CONTENT data in the provider config.");
    }
    String pullSecretFilePath = getOrCreateKeyFilePath(providerUUID);
    Path pullSecretFile = Paths.get(pullSecretFilePath, Util.getFileName(pullSecretFileName));
    if (!edit && Files.exists(pullSecretFile)) {
      throw new RuntimeException("File " + pullSecretFile.getFileName() + " already exists.");
    }
    Files.write(pullSecretFile, pullSecretFileContent.getBytes());

    return pullSecretFile.toAbsolutePath().toString();
  }
}
