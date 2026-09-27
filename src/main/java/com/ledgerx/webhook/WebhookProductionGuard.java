package com.ledgerx.webhook;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production startup fails if development-only webhook network exceptions are enabled. */
@Component
@Profile("prod")
public class WebhookProductionGuard {

  private final WebhookProperties properties;

  public WebhookProductionGuard(WebhookProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  public void verify() {
    if (properties.isAllowHttp() || properties.isAllowLocalTargets()) {
      throw new IllegalStateException(
          "production webhooks must reject HTTP and non-public destinations");
    }
  }
}
