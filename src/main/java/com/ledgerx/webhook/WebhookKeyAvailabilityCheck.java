package com.ledgerx.webhook;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start delivery if a stored endpoint cannot be decrypted by the configured key ring.
 */
@Component
@ConditionalOnProperty(
    prefix = "ledgerx.webhooks",
    name = "dispatcher-enabled",
    havingValue = "true")
public class WebhookKeyAvailabilityCheck implements SmartInitializingSingleton {

  private final JdbcTemplate jdbc;
  private final WebhookSecretCipher cipher;

  public WebhookKeyAvailabilityCheck(JdbcTemplate jdbc, WebhookSecretCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  @Override
  public void afterSingletonsInstantiated() {
    verifyStoredKeyVersions();
  }

  public void verifyStoredKeyVersions() {
    jdbc.query(
        "SELECT secret_ciphertext, secret_key_version FROM ledgerx.webhook_endpoints",
        resultSet -> {
          int version = resultSet.getInt("secret_key_version");
          cipher.requireKeyVersion(version);
          cipher.decrypt(resultSet.getBytes("secret_ciphertext"), version);
        });
  }
}
