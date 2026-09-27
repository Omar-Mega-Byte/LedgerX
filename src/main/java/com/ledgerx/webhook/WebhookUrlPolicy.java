package com.ledgerx.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Conservative registration-time guard for the outbound webhook SSRF boundary. */
@Component
public class WebhookUrlPolicy {

  private final WebhookProperties properties;

  public WebhookUrlPolicy(WebhookProperties properties) {
    this.properties = properties;
  }

  public String normalize(String rawUrl) {
    if (!StringUtils.hasText(rawUrl) || rawUrl.length() > 2048) {
      throw new WebhookValidationException("webhook URL must be between 1 and 2048 characters");
    }
    try {
      URI uri = new URI(rawUrl.trim());
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
        throw new WebhookValidationException("webhook URL must use HTTPS");
      }
      if (scheme.equalsIgnoreCase("http") && !properties.isAllowHttp()) {
        throw new WebhookValidationException("webhook URL must use HTTPS");
      }
      if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
        throw new WebhookValidationException(
            "webhook URL must have a host and cannot contain credentials or a fragment");
      }
      if (uri.getRawQuery() != null) {
        throw new WebhookValidationException("webhook URL cannot contain a query string");
      }
      if (!properties.isAllowHttp() && uri.getPort() != -1 && uri.getPort() != 443) {
        throw new WebhookValidationException("webhook URL must use the standard HTTPS port");
      }
      rejectUnsafeHost(uri.getHost());
      return new URI(
              scheme.toLowerCase(Locale.ROOT),
              null,
              uri.getHost().toLowerCase(Locale.ROOT),
              uri.getPort(),
              uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath(),
              null,
              null)
          .toASCIIString();
    } catch (URISyntaxException exception) {
      throw new WebhookValidationException("webhook URL is invalid");
    }
  }

  private void rejectUnsafeHost(String host) {
    if (properties.isAllowLocalTargets()) {
      return;
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")) {
      throw new WebhookValidationException("webhook URL cannot target a local address");
    }
    if (isLiteralAddress(host) && isPrivateOrLocalAddress(host)) {
      throw new WebhookValidationException("webhook URL cannot target a private address");
    }
  }

  private boolean isLiteralAddress(String host) {
    return host.matches("[0-9.]+") || host.startsWith("[") || host.contains(":");
  }

  private boolean isPrivateOrLocalAddress(String host) {
    try {
      String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
      InetAddress address = InetAddress.getByName(literal);
      return WebhookAddressPolicy.isForbidden(address);
    } catch (Exception exception) {
      throw new WebhookValidationException("webhook URL host is invalid");
    }
  }
}
