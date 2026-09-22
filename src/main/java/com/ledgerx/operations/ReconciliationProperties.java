package com.ledgerx.operations;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerx.reconciliation")
public class ReconciliationProperties {

  private boolean enabled;
  private String cron = "0 15 * * * *";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getCron() {
    return cron;
  }

  public void setCron(String cron) {
    this.cron = cron;
  }
}
