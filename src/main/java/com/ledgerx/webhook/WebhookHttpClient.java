package com.ledgerx.webhook;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Outbound client whose DNS validation is part of opening each network connection. */
@Component
public class WebhookHttpClient {

  private final CloseableHttpClient httpClient;
  private final WebhookUrlPolicy urlPolicy;

  @Autowired
  public WebhookHttpClient(WebhookProperties properties) {
    this(properties, new WebhookDnsResolver(properties));
  }

  WebhookHttpClient(WebhookProperties properties, WebhookDnsResolver resolver) {
    this.urlPolicy = new WebhookUrlPolicy(properties);
    var connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(
                        Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
                    .build())
            .setMaxConnTotal(20)
            .setMaxConnPerRoute(5)
            .build();
    this.httpClient =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(
                        Timeout.ofMilliseconds(properties.getRequestTimeout().toMillis()))
                    .build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
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
      HttpPost request = new HttpPost(URI.create(urlPolicy.normalize(targetUrl)));
      request.setHeader("User-Agent", "LedgerX-Webhooks/1.0");
      request.setHeader("LedgerX-Event-Id", eventId);
      request.setHeader("LedgerX-Event-Type", eventType);
      request.setHeader("LedgerX-Delivery-Id", deliveryId);
      request.setHeader("LedgerX-Timestamp", Long.toString(timestamp));
      request.setHeader("LedgerX-Signature", signature);
      request.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
      return httpClient.execute(
          request,
          response ->
              new WebhookHttpResponse(
                  response.getCode(), retryAfter(response.getFirstHeader("Retry-After"))));
    } catch (InterruptedIOException exception) {
      throw new WebhookTransportException("TIMEOUT", exception);
    } catch (IOException | RuntimeException exception) {
      throw new WebhookTransportException("NETWORK_ERROR", exception);
    }
  }

  @PreDestroy
  public void close() throws IOException {
    httpClient.close();
  }

  private Duration retryAfter(Header header) {
    if (header == null) {
      return null;
    }
    String value = header.getValue().trim();
    try {
      long seconds = Long.parseLong(value);
      return seconds < 0 ? null : Duration.ofSeconds(seconds);
    } catch (NumberFormatException exception) {
      try {
        Instant retryAt =
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from);
        Duration duration = Duration.between(Instant.now(), retryAt);
        return duration.isNegative() ? Duration.ZERO : duration;
      } catch (Exception ignored) {
        return null;
      }
    }
  }
}
