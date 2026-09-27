package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebhookProductionGuardTest {

  @Test
  void rejectsDevelopmentNetworkExceptionsInProduction() {
    WebhookProperties properties = new WebhookProperties();
    properties.setAllowLocalTargets(true);
    assertThatThrownBy(() -> new WebhookProductionGuard(properties).verify())
        .isInstanceOf(IllegalStateException.class);
    properties.setAllowLocalTargets(false);
    properties.setAllowHttp(true);
    assertThatThrownBy(() -> new WebhookProductionGuard(properties).verify())
        .isInstanceOf(IllegalStateException.class);
  }
}
