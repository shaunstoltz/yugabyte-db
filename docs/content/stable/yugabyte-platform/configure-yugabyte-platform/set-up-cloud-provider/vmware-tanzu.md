---
title: Configure the VMware Tanzu provider
headerTitle: Configure the VMware Tanzu provider
linkTitle: Configure the cloud provider
description: Configure the VMware Tanzu provider
menu:
  stable:
    identifier: set-up-cloud-provider-4-vmware-tanzu
    parent: configure-yugabyte-platform
    weight: 20
isTocNested: false
showAsideToc: true
---

<ul class="nav nav-tabs-alt nav-tabs-yb">

  <li>
    <a href="/latest/yugabyte-platform/configure-yugabyte-platform/set-up-cloud-provider/aws" class="nav-link">
      <i class="fab fa-aws"></i>
      AWS
    </a>
  </li>

  <li>
    <a href="/latest/yugabyte-platform/configure-yugabyte-platform/set-up-cloud-provider/gcp" class="nav-link">
      <i class="fab fa-google" aria-hidden="true"></i>
      GCP
    </a>
  </li>

  <li>
    <a href="/latest/yugabyte-platform/configure-yugabyte-platform/set-up-cloud-provider/azure" class="nav-link">
      <i class="icon-azure" aria-hidden="true"></i>
      &nbsp;&nbsp; Azure
    </a>
  </li>

  <li>
    <a href="/latest/yugabyte-platform/configure-yugabyte-platform/set-up-cloud-provider/kubernetes" class="nav-link">
      <i class="fas fa-cubes" aria-hidden="true"></i>
      Kubernetes
    </a>
  </li>

  <li>
    <a href="/latest/yugabyte-platform/configure-yugabyte-platform/set-up-cloud-provider/vmware-tanzu" class="nav-link active">
      <i class="fas fa-cubes" aria-hidden="true"></i>
      VMware Tanzu
    </a>
  </li>

<li>
    <a href="/latest/yugabyte-platform/configure-yugabyte-platform/set-up-cloud-provider/openshift" class="nav-link">
      <i class="fas fa-cubes" aria-hidden="true"></i>OpenShift</a>
  </li>

  <li>
    <a href="/latest/yugabyte-platform/configure-yugabyte-platform/set-up-cloud-provider/on-premises" class="nav-link">
      <i class="fas fa-building"></i>
      On-premises
    </a>
  </li>

</ul>

This document explains how to configure VMware Tanzu Kubernetes Grid (TKG) for a YugabyteDB universe using Yugabyte Platform. 

## Configuring the VMware Tanzu Provider

Before you start, ensure that you have the `kubeconfig` file generated during [Platform Installation](/latest/yugabyte-platform/install-yugabyte-platform/install-software/kubernetes/#create-a-kubeconfig-file-for-a-kubernetes-cluster) so Yugabyte Platform can use the provided credentials to automatically provision and deprovision Kubernetes pods that run the YugabyteDB universe.

To start configuring any TKG edition (that is, either TKG-Integrated, TKG-Service, or TKG-Multicloud), open the **Yugabyte Admin Console** and click **Configure a Provider**, as shown in the following illustration:

![Admin Console](/images/deploy/pivotal-cloud-foundry/admin-console.png)

### How to Configure TKG Credentials

- On the **Cloud Provider Configuration** window, select the **VMware Tanzu** tab, as shown in the following illustration.
- Use the **Name** field to provide a meaningful name for your configuration.
- Use the **Kube Config** field to specify the kubeconfig for an availability zone at one of the following levels:

  - At the **provider level**, in which case this configuration file will be used for all availability zones in all regions. You use the **Cloud Provider Configuration** window for this setting.
  - At the **zone level**, which is important for multi-zone or multi-region deployments. You use the **Add new region** dialog for this setting.
- Use the **Service Account** field to provide the name of the service account that has the necessary access to manage the cluster, as described in [Create Cluster](/latest/deploy/kubernetes/single-zone/oss/helm-chart/#create-cluster).

- Use the **Image Registry** field to specify the location of the YugabyteDB image. You should accept the default setting, unless you are hosting your own registry.
- The **Pull Secret** field indicates that the Enterprise YugabyteDB image is in a private repository. Use this field to upload the pull secret for downloading the images. The secret should be supplied by your organization's sales team.

![Tanzu Configuratioin](/images/deploy/pivotal-cloud-foundry/tanzu-config-1.png)

### How to Configure Region and Zones

- On the **Cloud Provider Configuration** window, click **Add Region** to open the **Add new region** dialog shown in the following illustration:


![Add Region](/images/deploy/pivotal-cloud-foundry/add-region-1.png)

- Use the **Region** field to select the region.
- Use the **Zone** field to enter a zone label that matches your failure domain zone label `failure-domain.beta.kubernetes.io/zone`
- In the **Storage Class** field, provide the storage class that (1) exists in your Kubernetes cluster and (2) matches the one installed on TKG. The valid input is a comma delimited value. The default is standard. That is, the default storage class is TKG - Multi Cloud: standard-sc, TKG - Service: tkg-vsan-storage-policy
- Overrides to add Service level annotations

- Use the **Kube Config** field to upload the kubeconfig file.
- Optionally, complete the **Overrides** field. If not completed, Yugabyte Platform uses the default values specified inside the Helm chart.

  To add Service-level annotations, use the following overrides:

```
serviceEndpoints:
  - name: "yb-master-service"
    type: "LoadBalancer"
    annotations:
      service.beta.kubernetes.io/aws-load-balancer-internal: "0.0.0.0/0"
    app: "yb-master"
    ports:
      ui: "7000"

  - name: "yb-tserver-service"
    type: "LoadBalancer"
    annotations:
      service.beta.kubernetes.io/aws-load-balancer-internal: "0.0.0.0/0"
    app: "yb-tserver"
    ports:
      ycql-port: "9042"
      yedis-port: "6379"
      ysql-port: "5433"
```

  To disable LoadBalancer, use the following overrides:

```
enableLoadBalancer: False
```

  To change the cluster domain name, use the following overrides:

```
domainName: my.cluster
```

  To add annotations at the StatefulSet level, use the following overrides:

```
networkAnnotation:
  annotation1: 'foo'
  annotation2: 'bar'
```

- Add a new zone by clicking **Add Zone**. Your configuration may have multiple zones, as shown in the following illustration:


![Add Region](/images/deploy/pivotal-cloud-foundry/add-region-2.png)

- Click **Add Region**.

- Click **Save**. If your configuration is successful, you are redirected to **VMware Tanzu configs**, as shown in the following illustration. 


![Finish Tanzu Configuration](/images/deploy/pivotal-cloud-foundry/tanzu-config-finish.png)

## Appendix Using VMware Tanzu Application Service

VMware Tanzu Application Service is no longer actively supported and the following information is considered legacy.

If you choose to use VMware Tanzu Application Service, before creating the service instance, ensure that the following is available: 

- The YugabyteDB tile is installed in your PCF marketplace.
- The cloud provider is configured in the Yugabyte Platform instance in your PCF environment . 

### Creating a YugabyteDB Service Instance

You can create a YugabyteDB service instance via the App Manager UI or Cloud Foundry (cf) command-line interface (CLI).

#### How to Use the PCF App Manager

- In your PCF App manager, navigate to the marketplace and select **YugabyteDB**. 
- Read descriptions of the available service plans to identify the resource requirements and intended environment, as shown in the following illustration.

![Yugabyte Service Plans](/images/deploy/pivotal-cloud-foundry/service-plan-choices.png)

- Select the service plan. 
- Complete the service instance configuration, as shown in the following illustration:

![App Manager Config](/images/deploy/pivotal-cloud-foundry/apps-manager-config.png)

#### How to Use the Cloud Foundry CLI

You can view the marketplace and plan description in the cf CLI by executing the following command:

```sh
$ cf marketplace -s yugabyte-db
```

The ouput should be simiar to the following:

```
service plan   description                  free or paid
x-small        Cores: 2, Memory (GB): 4     paid
small          Cores: 4, Memory (GB): 7     paid
medium         Cores: 8, Memory (GB): 15    paid
large          Cores: 16, Memory (GB): 15   paid
x-large        Cores: 32, Memory (GB): 30   paid
```

Once you decide on the service plan, you can launch the YugabyteDB service instance by executing the following command:

```sh
$ cf create-service yugabyte-db x-small yb-demo -c '{"universe_name": "yb-demo"}'
```

### Configuring the YugabyteDB Service Instance

You can specify override options when you create a service instance using the YugabyteDB service broker.

#### How to Override Cloud Providers

Depending on the cloud providers configured for your Yugabyte Platform, you can create Yugabyte service instances by providing overrides.

To provision in AWS or GCP cloud, your overrides should include the appropriate `provider_type` and `region_codes` as an array, as follows:

```sh
{
 "universe_name": "cloud-override-demo",
 "provider_type": "gcp", # gcp for Google Cloud, aws for Amazon Web Service
 "region_codes": ["us-west1"] # comma delimited list of regions
}
```

To provision in Kubernetes, your overrides should include the appropriate `provider_type` and `kube_provider` type, as follows:

```sh
{
 "universe_name": "cloud-override-demo",
 "provider_type": "kubernetes",
 "kube_provider": "gke" # gke for Google Compute Engine, pks for Pivotal Container Service (default)
}
```

#### How to Override the Number of Nodes

To override the number of nodes, include the `num_nodes` with the desired value, and then include this parameter along with other parameters for the cloud provider, as follows:

```sh
{
 "universe_name": "cloud-override-demo",
 "num_nodes": 4 # default is 3 nodes.
}
```

#### How to Override the Replication Factor

To override the replication factor, include `replication` with the desired value, and then include this parameter along with other parameters for the cloud provider, as follows:

```sh
{
 "universe_name": "cloud-override-demo",
 "replication": 5,
 "num_nodes": 5 # if the replication factor is 5, num_nodes must be 5 minimum
}
```

*replication* must be set to 1, 3, 5, or 7.

#### How to Override the Volume Settings

To override the volume settings, include `num_volumes` with the desired value, as well as `volume_size` with the volume size in GB for each of those volumes. For example, to have two volumes with 100GB each, overrides should be specified as follows:

```sh
{
 "universe_name": "cloud-override-demo",
 "num_volumes": 2,
 "volume_size": 100
}
```

#### How to Override  the YugabyteDB Software Version

To override the YugabyteDB software version to be used, include `yb_version` with the desired value, ensuring that this version exists in Yugabyte Platform, as follows:

```sh
{
 "universe_name": "cloud-override-demo",
 "yb_version": "1.1.6.0-b4"
}
```

