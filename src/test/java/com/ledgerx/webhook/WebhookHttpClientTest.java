package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookHttpClientTest {

  private HttpServer server;
  private AtomicInteger deliveries;
  private AtomicReference<String> body;
  private AtomicReference<String> signature;
  private AtomicReference<String> eventId;

  @BeforeEach
  void startReceiver() throws IOException {
    deliveries = new AtomicInteger();
    body = new AtomicReference<>();
    signature = new AtomicReference<>();
    eventId = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hooks",
        exchange -> {
          deliveries.incrementAndGet();
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          signature.set(exchange.getRequestHeaders().getFirst("LedgerX-Signature"));
          eventId.set(exchange.getRequestHeaders().getFirst("LedgerX-Event-Id"));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/hooks");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/throttle",
        exchange -> {
          exchange.getResponseHeaders().add("Retry-After", "3");
          exchange.sendResponseHeaders(429, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopReceiver() {
    server.stop(0);
  }

  @Test
  void postsExactBodyAndSignatureHeadersToTheReceiver() {
    String payload = "{\"amount\":\"0.01\"}";
    WebhookHttpResponse response =
        client()
            .post(
                url("/hooks"),
                "event-id",
                "payment.completed.v1",
                "delivery-id",
                123L,
                "v1=abc",
                payload);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(deliveries).hasValue(1);
    assertThat(body).hasValue(payload);
    assertThat(signature).hasValue("v1=abc");
    assertThat(eventId).hasValue("event-id");
  }

  @Test
  void doesNotFollowRedirectsAndParsesRetryAfter() {
    WebhookHttpClient client = client();
    WebhookHttpResponse redirect =
        client.post(
            url("/redirect"),
            "event-id",
            "payment.completed.v1",
            "delivery-id",
            123L,
            "v1=abc",
            "{}");
    WebhookHttpResponse throttle =
        client.post(
            url("/throttle"),
            "event-id",
            "payment.completed.v1",
            "delivery-id",
            123L,
            "v1=abc",
            "{}");

    assertThat(redirect.statusCode()).isEqualTo(302);
    assertThat(deliveries).hasValue(0);
    assertThat(throttle.statusCode()).isEqualTo(429);
    assertThat(throttle.retryAfter()).isEqualTo(Duration.ofSeconds(3));
  }

  private WebhookHttpClient client() {
    WebhookProperties properties = new WebhookProperties();
    properties.setConnectTimeout(Duration.ofSeconds(2));
    properties.setRequestTimeout(Duration.ofSeconds(2));
    return new WebhookHttpClient(properties);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }
}
