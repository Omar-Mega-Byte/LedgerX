package com.ledgerx.webhook;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/** Validates every DNS result in the connection path, including on subsequent DNS changes. */
final class WebhookDnsResolver implements DnsResolver {

  @FunctionalInterface
  interface AddressLookup {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final WebhookProperties properties;
  private final AddressLookup lookup;

  WebhookDnsResolver(WebhookProperties properties) {
    this(properties, InetAddress::getAllByName);
  }

  WebhookDnsResolver(WebhookProperties properties, AddressLookup lookup) {
    this.properties = properties;
    this.lookup = lookup;
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = lookup.resolve(host);
    if (addresses.length == 0) {
      throw new UnknownHostException("webhook destination has no addresses");
    }
    if (!properties.isAllowLocalTargets()) {
      for (InetAddress address : addresses) {
        if (WebhookAddressPolicy.isForbidden(address)) {
          throw new UnknownHostException("webhook destination resolves to a non-public address");
        }
      }
    }
    return addresses;
  }

  @Override
  public String resolveCanonicalHostname(String host) {
    return host;
  }
}
