package com.ledgerx.webhook;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Minimal public representation deliberately separated from LedgerX's internal outbox envelope. */
public record PublicWebhookEvent(
    UUID id, String type, String apiVersion, Instant occurredAt, Map<String, String> data) {

  public PublicWebhookEvent {
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
