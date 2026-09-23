package com.ledgerx.webhook;

public class WebhookIdempotencyRequestInProgressException extends RuntimeException {

  public WebhookIdempotencyRequestInProgressException() {
    super("webhook configuration request is still processing");
  }
}
