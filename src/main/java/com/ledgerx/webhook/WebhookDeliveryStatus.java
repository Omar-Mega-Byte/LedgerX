package com.ledgerx.webhook;

public enum WebhookDeliveryStatus {
  PENDING,
  IN_FLIGHT,
  DELIVERED,
  DEAD,
  CANCELLED
}
