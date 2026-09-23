package com.ledgerx.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.reliability.PaymentEventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Separate idempotent Kafka consumer that queues public webhook work without calling a remote
 * server.
 */
@Component
@ConditionalOnProperty(prefix = "ledgerx.webhooks", name = "consumer-enabled", havingValue = "true")
public class WebhookEventConsumer {

  private final ObjectMapper objectMapper;
  private final WebhookEventEnqueueService enqueueService;

  public WebhookEventConsumer(
      ObjectMapper objectMapper, WebhookEventEnqueueService enqueueService) {
    this.objectMapper = objectMapper;
    this.enqueueService = enqueueService;
  }

  @KafkaListener(
      topics = "${ledgerx.kafka.payment-events-topic}",
      groupId = "${ledgerx.webhooks.consumer-group:ledgerx-webhook-delivery-enqueuer-v1}")
  public void consume(String payload) {
    enqueueService.enqueue(parse(payload), payload);
  }

  private PaymentEventEnvelope parse(String payload) {
    try {
      return objectMapper.readValue(payload, PaymentEventEnvelope.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("payment event payload is invalid", exception);
    }
  }
}
