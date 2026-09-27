package com.ledgerx.reliability;

import com.ledgerx.operations.OperationalMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends committed outbox facts after their database transaction has completed. */
@Component
@ConditionalOnProperty(prefix = "ledgerx.outbox", name = "publisher-enabled", havingValue = "true")
public class OutboxPublisher {

  private final OutboxEventStore outboxEventStore;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OutboxProperties outboxProperties;
  private final KafkaProperties kafkaProperties;
  private final Clock clock;
  private final OperationalMetrics metrics;

  public OutboxPublisher(
      OutboxEventStore outboxEventStore,
      KafkaTemplate<String, String> kafkaTemplate,
      OutboxProperties outboxProperties,
      KafkaProperties kafkaProperties,
      Clock clock,
      OperationalMetrics metrics) {
    this.outboxEventStore = outboxEventStore;
    this.kafkaTemplate = kafkaTemplate;
    this.outboxProperties = outboxProperties;
    this.kafkaProperties = kafkaProperties;
    this.clock = clock;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${ledgerx.outbox.poll-delay:PT5S}")
  public void publishAvailable() {
    for (int published = 0; published < outboxProperties.getBatchSize(); published++) {
      if (!publishNext()) {
        return;
      }
    }
  }

  boolean publishNext() {
    Instant now = clock.instant();
    Optional<OutboxEvent> claimed =
        outboxEventStore.claimNext(now, outboxProperties.getLeaseDuration());
    if (claimed.isEmpty()) {
      return false;
    }

    OutboxEvent event = claimed.get();
    try {
      kafkaTemplate
          .send(
              kafkaProperties.getPaymentEventsTopic(),
              event.aggregateId().toString(),
              event.payload())
          .get(outboxProperties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      outboxEventStore.scheduleRetry(event, nextAttemptAt(event, now), "publisher interrupted");
      metrics.outboxRetried();
      return true;
    } catch (Exception exception) {
      outboxEventStore.scheduleRetry(event, nextAttemptAt(event, now), failureMessage(exception));
      metrics.outboxRetried();
      return true;
    }

    outboxEventStore.markPublished(event, clock.instant());
    metrics.outboxPublished();
    return true;
  }

  private Instant nextAttemptAt(OutboxEvent event, Instant now) {
    int exponent = Math.min(event.attemptCount() - 1, 20);
    Duration delay = outboxProperties.getRetryBaseDelay().multipliedBy(1L << exponent);
    if (delay.compareTo(outboxProperties.getRetryMaxDelay()) > 0) {
      delay = outboxProperties.getRetryMaxDelay();
    }
    return now.plus(delay);
  }

  private String failureMessage(Exception exception) {
    Throwable cause = exception.getCause();
    if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
      return cause.getMessage();
    }
    return exception.getMessage();
  }
}
