package com.ledgerx.reliability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerx.outbox")
public class OutboxProperties {

  private boolean publisherEnabled;
  private Duration pollDelay = Duration.ofSeconds(5);
  private Duration leaseDuration = Duration.ofSeconds(30);
  private Duration publishTimeout = Duration.ofSeconds(10);
  private int batchSize = 20;
  private Duration retryBaseDelay = Duration.ofSeconds(1);
  private Duration retryMaxDelay = Duration.ofMinutes(5);

  public boolean isPublisherEnabled() {
    return publisherEnabled;
  }

  public void setPublisherEnabled(boolean publisherEnabled) {
    this.publisherEnabled = publisherEnabled;
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

  public Duration getPublishTimeout() {
    return publishTimeout;
  }

  public void setPublishTimeout(Duration publishTimeout) {
    this.publishTimeout = publishTimeout;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
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
}
