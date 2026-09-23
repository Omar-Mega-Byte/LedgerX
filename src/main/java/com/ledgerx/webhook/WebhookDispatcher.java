package com.ledgerx.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends durable webhook instructions after they have been claimed by a short PostgreSQL lease. */
@Component
@ConditionalOnProperty(
    prefix = "ledgerx.webhooks",
    name = "dispatcher-enabled",
    havingValue = "true")
public class WebhookDispatcher {

  private final WebhookDeliveryStore deliveryStore;
  private final WebhookSecretCipher secretCipher;
  private final WebhookHttpClient httpClient;
  private final WebhookProperties properties;
  private final WebhookMetrics webhookMetrics;
  private final Clock clock;

  public WebhookDispatcher(
      WebhookDeliveryStore deliveryStore,
      WebhookSecretCipher secretCipher,
      WebhookHttpClient httpClient,
      WebhookProperties properties,
      WebhookMetrics webhookMetrics,
      Clock clock) {
    this.deliveryStore = deliveryStore;
    this.secretCipher = secretCipher;
    this.httpClient = httpClient;
    this.properties = properties;
    this.webhookMetrics = webhookMetrics;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${ledgerx.webhooks.poll-delay:PT5S}")
  public void dispatchAvailable() {
    for (int sent = 0; sent < properties.getBatchSize(); sent++) {
      if (!dispatchNext()) {
        return;
      }
    }
  }

  boolean dispatchNext() {
    Instant claimedAt = clock.instant();
    Optional<WebhookClaim> claimed =
        deliveryStore.claimNext(claimedAt, properties.getLeaseDuration());
    if (claimed.isEmpty()) {
      return false;
    }

    WebhookClaim claim = claimed.get();
    Instant startedAt = clock.instant();
    WebhookDelivery delivery = claim.delivery();
    long timestamp = startedAt.getEpochSecond();
    String signature;
    try {
      signature =
          WebhookSignature.sign(
              secretCipher.decrypt(claim.secretCiphertext()), timestamp, delivery.payload());
    } catch (RuntimeException exception) {
      completeFailure(claim, startedAt, clock.instant(), null, "SIGNING_FAILURE");
      return true;
    }

    WebhookHttpResponse response;
    try {
      response =
          httpClient.post(
              claim.targetUrl(),
              delivery.eventId().toString(),
              delivery.eventType().wireName(),
              delivery.id().toString(),
              timestamp,
              signature,
              delivery.payload());
    } catch (WebhookTransportException exception) {
      completeFailure(claim, startedAt, clock.instant(), null, exception.category());
      return true;
    }
    completeResponse(claim, startedAt, clock.instant(), response);
    return true;
  }

  private void completeResponse(
      WebhookClaim claim, Instant startedAt, Instant completedAt, WebhookHttpResponse response) {
    int statusCode = response.statusCode();
    if (statusCode >= 200 && statusCode < 300) {
      deliveryStore.markDelivered(claim, startedAt, completedAt, statusCode);
      webhookMetrics.attempted(claim.delivery().eventType(), WebhookDeliveryOutcome.DELIVERED);
      return;
    }
    String category = "HTTP_" + statusCode;
    if (isRetryable(statusCode)) {
      completeFailure(claim, startedAt, completedAt, statusCode, category, response.retryAfter());
      return;
    }
    deliveryStore.markDead(claim, startedAt, completedAt, statusCode, category);
    webhookMetrics.attempted(claim.delivery().eventType(), WebhookDeliveryOutcome.TERMINAL_FAILURE);
  }

  private void completeFailure(
      WebhookClaim claim,
      Instant startedAt,
      Instant completedAt,
      Integer httpStatus,
      String category) {
    completeFailure(claim, startedAt, completedAt, httpStatus, category, null);
  }

  private void completeFailure(
      WebhookClaim claim,
      Instant startedAt,
      Instant completedAt,
      Integer httpStatus,
      String category,
      Duration receiverRetryAfter) {
    if (claim.delivery().attemptCount() >= properties.getMaxAttempts()) {
      deliveryStore.markDead(claim, startedAt, completedAt, httpStatus, category);
      webhookMetrics.attempted(
          claim.delivery().eventType(), WebhookDeliveryOutcome.TERMINAL_FAILURE);
      return;
    }
    deliveryStore.scheduleRetry(
        claim,
        startedAt,
        completedAt,
        httpStatus,
        category,
        completedAt.plus(retryDelay(claim.delivery(), receiverRetryAfter)));
    webhookMetrics.attempted(
        claim.delivery().eventType(), WebhookDeliveryOutcome.RETRYABLE_FAILURE);
  }

  private boolean isRetryable(int statusCode) {
    return statusCode == 408
        || statusCode == 425
        || statusCode == 429
        || (statusCode >= 500 && statusCode <= 599);
  }

  private Duration retryDelay(WebhookDelivery delivery, Duration receiverRetryAfter) {
    int exponent = Math.min(delivery.attemptCount() - 1, 20);
    Duration delay = properties.getRetryBaseDelay().multipliedBy(1L << exponent);
    if (delay.compareTo(properties.getRetryMaxDelay()) > 0) {
      delay = properties.getRetryMaxDelay();
    }
    if (receiverRetryAfter != null && receiverRetryAfter.compareTo(delay) > 0) {
      delay = receiverRetryAfter;
    }
    return delay.compareTo(properties.getRetryMaxDelay()) > 0
        ? properties.getRetryMaxDelay()
        : delay;
  }
}
