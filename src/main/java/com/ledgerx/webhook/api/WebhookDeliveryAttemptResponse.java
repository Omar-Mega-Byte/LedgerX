package com.ledgerx.webhook.api;

import com.ledgerx.webhook.WebhookDeliveryAttempt;
import com.ledgerx.webhook.WebhookDeliveryOutcome;
import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryAttemptResponse(
    UUID attemptId,
    int replayCount,
    int attemptNumber,
    Instant startedAt,
    Instant completedAt,
    WebhookDeliveryOutcome outcome,
    Integer httpStatus,
    long durationMillis,
    String errorCategory) {
  public static WebhookDeliveryAttemptResponse from(WebhookDeliveryAttempt attempt) {
    return new WebhookDeliveryAttemptResponse(
        attempt.id(),
        attempt.replayCount(),
        attempt.attemptNumber(),
        attempt.startedAt(),
        attempt.completedAt(),
        attempt.outcome(),
        attempt.httpStatus(),
        attempt.durationMillis(),
        attempt.errorCategory());
  }
}
