package com.ledgerx.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Records a minimal durable downstream effect while safely ignoring duplicate Kafka delivery. */
@Component
@ConditionalOnProperty(prefix = "ledgerx.kafka", name = "consumer-enabled", havingValue = "true")
public class PaymentEventAuditConsumer {

  static final String CONSUMER_NAME = "payment-event-audit-v1";

  private final ObjectMapper objectMapper;
  private final ProcessedEventStore processedEventStore;

  public PaymentEventAuditConsumer(
      ObjectMapper objectMapper, ProcessedEventStore processedEventStore) {
    this.objectMapper = objectMapper;
    this.processedEventStore = processedEventStore;
  }

  @KafkaListener(topics = "${ledgerx.kafka.payment-events-topic}")
  public void consume(String payload) {
    PaymentEventEnvelope envelope = parse(payload);
    processedEventStore.record(CONSUMER_NAME, envelope, payload);
  }

  private PaymentEventEnvelope parse(String payload) {
    try {
      return objectMapper.readValue(payload, PaymentEventEnvelope.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("payment event payload is invalid", exception);
    }
  }
}
