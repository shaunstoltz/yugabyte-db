// Copyright (c) Yugabyte, Inc.
package com.yugabyte.yw.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.tasks.CloudBootstrap;
import com.yugabyte.yw.commissioner.tasks.CloudBootstrap.Params.PerRegionMetadata;
import com.yugabyte.yw.common.YWServiceException;
import io.ebean.Finder;
import io.ebean.Model;
import io.ebean.annotation.DbJson;
import io.swagger.annotations.ApiModelProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.validation.Constraints;

import javax.persistence.*;
import java.util.*;

import static com.yugabyte.yw.models.helpers.CommonUtils.DEFAULT_YB_HOME_DIR;
import static com.yugabyte.yw.models.helpers.CommonUtils.maskConfigNew;
import static io.swagger.annotations.ApiModelProperty.AccessMode.READ_ONLY;
import static io.swagger.annotations.ApiModelProperty.AccessMode.READ_WRITE;
import static play.mvc.Http.Status.BAD_REQUEST;

@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"customer_uuid", "name", "code"}))
@Entity
public class Provider extends Model {
  public static final Logger LOG = LoggerFactory.getLogger(Provider.class);
  private static final String TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST =
      "Transient property - only present in mutate API request";

  @ApiModelProperty(value = "Provide uuid", accessMode = READ_ONLY)
  @Id
  public UUID uuid;

  // TODO: Use Enum
  @Column(nullable = false)
  @ApiModelProperty(value = "Cloud Provider code", accessMode = READ_WRITE)
  @Constraints.Required()
  public String code;

  @Column(nullable = false)
  @ApiModelProperty(value = "Cloud Provider code", accessMode = READ_WRITE)
  @Constraints.Required()
  public String name;

  @Column(nullable = false, columnDefinition = "boolean default true")
  @ApiModelProperty(value = "Cloud Provider code", accessMode = READ_ONLY)
  public Boolean active = true;

  @Column(nullable = false)
  @ApiModelProperty(value = "Cloud Provider code", accessMode = READ_ONLY)
  public UUID customerUUID;

  public static final Set<String> HostedZoneEnabledProviders = ImmutableSet.of("aws", "azu");
  public static final Set<Common.CloudType> InstanceTagsEnabledProviders =
      ImmutableSet.of(Common.CloudType.aws, Common.CloudType.azu);

  @JsonIgnore
  public void setCustomerUuid(UUID id) {
    this.customerUUID = id;
  }

  @Column(nullable = false, columnDefinition = "TEXT")
  @DbJson
  private Map<String, String> config;

  @OneToMany(cascade = CascadeType.ALL)
  @JsonManagedReference(value = "provider-regions")
  public List<Region> regions;

  @JsonIgnore
  @OneToMany(mappedBy = "provider", cascade = CascadeType.ALL)
  public Set<InstanceType> instanceTypes;

  @JsonIgnore
  @OneToMany(mappedBy = "provider", cascade = CascadeType.ALL)
  public Set<PriceComponent> priceComponents;

  // Start Transient Properties
  // TODO: These are all transient fields for now. At present these are stored
  //  with CliudBootstrap params. We should move them to Provider and persist with
  //  Provider entity.

  // Custom keypair name to use when spinning up YB nodes.
  // Default: created and managed by YB.
  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public String keyPairName = null;

  // Custom SSH private key component.
  // Default: created and managed by YB.
  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public String sshPrivateKeyContent = null;

  // Custom SSH user to login to machines.
  // Default: created and managed by YB.
  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public String sshUser = null;

  // Whether provider should use airgapped install.
  // Default: false.
  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public boolean airGapInstall = false;

  // Port to open for connections on the instance.
  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public Integer sshPort = 54422;

  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public String hostVpcId = null;

  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public String hostVpcRegion = null;

  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public List<String> customHostCidrs = new ArrayList<>();
  // TODO(bogdan): only used/needed for GCP.

  @Transient
  @ApiModelProperty(TRANSIENT_PROPERTY_IN_MUTATE_API_REQUEST)
  public String destVpcId = null;

  // End Transient Properties

  @JsonProperty("config")
  public void setConfig(Map<String, String> configMap) {
    Map<String, String> newConfigMap = this.getConfig();
    newConfigMap.putAll(configMap);
    this.config = newConfigMap;
  }

  @JsonProperty("config")
  public Map<String, String> getMaskedConfig() {
    return maskConfigNew(this.getConfig());
  }

  @JsonIgnore
  public Map<String, String> getConfig() {
    if (this.config == null) {
      return new HashMap<>();
    } else {
      return new HashMap<>(this.config);
    }
  }

  @JsonIgnore
  public String getYbHome() {
    String ybHomeDir = this.getConfig().getOrDefault("YB_HOME_DIR", "");
    if (ybHomeDir.isEmpty()) {
      ybHomeDir = DEFAULT_YB_HOME_DIR;
    }
    return ybHomeDir;
  }

  /** Query Helper for Provider with uuid */
  public static final Finder<UUID, Provider> find = new Finder<UUID, Provider>(Provider.class) {};

  /**
   * Create a new Cloud Provider
   *
   * @param customerUUID, customer uuid
   * @param code, code of cloud provider
   * @param name, name of cloud provider
   * @return instance of cloud provider
   */
  public static Provider create(UUID customerUUID, Common.CloudType code, String name) {
    return create(customerUUID, code, name, new HashMap<>());
  }

  /**
   * Create a new Cloud Provider
   *
   * @param customerUUID, customer uuid
   * @param code, code of cloud provider
   * @param name, name of cloud provider
   * @param config, Map of cloud provider configuration
   * @return instance of cloud provider
   */
  public static Provider create(
      UUID customerUUID, Common.CloudType code, String name, Map<String, String> config) {
    return create(customerUUID, null, code, name, config);
  }

  public static Provider create(
      UUID customerUUID,
      UUID providerUUID,
      Common.CloudType code,
      String name,
      Map<String, String> config) {
    Provider provider = new Provider();
    provider.customerUUID = customerUUID;
    provider.uuid = providerUUID;
    provider.code = code.toString();
    provider.name = name;
    provider.setConfig(config);
    provider.save();
    return provider;
  }

  /**
   * Query provider based on customer uuid and provider uuid
   *
   * @param customerUUID, customer uuid
   * @param providerUUID, cloud provider uuid
   * @return instance of cloud provider.
   */
  public static Provider get(UUID customerUUID, UUID providerUUID) {
    return find.query().where().eq("customer_uuid", customerUUID).idEq(providerUUID).findOne();
  }

  public static Provider getOrBadRequest(UUID customerUUID, UUID providerUUID) {
    Provider provider = Provider.get(customerUUID, providerUUID);
    if (provider == null) {
      throw new YWServiceException(BAD_REQUEST, "Invalid Provider UUID: " + providerUUID);
    }
    return provider;
  }

  /**
   * Get all the providers for a given customer uuid
   *
   * @param customerUUID, customer uuid
   * @return list of cloud providers.
   */
  public static List<Provider> getAll(UUID customerUUID) {
    return find.query().where().eq("customer_uuid", customerUUID).findList();
  }

  /**
   * Get Provider by code for a given customer uuid. If there is multiple providers with the same
   * name, it will raise a exception.
   *
   * @param customerUUID
   * @param code
   * @return
   */
  public static List<Provider> get(UUID customerUUID, Common.CloudType code) {
    return find.query()
        .where()
        .eq("customer_uuid", customerUUID)
        .eq("code", code.toString())
        .findList();
  }

  /**
   * Get Provider by name, cloud for a given customer uuid. If there is multiple providers with the
   * same name, cloud will raise a exception.
   *
   * @param customerUUID
   * @param name
   * @param code
   * @return
   */
  public static Provider get(UUID customerUUID, String name, Common.CloudType code) {
    return find.query()
        .where()
        .eq("customer_uuid", customerUUID)
        .eq("name", name)
        .eq("code", code.toString())
        .findOne();
  }

  // Use get Or bad request
  @Deprecated
  public static Provider get(UUID providerUuid) {
    return find.byId(providerUuid);
  }

  public static Provider getOrBadRequest(UUID providerUuid) {
    Provider provider = find.byId(providerUuid);
    if (provider == null)
      throw new YWServiceException(BAD_REQUEST, "Cannot find universe " + providerUuid);
    return provider;
  }

  public String getHostedZoneId() {
    return getConfig().getOrDefault("HOSTED_ZONE_ID", getConfig().get("AWS_HOSTED_ZONE_ID"));
  }

  public String getHostedZoneName() {
    return getConfig().getOrDefault("HOSTED_ZONE_NAME", getConfig().get("AWS_HOSTED_ZONE_NAME"));
  }

  /**
   * Get all Providers by code without customer uuid.
   *
   * @param code
   * @return
   */
  public static List<Provider> getByCode(String code) {
    return find.query().where().eq("code", code).findList();
  }

  // Update host zone.
  public void updateHostedZone(String hostedZoneId, String hostedZoneName) {
    Map<String, String> currentProviderConfig = getConfig();
    currentProviderConfig.put("HOSTED_ZONE_ID", hostedZoneId);
    currentProviderConfig.put("HOSTED_ZONE_NAME", hostedZoneName);
    this.setConfig(currentProviderConfig);
    this.save();
  }

  // Used for GCP providers to pass down region information. Currently maps regions to
  // their subnets. Only user-input fields should be retrieved here (e.g. zones should
  // not be included for GCP because they're generated from devops).
  @JsonIgnore
  public CloudBootstrap.Params getCloudParams() {
    CloudBootstrap.Params newParams = new CloudBootstrap.Params();
    newParams.perRegionMetadata = new HashMap<>();
    if (!this.code.equals(Common.CloudType.gcp.toString())) {
      return newParams;
    }

    List<Region> regions = Region.getByProvider(this.uuid);
    if (regions == null || regions.isEmpty()) {
      return newParams;
    }

    for (Region r : regions) {
      List<AvailabilityZone> zones = AvailabilityZone.getAZsForRegion(r.uuid);
      if (zones == null || zones.isEmpty()) {
        continue;
      }
      PerRegionMetadata regionData = new PerRegionMetadata();
      // For GCP, a subnet is assigned to each region, so we only need the first zone's subnet.
      regionData.subnetId = zones.get(0).subnet;
      newParams.perRegionMetadata.put(r.code, regionData);
    }
    return newParams;
  }
}
