package com.ledgerx.webhook;

public class WebhookNotFoundException extends RuntimeException {

  public WebhookNotFoundException(String message) {
    super(message);
  }
}
