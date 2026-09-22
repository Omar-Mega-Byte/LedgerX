package com.ledgerx.webhook.api;

import com.ledgerx.webhook.WebhookDelivery;
import com.ledgerx.webhook.WebhookDeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Redacted delivery view: body, signature, secret, and receiver response are deliberately omitted.
 */
public record WebhookDeliveryResponse(
    UUID deliveryId,
    UUID eventId,
    String eventType,
    WebhookDeliveryStatus status,
    int attemptCount,
    int replayCount,
    @Schema(format = "date-time") Instant queuedAt,
    @Schema(format = "date-time") Instant nextAttemptAt,
    @Schema(format = "date-time") Instant deliveredAt,
    Integer lastHttpStatus,
    String lastErrorCategory) {

  public static WebhookDeliveryResponse from(WebhookDelivery delivery) {
    return new WebhookDeliveryResponse(
        delivery.id(),
        delivery.eventId(),
        delivery.eventType().wireName(),
        delivery.status(),
        delivery.attemptCount(),
        delivery.replayCount(),
        delivery.queuedAt(),
        delivery.nextAttemptAt(),
        delivery.deliveredAt(),
        delivery.lastHttpStatus(),
        delivery.lastError());
  }
}
