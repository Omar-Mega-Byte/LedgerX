package com.ledgerx.webhook;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Encrypts merchant HMAC secrets before persistence; raw secrets never leave this boundary. */
@Component
public class WebhookSecretCipher {

  private static final int NONCE_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;

  private final WebhookProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public WebhookSecretCipher(WebhookProperties properties) {
    this.properties = properties;
  }

  public byte[] encrypt(String secret) {
    if (!StringUtils.hasText(secret) || secret.length() < 32) {
      throw new WebhookValidationException(
          "webhook signing secret must contain at least 32 characters");
    }
    byte[] nonce = new byte[NONCE_LENGTH];
    secureRandom.nextBytes(nonce);
    try {
      Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
      byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("webhook secret encryption failed", exception);
    }
  }

  public String decrypt(byte[] ciphertext) {
    if (ciphertext == null || ciphertext.length <= NONCE_LENGTH) {
      throw new IllegalStateException("webhook secret ciphertext is invalid");
    }
    byte[] nonce = new byte[NONCE_LENGTH];
    byte[] encrypted = new byte[ciphertext.length - NONCE_LENGTH];
    System.arraycopy(ciphertext, 0, nonce, 0, nonce.length);
    System.arraycopy(ciphertext, nonce.length, encrypted, 0, encrypted.length);
    try {
      return new String(
          cipher(Cipher.DECRYPT_MODE, nonce).doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("webhook secret decryption failed", exception);
    }
  }

  private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
    byte[] decodedKey = encryptionKey();
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        mode, new SecretKeySpec(decodedKey, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
    return cipher;
  }

  private byte[] encryptionKey() {
    if (!StringUtils.hasText(properties.getEncryptionKey())) {
      throw new WebhookValidationException("webhook encryption key is not configured");
    }
    try {
      byte[] decodedKey = Base64.getDecoder().decode(properties.getEncryptionKey());
      if (decodedKey.length != 32) {
        throw new WebhookValidationException(
            "webhook encryption key must be a base64-encoded 32-byte key");
      }
      return decodedKey;
    } catch (IllegalArgumentException exception) {
      throw new WebhookValidationException("webhook encryption key must be valid base64");
    }
  }
}
