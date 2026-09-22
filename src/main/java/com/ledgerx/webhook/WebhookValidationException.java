package com.ledgerx.webhook;

public class WebhookValidationException extends RuntimeException {

  public WebhookValidationException(String message) {
    super(message);
  }
}
