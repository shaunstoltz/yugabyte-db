package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.common.CertificateDetails;
import com.yugabyte.yw.common.CertificateHelper;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.forms.CertificateParams;
import com.yugabyte.yw.forms.ClientCertParams;
import com.yugabyte.yw.forms.YWResults.YWError;
import com.yugabyte.yw.forms.YWResults;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.Customer;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.libs.Json;
import play.mvc.Result;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Api(
    value = "Certificate Info",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class CertificateController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(CertificateController.class);
  @Inject private RuntimeConfigFactory runtimeConfigFactory;

  @ApiOperation(value = "restore Backups", response = UUID.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "certificate",
          value = "certificate params of the backup to be restored",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.CertificateParams",
          required = true))
  public Result upload(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);
    Form<CertificateParams> formData = formFactory.getFormDataOrBadRequest(CertificateParams.class);

    Date certStart = new Date(formData.get().certStart);
    Date certExpiry = new Date(formData.get().certExpiry);
    String label = formData.get().label;
    CertificateInfo.Type certType = formData.get().certType;
    String certContent = formData.get().certContent;
    String keyContent = formData.get().keyContent;
    CertificateParams.CustomCertInfo customCertInfo = formData.get().customCertInfo;
    CertificateParams.CustomServerCertData customServerCertData =
        formData.get().customServerCertData;
    switch (certType) {
      case SelfSigned:
        {
          if (certContent == null || keyContent == null) {
            throw new YWServiceException(BAD_REQUEST, "Certificate or Keyfile can't be null.");
          }
          break;
        }
      case CustomCertHostPath:
        {
          if (customCertInfo == null) {
            throw new YWServiceException(BAD_REQUEST, "Custom Cert Info must be provided.");
          } else if (customCertInfo.nodeCertPath == null
              || customCertInfo.nodeKeyPath == null
              || customCertInfo.rootCertPath == null) {
            throw new YWServiceException(BAD_REQUEST, "Custom Cert Paths can't be empty.");
          }
          break;
        }
      case CustomServerCert:
        {
          if (customServerCertData == null) {
            throw new YWServiceException(BAD_REQUEST, "Custom Server Cert Info must be provided.");
          } else if (customServerCertData.serverCertContent == null
              || customServerCertData.serverKeyContent == null) {
            throw new YWServiceException(
                BAD_REQUEST, "Custom Server Cert and Key content can't be empty.");
          }
          break;
        }
      default:
        {
          throw new YWServiceException(BAD_REQUEST, "certType should be valid.");
        }
    }
    LOG.info("CertificateController: upload cert label {}, type {}", label, certType);
    UUID certUUID =
        CertificateHelper.uploadRootCA(
            label,
            customerUUID,
            runtimeConfigFactory.staticApplicationConf().getString("yb.storage.path"),
            certContent,
            keyContent,
            certStart,
            certExpiry,
            certType,
            customCertInfo,
            customServerCertData);
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return YWResults.withData(certUUID);
  }

  @ApiOperation(value = "post certificate info", response = CertificateDetails.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "certificate",
          value = "post certificate info",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.ClientCertParams",
          required = true))
  public Result getClientCert(UUID customerUUID, UUID rootCA) {
    Form<ClientCertParams> formData = formFactory.getFormDataOrBadRequest(ClientCertParams.class);
    Customer.getOrBadRequest(customerUUID);
    long certTimeMillis = formData.get().certStart;
    long certExpiryMillis = formData.get().certExpiry;
    Date certStart = certTimeMillis != 0L ? new Date(certTimeMillis) : null;
    Date certExpiry = certExpiryMillis != 0L ? new Date(certExpiryMillis) : null;

    CertificateDetails result =
        CertificateHelper.createClientCertificate(
            rootCA, null, formData.get().username, certStart, certExpiry);
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return YWResults.withData(result);
  }

  @ApiOperation(value = "get root certificate", response = JsonNode.class)
  public Result getRootCert(UUID customerUUID, UUID rootCA) {
    Customer.getOrBadRequest(customerUUID);
    CertificateInfo.getOrBadRequest(rootCA, customerUUID);

    String certContents = CertificateHelper.getCertPEMFileContents(rootCA);
    auditService().createAuditEntry(ctx(), request());
    ObjectNode result = Json.newObject();
    result.put(CertificateHelper.ROOT_CERT, certContents);
    return YWResults.withRawData(result);
  }

  @ApiOperation(
      value = "list Certificates for a specific customer",
      response = CertificateInfo.class,
      responseContainer = "List")
  @ApiResponses(
      @io.swagger.annotations.ApiResponse(
          code = 500,
          message = "If there was a server or database issue when listing the regions",
          response = YWError.class))
  public Result list(UUID customerUUID) {
    List<CertificateInfo> certs = CertificateInfo.getAll(customerUUID);
    return YWResults.withData(certs);
  }

  @ApiOperation(value = "get certificate UUID", response = UUID.class)
  public Result get(UUID customerUUID, String label) {
    CertificateInfo cert = CertificateInfo.getOrBadRequest(label);
    return YWResults.withData(cert.uuid);
  }

  @ApiOperation(value = "delete certificate", response = YWResults.YWSuccess.class)
  public Result delete(UUID customerUUID, UUID reqCertUUID) {
    CertificateInfo.delete(reqCertUUID, customerUUID);
    auditService().createAuditEntry(ctx(), request());
    LOG.info("Successfully deleted the certificate:" + reqCertUUID);
    return YWResults.YWSuccess.empty();
  }

  @ApiOperation(value = "update empty certs", response = CertificateInfo.class)
  public Result updateEmptyCustomCert(UUID customerUUID, UUID rootCA) {
    Form<CertificateParams> formData = formFactory.getFormDataOrBadRequest(CertificateParams.class);
    Customer.getOrBadRequest(customerUUID);
    CertificateInfo certificate = CertificateInfo.getOrBadRequest(rootCA, customerUUID);
    CertificateParams.CustomCertInfo customCertInfo = formData.get().customCertInfo;
    certificate.setCustomCertInfo(customCertInfo, rootCA, customerUUID);
    return YWResults.withData(certificate);
  }
}
