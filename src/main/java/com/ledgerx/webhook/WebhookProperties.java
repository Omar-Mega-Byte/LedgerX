package com.ledgerx.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerx.webhooks")
public class WebhookProperties {

  private boolean consumerEnabled;
  private boolean dispatcherEnabled;
  private String consumerGroup = "ledgerx-webhook-delivery-enqueuer-v1";
  private String encryptionKey = "";
  private int encryptionKeyVersion = 1;
  private int maxEndpointsPerMerchant = 5;
  private int batchSize = 20;
  private int maxAttempts = 8;
  private Duration pollDelay = Duration.ofSeconds(5);
  private Duration leaseDuration = Duration.ofSeconds(30);
  private Duration connectTimeout = Duration.ofSeconds(5);
  private Duration requestTimeout = Duration.ofSeconds(10);
  private Duration retryBaseDelay = Duration.ofSeconds(2);
  private Duration retryMaxDelay = Duration.ofMinutes(5);
  private boolean allowHttp;
  private boolean allowLocalTargets;

  public boolean isConsumerEnabled() {
    return consumerEnabled;
  }

  public void setConsumerEnabled(boolean consumerEnabled) {
    this.consumerEnabled = consumerEnabled;
  }

  public boolean isDispatcherEnabled() {
    return dispatcherEnabled;
  }

  public void setDispatcherEnabled(boolean dispatcherEnabled) {
    this.dispatcherEnabled = dispatcherEnabled;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }

  public void setConsumerGroup(String consumerGroup) {
    this.consumerGroup = consumerGroup;
  }

  public String getEncryptionKey() {
    return encryptionKey;
  }

  public void setEncryptionKey(String encryptionKey) {
    this.encryptionKey = encryptionKey;
  }

  public int getEncryptionKeyVersion() {
    return encryptionKeyVersion;
  }

  public void setEncryptionKeyVersion(int encryptionKeyVersion) {
    this.encryptionKeyVersion = encryptionKeyVersion;
  }

  public int getMaxEndpointsPerMerchant() {
    return maxEndpointsPerMerchant;
  }

  public void setMaxEndpointsPerMerchant(int maxEndpointsPerMerchant) {
    this.maxEndpointsPerMerchant = maxEndpointsPerMerchant;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getPollDelay() {
    return pollDelay;
  }

  public void setPollDelay(Duration pollDelay) {
    this.pollDelay = pollDelay;
  }

  public Duration getLeaseDuration() {
    return leaseDuration;
  }

  public void setLeaseDuration(Duration leaseDuration) {
    this.leaseDuration = leaseDuration;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public Duration getRetryBaseDelay() {
    return retryBaseDelay;
  }

  public void setRetryBaseDelay(Duration retryBaseDelay) {
    this.retryBaseDelay = retryBaseDelay;
  }

  public Duration getRetryMaxDelay() {
    return retryMaxDelay;
  }

  public void setRetryMaxDelay(Duration retryMaxDelay) {
    this.retryMaxDelay = retryMaxDelay;
  }

  public boolean isAllowHttp() {
    return allowHttp;
  }

  public void setAllowHttp(boolean allowHttp) {
    this.allowHttp = allowHttp;
  }

  public boolean isAllowLocalTargets() {
    return allowLocalTargets;
  }

  public void setAllowLocalTargets(boolean allowLocalTargets) {
    this.allowLocalTargets = allowLocalTargets;
  }
}
