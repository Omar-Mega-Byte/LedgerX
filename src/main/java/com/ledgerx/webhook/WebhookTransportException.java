package com.ledgerx.webhook;

public class WebhookTransportException extends RuntimeException {

  private final String category;

  public WebhookTransportException(String category, Throwable cause) {
    super(category, cause);
    this.category = category;
  }

  public String category() {
    return category;
  }
}
