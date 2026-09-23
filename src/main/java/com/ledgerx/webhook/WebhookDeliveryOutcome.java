package com.ledgerx.webhook;

public enum WebhookDeliveryOutcome {
  DELIVERED,
  RETRYABLE_FAILURE,
  TERMINAL_FAILURE
}
