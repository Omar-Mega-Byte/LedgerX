package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerx.reliability.PaymentEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class WebhookDispatcherTest {

  private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");

  @Test
  void signsAndMarksAClaimedDeliveryAfterA2xxResponse() {
    WebhookDeliveryStore deliveryStore = Mockito.mock(WebhookDeliveryStore.class);
    WebhookSecretCipher secretCipher = Mockito.mock(WebhookSecretCipher.class);
    WebhookHttpClient httpClient = Mockito.mock(WebhookHttpClient.class);
    WebhookMetrics webhookMetrics = Mockito.mock(WebhookMetrics.class);
    WebhookClaim claim = claim();
    when(deliveryStore.claimNext(eq(NOW), any(Duration.class))).thenReturn(Optional.of(claim));
    when(secretCipher.decrypt(claim.secretCiphertext()))
        .thenReturn("merchant-secret-0123456789-abcdef");
    when(httpClient.post(any(), any(), any(), any(), any(Long.class), any(), any()))
        .thenReturn(new WebhookHttpResponse(204, null));

    WebhookDispatcher dispatcher =
        new WebhookDispatcher(
            deliveryStore,
            secretCipher,
            httpClient,
            properties(),
            webhookMetrics,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(dispatcher.dispatchNext()).isTrue();
    verify(deliveryStore).markDelivered(claim, NOW, NOW, 204);
    verify(webhookMetrics)
        .attempted(PaymentEventType.PAYMENT_COMPLETED, WebhookDeliveryOutcome.DELIVERED);
    ArgumentCaptor<String> signature = ArgumentCaptor.forClass(String.class);
    verify(httpClient)
        .post(
            eq("https://merchant.example.com/hooks"),
            eq(claim.delivery().eventId().toString()),
            eq("payment.completed.v1"),
            eq(claim.delivery().id().toString()),
            eq(NOW.getEpochSecond()),
            signature.capture(),
            eq(claim.delivery().payload()));
    assertThat(signature.getValue()).startsWith("v1=");
  }

  private WebhookProperties properties() {
    WebhookProperties properties = new WebhookProperties();
    properties.setLeaseDuration(Duration.ofSeconds(30));
    properties.setMaxAttempts(8);
    return properties;
  }

  private WebhookClaim claim() {
    WebhookDelivery delivery =
        new WebhookDelivery(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            PaymentEventType.PAYMENT_COMPLETED,
            1,
            "{\"id\":\"event\"}",
            "a".repeat(64),
            NOW,
            WebhookDeliveryStatus.IN_FLIGHT,
            1,
            0,
            NOW,
            UUID.randomUUID(),
            NOW.plusSeconds(30),
            null,
            null,
            null);
    return new WebhookClaim(delivery, "https://merchant.example.com/hooks", new byte[] {1, 2, 3});
  }
}
