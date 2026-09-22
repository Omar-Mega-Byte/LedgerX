package com.ledgerx.webhook;

import java.util.UUID;

public record WebhookClaim(WebhookDelivery delivery, String targetUrl, byte[] secretCiphertext) {

  public UUID leaseToken() {
    return delivery.leaseToken();
  }
}
