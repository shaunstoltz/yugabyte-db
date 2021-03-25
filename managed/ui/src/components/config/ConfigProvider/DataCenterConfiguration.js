// Copyright (c) YugaByte, Inc.

import React, { Component } from 'react';
import { withRouter } from 'react-router';
import {
  KubernetesProviderConfigurationContainer,
  OnPremConfigurationContainer,
  ProviderConfigurationContainer,
  StorageConfigurationContainer,
  SecurityConfiguration
} from '../../config';
import { Tab, Row, Col } from 'react-bootstrap';
import { YBTabsPanel } from '../../panels';
import './providerConfig.scss';
import awsLogo from './images/aws.svg';
import azureLogo from './images/azure.png';
import k8sLogo from './images/k8s.png';
import openshiftLogo from './images/redhat.png';
import tanzuLogo from './images/tanzu.png';
import gcpLogo from './images/gcp.png';
import { isAvailable, showOrRedirect } from '../../../utils/LayoutUtils';

class DataCenterConfiguration extends Component {
  render() {
    const {
      customer: { currentCustomer },
      params: { tab, section },
      params
    } = this.props;
    showOrRedirect(currentCustomer.data.features, 'menu.config');

    const onPremiseTabContent = (
      <div className="on-premise">
        <i className="fa fa-server" />
        On-Premises
        <br />
        Datacenters
      </div>
    );

    const openshiftTabContent = (
      <Row className="custom-tab">
        <Col md={4}>
          <img src={openshiftLogo} alt="Red Hat OpenShift" />
        </Col>
        <Col md={8}>
          Red Hat OpenShift
        </Col>
      </Row>
    );

    const k8sTabContent = (
      <Row className="custom-tab">
        <Col md={4}>
          <img src={k8sLogo} alt="Managed Kubernetes" />
        </Col>
        <Col md={8}>
          Managed Kubernetes Service
        </Col>
      </Row>
    );

    const tanzuTabContent = (
      <Row className="custom-tab">
        <Col md={4}>
          <img src={tanzuLogo} alt="VMware Tanzu" />
        </Col>
        <Col md={8}>
          VMware Tanzu
        </Col>
      </Row>
    );

    const defaultTab = isAvailable(currentCustomer.data.features, 'config.infra')
      ? 'cloud'
      : 'backup';
    const activeTab = tab || defaultTab;

    return (
      <div>
        <h2 className="content-title">Cloud Provider Configuration</h2>
        <YBTabsPanel
          defaultTab={defaultTab}
          activeTab={activeTab}
          routePrefix="/config/"
          id="config-tab-panel"
        >
          {isAvailable(currentCustomer.data.features, 'config.infra') && (
            <Tab eventKey="cloud" title="Infrastructure" key="cloud-config">
              <YBTabsPanel
                defaultTab="aws"
                activeTab={section}
                id="cloud-config-tab-panel"
                className="config-tabs"
                routePrefix="/config/cloud/"
              >
                <Tab
                  eventKey="aws"
                  title={<img src={awsLogo} alt="AWS" className="aws-logo" />}
                  key="aws-tab"
                  unmountOnExit={true}
                >
                  <ProviderConfigurationContainer providerType="aws" />
                </Tab>
                <Tab
                  eventKey="gcp"
                  title={<img src={gcpLogo} alt="GCP" className="gcp-logo" />}
                  key="gcp-tab"
                  unmountOnExit={true}
                >
                  <ProviderConfigurationContainer providerType="gcp" />
                </Tab>
                <Tab
                  eventKey="azure"
                  title={<img src={azureLogo} alt="Azure" className="azure-logo" />}
                  key="azure-tab"
                  unmountOnExit={true}
                >
                  <ProviderConfigurationContainer providerType="azu" />
                </Tab>
                <Tab eventKey="tanzu" title={tanzuTabContent} key="tanzu-tab" unmountOnExit={true}>
                  <KubernetesProviderConfigurationContainer type="tanzu" params={params} />
                </Tab>
                <Tab eventKey="openshift" title={openshiftTabContent} key="openshift-tab" unmountOnExit={true}>
                  <KubernetesProviderConfigurationContainer type="openshift" params={params} />
                </Tab>
                <Tab eventKey="k8s" title={k8sTabContent} key="k8s-tab" unmountOnExit={true}>
                  <KubernetesProviderConfigurationContainer type="k8s" params={params} />
                </Tab>
                <Tab
                  eventKey="onprem"
                  title={onPremiseTabContent}
                  key="onprem-tab"
                  unmountOnExit={true}
                >
                  <OnPremConfigurationContainer params={params} />
                </Tab>
              </YBTabsPanel>
            </Tab>
          )}
          {isAvailable(currentCustomer.data.features, 'config.backup') && (
            <Tab eventKey="backup" title="Backup" key="storage-config">
              <StorageConfigurationContainer activeTab={section} />
            </Tab>
          )}
          {isAvailable(currentCustomer.data.features, 'config.security') && (
            <Tab eventKey="security" title="Security" key="security-config">
              <SecurityConfiguration activeTab={section} />
            </Tab>
          )}
        </YBTabsPanel>
      </div>
    );
  }
}
export default withRouter(DataCenterConfiguration);
