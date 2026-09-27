package com.ledgerx.reliability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerx.kafka")
public class KafkaProperties {

  private String paymentEventsTopic = "ledgerx.payment-events.v1";
  private boolean consumerEnabled;
  private Duration eventRetention = Duration.ofDays(14);
  private Duration deadLetterRetention = Duration.ofDays(30);

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

  public Duration getEventRetention() {
    return eventRetention;
  }

  public void setEventRetention(Duration eventRetention) {
    this.eventRetention = eventRetention;
  }

  public Duration getDeadLetterRetention() {
    return deadLetterRetention;
  }

  public void setDeadLetterRetention(Duration deadLetterRetention) {
    this.deadLetterRetention = deadLetterRetention;
  }
}
