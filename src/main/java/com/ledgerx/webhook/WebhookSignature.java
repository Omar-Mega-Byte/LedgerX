package com.ledgerx.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {

  private WebhookSignature() {}

  public static String sign(String secret, long timestamp, String rawBody) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signature = mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
      return "v1=" + HexFormat.of().formatHex(signature);
    } catch (InvalidKeyException exception) {
      throw new IllegalStateException("webhook signing key is invalid", exception);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("HMAC-SHA-256 must be available", exception);
    }
  }
}
