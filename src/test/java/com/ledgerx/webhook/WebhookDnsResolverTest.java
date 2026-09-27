package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebhookDnsResolverTest {

  @Test
  void rejectsMixedPublicAndPrivateAnswers() {
    WebhookDnsResolver resolver =
        new WebhookDnsResolver(
            new WebhookProperties(),
            host -> new InetAddress[] {address(8, 8, 8, 8), address(10, 0, 0, 1)});

    assertThatThrownBy(() -> resolver.resolve("merchant.example.test"))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  void rechecksEachResolutionAndRejectsRebinding() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    WebhookDnsResolver resolver =
        new WebhookDnsResolver(
            new WebhookProperties(),
            host ->
                new InetAddress[] {
                  calls.incrementAndGet() == 1 ? address(8, 8, 8, 8) : address(127, 0, 0, 1)
                });

    assertThat(resolver.resolve("merchant.example.test")).containsExactly(address(8, 8, 8, 8));
    assertThatThrownBy(() -> resolver.resolve("merchant.example.test"))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  void rejectsSharedAndDocumentationRanges() throws Exception {
    WebhookDnsResolver resolver =
        new WebhookDnsResolver(
            new WebhookProperties(), host -> new InetAddress[] {address(100, 64, 1, 1)});
    assertThatThrownBy(() -> resolver.resolve("merchant.example.test"))
        .isInstanceOf(UnknownHostException.class);
    assertThat(WebhookAddressPolicy.isForbidden(address(192, 0, 2, 1))).isTrue();
    assertThat(WebhookAddressPolicy.isForbidden(address(192, 0, 0, 9))).isTrue();
    assertThat(WebhookAddressPolicy.isForbidden(address(192, 88, 99, 1))).isTrue();
    assertThat(WebhookAddressPolicy.isForbidden(InetAddress.getByName("2002:7f00:1::1"))).isTrue();
    assertThat(WebhookAddressPolicy.isForbidden(address(8, 8, 8, 8))).isFalse();
  }

  private static InetAddress address(int a, int b, int c, int d) {
    try {
      return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    } catch (UnknownHostException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
