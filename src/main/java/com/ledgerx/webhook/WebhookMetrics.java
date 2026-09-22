package com.ledgerx.webhook;

import com.ledgerx.reliability.PaymentEventType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Bounded operational metrics; URLs, UUIDs, payloads, and error text never become tags. */
@Component
public class WebhookMetrics {

  private final MeterRegistry meterRegistry;

  public WebhookMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void queued(PaymentEventType eventType) {
    meterRegistry.counter("ledgerx.webhook.queued", "event_type", eventType.wireName()).increment();
  }

  public void attempted(PaymentEventType eventType, WebhookDeliveryOutcome outcome) {
    meterRegistry
        .counter(
            "ledgerx.webhook.attempts",
            "event_type",
            eventType.wireName(),
            "outcome",
            outcome.name())
        .increment();
  }
}
