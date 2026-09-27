package com.ledgerx.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Rejects non-public destinations before an outbound webhook connection is opened. */
final class WebhookAddressPolicy {

  private WebhookAddressPolicy() {}

  static boolean isForbidden(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      int third = Byte.toUnsignedInt(bytes[2]);
      return first == 0
          || first >= 224
          || (first == 100 && second >= 64 && second <= 127)
          || (first == 169 && second == 254)
          || (first == 192 && second == 0 && (third == 0 || third == 2))
          || (first == 192 && second == 88 && third == 99)
          || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
          || (first == 203 && second == 0 && third == 113);
    }
    if (address instanceof Inet6Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      int third = Byte.toUnsignedInt(bytes[2]);
      int fourth = Byte.toUnsignedInt(bytes[3]);
      return (first & 0xe0) != 0x20 // only global unicast 2000::/3
          || (first == 0x20 && second == 0x02) // 6to4 embeds an IPv4 address
          || (first == 0x20
              && second == 0x01
              && (third == 0x00 // Teredo and IANA special-purpose ranges
                  || (third == 0x0d && fourth == 0xb8) // documentation
                  || (third == 0x00 && fourth == 0x02) // benchmark
                  || (third == 0x00 && fourth == 0x20))); // ORCHID
    }
    return true;
  }
}
