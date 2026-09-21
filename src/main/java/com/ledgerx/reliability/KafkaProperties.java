package com.ledgerx.reliability;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerx.kafka")
public class KafkaProperties {

  private String paymentEventsTopic = "ledgerx.payment-events.v1";
  private boolean consumerEnabled;

  public String getPaymentEventsTopic() {
    return paymentEventsTopic;
  }

  public void setPaymentEventsTopic(String paymentEventsTopic) {
    this.paymentEventsTopic = paymentEventsTopic;
  }

  public boolean isConsumerEnabled() {
    return consumerEnabled;
  }

  public void setConsumerEnabled(boolean consumerEnabled) {
    this.consumerEnabled = consumerEnabled;
  }
}
