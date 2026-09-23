package com.ledgerx.webhook;

public class WebhookDeliveryConflictException extends RuntimeException {

  public WebhookDeliveryConflictException(String message) {
    super(message);
  }
}
