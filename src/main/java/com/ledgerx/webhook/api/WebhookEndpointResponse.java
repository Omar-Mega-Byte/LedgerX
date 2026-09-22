package com.ledgerx.webhook.api;

import com.ledgerx.reliability.PaymentEventType;
import com.ledgerx.webhook.WebhookEndpoint;
import com.ledgerx.webhook.WebhookEndpointStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record WebhookEndpointResponse(
    UUID endpointId,
    String url,
    Set<String> eventTypes,
    WebhookEndpointStatus status,
    int secretKeyVersion,
    @Schema(format = "date-time") Instant createdAt,
    @Schema(format = "date-time") Instant updatedAt,
    @Schema(format = "date-time") Instant disabledAt) {

  public static WebhookEndpointResponse from(WebhookEndpoint endpoint) {
    return new WebhookEndpointResponse(
        endpoint.id(),
        endpoint.targetUrl(),
        endpoint.eventTypes().stream()
            .map(PaymentEventType::wireName)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
        endpoint.status(),
        endpoint.secretKeyVersion(),
        endpoint.createdAt(),
        endpoint.updatedAt(),
        endpoint.disabledAt());
  }
}
