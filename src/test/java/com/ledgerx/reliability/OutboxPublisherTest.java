package com.ledgerx.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

  private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");

  @Test
  void marksTheClaimedEventPublishedAfterKafkaAcknowledgesIt() {
    OutboxEventStore eventStore = Mockito.mock(OutboxEventStore.class);
    KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
    OutboxEvent event = event();
    when(eventStore.claimNext(eq(NOW), any(Duration.class))).thenReturn(Optional.of(event));
    when(kafkaTemplate.send(
            "ledgerx.payment-events.v1", event.aggregateId().toString(), event.payload()))
        .thenReturn(CompletableFuture.completedFuture(null));

    OutboxPublisher publisher = publisher(eventStore, kafkaTemplate);

    assertThat(publisher.publishNext()).isTrue();
    verify(eventStore).markPublished(event, NOW);
  }

  @Test
  void returnsAFailedBrokerSendToTheRetryableOutboxState() {
    OutboxEventStore eventStore = Mockito.mock(OutboxEventStore.class);
    KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
    OutboxEvent event = event();
    when(eventStore.claimNext(eq(NOW), any(Duration.class))).thenReturn(Optional.of(event));
    when(kafkaTemplate.send(
            "ledgerx.payment-events.v1", event.aggregateId().toString(), event.payload()))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

    OutboxPublisher publisher = publisher(eventStore, kafkaTemplate);

    assertThat(publisher.publishNext()).isTrue();
    verify(eventStore).scheduleRetry(event, NOW.plusSeconds(1), "broker unavailable");
  }

  private OutboxPublisher publisher(
      OutboxEventStore eventStore, KafkaTemplate<String, String> kafkaTemplate) {
    OutboxProperties outboxProperties = new OutboxProperties();
    outboxProperties.setLeaseDuration(Duration.ofSeconds(30));
    outboxProperties.setPublishTimeout(Duration.ofSeconds(1));
    outboxProperties.setRetryBaseDelay(Duration.ofSeconds(1));
    outboxProperties.setRetryMaxDelay(Duration.ofMinutes(5));
    KafkaProperties kafkaProperties = new KafkaProperties();
    return new OutboxPublisher(
        eventStore,
        kafkaTemplate,
        outboxProperties,
        kafkaProperties,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private OutboxEvent event() {
    return new OutboxEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        PaymentEventType.PAYMENT_COMPLETED,
        "{\"eventId\":\"demo\"}",
        NOW,
        OutboxStatus.IN_FLIGHT,
        1,
        UUID.randomUUID());
  }
}
