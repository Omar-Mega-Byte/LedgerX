package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class WebhookSecretCipherTest {

  @Test
  void encryptsAndDecryptsSecretWithoutPersistingItsPlaintextBytes() {
    WebhookProperties properties = new WebhookProperties();
    properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
    WebhookSecretCipher cipher = new WebhookSecretCipher(properties);
    String secret = "merchant-signing-secret-0123456789";

    byte[] ciphertext = cipher.encrypt(secret);

    assertThat(cipher.decrypt(ciphertext)).isEqualTo(secret);
    assertThat(Arrays.equals(ciphertext, secret.getBytes(StandardCharsets.UTF_8))).isFalse();
  }
}
