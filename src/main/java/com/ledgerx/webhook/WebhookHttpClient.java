package com.ledgerx.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Outbound client deliberately configured not to follow redirects or retain response content. */
@Component
public class WebhookHttpClient {

  private final HttpClient httpClient;
  private final WebhookProperties properties;

  public WebhookHttpClient(WebhookProperties properties) {
    this.properties = properties;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public WebhookHttpResponse post(
      String targetUrl,
      String eventId,
      String eventType,
      String deliveryId,
      long timestamp,
      String signature,
      String payload) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(targetUrl))
              .timeout(properties.getRequestTimeout())
              .header("Content-Type", "application/json")
              .header("User-Agent", "LedgerX-Webhooks/1.0")
              .header("LedgerX-Event-Id", eventId)
              .header("LedgerX-Event-Type", eventType)
              .header("LedgerX-Delivery-Id", deliveryId)
              .header("LedgerX-Timestamp", Long.toString(timestamp))
              .header("LedgerX-Signature", signature)
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return new WebhookHttpResponse(response.statusCode(), retryAfter(response));
    } catch (java.net.http.HttpTimeoutException exception) {
      throw new WebhookTransportException("TIMEOUT", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new WebhookTransportException("INTERRUPTED", exception);
    } catch (Exception exception) {
      throw new WebhookTransportException("NETWORK_ERROR", exception);
    }
  }

  private Duration retryAfter(HttpResponse<?> response) {
    Optional<String> value = response.headers().firstValue("Retry-After");
    if (value.isEmpty()) {
      return null;
    }
    try {
      long seconds = Long.parseLong(value.get().trim());
      return seconds < 0 ? null : Duration.ofSeconds(seconds);
    } catch (NumberFormatException exception) {
      try {
        Instant retryAt =
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(
                value.get().trim(), Instant::from);
        Duration duration = Duration.between(Instant.now(), retryAt);
        return duration.isNegative() ? Duration.ZERO : duration;
      } catch (Exception ignored) {
        return null;
      }
    }
  }
}
