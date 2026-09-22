package com.ledgerx.webhook;

import com.ledgerx.reliability.PaymentEventType;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public record WebhookEndpoint(
    UUID id,
    UUID ownerId,
    String targetUrl,
    Set<PaymentEventType> eventTypes,
    byte[] secretCiphertext,
    int secretKeyVersion,
    WebhookEndpointStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant disabledAt) {

  public WebhookEndpoint {
    eventTypes = Set.copyOf(eventTypes);
    secretCiphertext = Arrays.copyOf(secretCiphertext, secretCiphertext.length);
  }

  @Override
  public byte[] secretCiphertext() {
    return Arrays.copyOf(secretCiphertext, secretCiphertext.length);
  }

  public static String encodeEventTypes(Set<PaymentEventType> eventTypes) {
    if (eventTypes == null || eventTypes.isEmpty()) {
      throw new WebhookValidationException("at least one webhook event type is required");
    }
    StringBuilder encoded = new StringBuilder("|");
    for (PaymentEventType eventType : PaymentEventType.values()) {
      if (eventTypes.contains(eventType)) {
        encoded.append(eventType.wireName()).append('|');
      }
    }
    return encoded.toString();
  }

  public static Set<PaymentEventType> decodeEventTypes(String encoded) {
    Set<PaymentEventType> eventTypes = EnumSet.noneOf(PaymentEventType.class);
    for (PaymentEventType eventType : PaymentEventType.values()) {
      if (encoded.contains("|" + eventType.wireName() + "|")) {
        eventTypes.add(eventType);
      }
    }
    return eventTypes;
  }
}
