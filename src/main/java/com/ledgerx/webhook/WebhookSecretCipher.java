package com.ledgerx.webhook;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
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

  @PostConstruct
  public void validateWorkerConfiguration() {
    if (properties.isConsumerEnabled() || properties.isDispatcherEnabled()) {
      encryptionKey(properties.getEncryptionKeyVersion());
    }
  }

  public void requireKeyVersion(int keyVersion) {
    encryptionKey(keyVersion);
  }

  public byte[] encrypt(String secret) {
    if (!StringUtils.hasText(secret) || secret.length() < 32) {
      throw new WebhookValidationException(
          "webhook signing secret must contain at least 32 characters");
    }
    byte[] nonce = new byte[NONCE_LENGTH];
    secureRandom.nextBytes(nonce);
    try {
      Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, properties.getEncryptionKeyVersion());
      byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("webhook secret encryption failed", exception);
    }
  }

  public String decrypt(byte[] ciphertext) {
    return decrypt(ciphertext, properties.getEncryptionKeyVersion());
  }

  public String decrypt(byte[] ciphertext, int keyVersion) {
    if (ciphertext == null || ciphertext.length <= NONCE_LENGTH) {
      throw new IllegalStateException("webhook secret ciphertext is invalid");
    }
    byte[] nonce = new byte[NONCE_LENGTH];
    byte[] encrypted = new byte[ciphertext.length - NONCE_LENGTH];
    System.arraycopy(ciphertext, 0, nonce, 0, nonce.length);
    System.arraycopy(ciphertext, nonce.length, encrypted, 0, encrypted.length);
    try {
      return new String(
          cipher(Cipher.DECRYPT_MODE, nonce, keyVersion).doFinal(encrypted),
          StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("webhook secret decryption failed", exception);
    }
  }

  private Cipher cipher(int mode, byte[] nonce, int keyVersion) throws GeneralSecurityException {
    byte[] decodedKey = encryptionKey(keyVersion);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        mode, new SecretKeySpec(decodedKey, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
    return cipher;
  }

  private byte[] encryptionKey(int keyVersion) {
    String encodedKey = configuredKeys().get(keyVersion);
    if (!StringUtils.hasText(encodedKey)) {
      throw new WebhookValidationException("webhook encryption key version is not configured");
    }
    try {
      byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
      if (decodedKey.length != 32) {
        throw new WebhookValidationException(
            "webhook encryption key must be a base64-encoded 32-byte key");
      }
      return decodedKey;
    } catch (IllegalArgumentException exception) {
      throw new WebhookValidationException("webhook encryption key must be valid base64");
    }
  }

  private Map<Integer, String> configuredKeys() {
    if (!StringUtils.hasText(properties.getEncryptionKeys())) {
      return StringUtils.hasText(properties.getEncryptionKey())
          ? Map.of(properties.getEncryptionKeyVersion(), properties.getEncryptionKey())
          : Map.of();
    }
    Map<Integer, String> keys = new HashMap<>();
    for (String entry : properties.getEncryptionKeys().split(",", -1)) {
      int separator = entry.indexOf('=');
      if (separator < 1 || separator == entry.length() - 1) {
        throw new WebhookValidationException("webhook encryption key ring is invalid");
      }
      try {
        int version = Integer.parseInt(entry.substring(0, separator).trim());
        if (version <= 0
            || keys.putIfAbsent(version, entry.substring(separator + 1).trim()) != null) {
          throw new WebhookValidationException("webhook encryption key ring is invalid");
        }
      } catch (NumberFormatException exception) {
        throw new WebhookValidationException("webhook encryption key ring is invalid");
      }
    }
    return keys;
  }
}
