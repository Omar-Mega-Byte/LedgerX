package com.ledgerx.webhook;

public class WebhookIdempotencyKeyReuseException extends RuntimeException {

  public WebhookIdempotencyKeyReuseException() {
    super("idempotency key was already used for a different webhook configuration");
  }
}
