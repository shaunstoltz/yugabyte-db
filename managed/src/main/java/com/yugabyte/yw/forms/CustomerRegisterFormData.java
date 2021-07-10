// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.forms;

import play.data.validation.Constraints;
import java.util.Map;

import com.yugabyte.yw.common.alerts.SmtpData;

/** This class will be used by the API and UI Form Elements to validate constraints are met */
public class CustomerRegisterFormData {
  @Constraints.Required()
  @Constraints.MaxLength(15)
  private String code;

  @Constraints.Required() @Constraints.Email private String email;

  private String password;

  private String confirmPassword;

  @Constraints.Required()
  @Constraints.MinLength(3)
  private String name;

  private Map features;

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getConfirmPassword() {
    return confirmPassword;
  }

  public void setConfirmPassword(String confirmPassword) {
    this.confirmPassword = confirmPassword;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Map getFeatures() {
    return features;
  }

  public void setFeatures(Map features) {
    this.features = features;
  }

  public AlertingData getAlertingData() {
    return alertingData;
  }

  public void setAlertingData(AlertingData alertingData) {
    this.alertingData = alertingData;
  }

  public SmtpData getSmtpData() {
    return smtpData;
  }

  public void setSmtpData(SmtpData smtpData) {
    this.smtpData = smtpData;
  }

  public String getCallhomeLevel() {
    return callhomeLevel;
  }

  public void setCallhomeLevel(String callhomeLevel) {
    this.callhomeLevel = callhomeLevel;
  }

  public static class AlertingData {
    @Constraints.Email
    @Constraints.MinLength(5)
    public String alertingEmail;

    public boolean sendAlertsToYb = false;

    public long checkIntervalMs = 0;

    public long statusUpdateIntervalMs = 0;

    public Boolean reportOnlyErrors = false;

    public Boolean reportBackupFailures = false;
  }

  public AlertingData alertingData;

  public SmtpData smtpData;

  @Constraints.Pattern(
      message = "Must be one of NONE, LOW, MEDIUM, HIGH",
      value = "\\b(?:NONE|LOW|MEDIUM|HIGH)\\b")
  public String callhomeLevel = "MEDIUM";
}
