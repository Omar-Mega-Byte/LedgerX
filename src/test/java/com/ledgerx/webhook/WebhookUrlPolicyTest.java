package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebhookUrlPolicyTest {

  @Test
  void normalizesAnHttpsEndpointWithoutCredentialsOrQueryParameters() {
    WebhookUrlPolicy policy = new WebhookUrlPolicy(new WebhookProperties());

    assertThat(policy.normalize("https://Merchant.Example.com/hooks"))
        .isEqualTo("https://merchant.example.com/hooks");
  }

  @Test
  void rejectsHttpAndCredentialBearingEndpointsByDefault() {
    WebhookUrlPolicy policy = new WebhookUrlPolicy(new WebhookProperties());

    assertThatThrownBy(() -> policy.normalize("http://merchant.example.com/hooks"))
        .isInstanceOf(WebhookValidationException.class);
    assertThatThrownBy(() -> policy.normalize("https://user:password@merchant.example.com/hooks"))
        .isInstanceOf(WebhookValidationException.class);
  }
}
