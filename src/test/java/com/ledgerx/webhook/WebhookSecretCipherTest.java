package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void keyRingReadsOldCiphertextAfterActivatingANewVersion() {
    String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] newKeyBytes = new byte[32];
    Arrays.fill(newKeyBytes, (byte) 7);
    String newKey = Base64.getEncoder().encodeToString(newKeyBytes);
    WebhookProperties oldProperties = new WebhookProperties();
    oldProperties.setEncryptionKey(oldKey);
    byte[] oldCiphertext =
        new WebhookSecretCipher(oldProperties).encrypt("merchant-signing-secret-0123456789");

    WebhookProperties rotatedProperties = new WebhookProperties();
    rotatedProperties.setEncryptionKeys("1=" + oldKey + ",2=" + newKey);
    rotatedProperties.setEncryptionKeyVersion(2);
    WebhookSecretCipher rotated = new WebhookSecretCipher(rotatedProperties);
    byte[] newCiphertext = rotated.encrypt("merchant-signing-secret-9876543210");

    assertThat(rotated.decrypt(oldCiphertext, 1)).isEqualTo("merchant-signing-secret-0123456789");
    assertThat(rotated.decrypt(newCiphertext, 2)).isEqualTo("merchant-signing-secret-9876543210");
    assertThatThrownBy(() -> rotated.decrypt(oldCiphertext, 2))
        .isInstanceOf(IllegalStateException.class);
  }
}
