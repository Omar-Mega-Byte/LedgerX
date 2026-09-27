package com.ledgerx.webhook;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Re-encrypts endpoint signing secrets without changing the merchant-visible HMAC secret. */
@Service
public class WebhookEncryptionRotationService {

  private final JdbcTemplate jdbc;
  private final WebhookSecretCipher cipher;
  private final WebhookProperties properties;

  public WebhookEncryptionRotationService(
      JdbcTemplate jdbc, WebhookSecretCipher cipher, WebhookProperties properties) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.properties = properties;
  }

  @Transactional
  public int reencryptBatch(int limit, String operatorSubject) {
    if (limit < 1 || limit > 500) {
      throw new WebhookValidationException("rotation batch size must be between 1 and 500");
    }
    if (!StringUtils.hasText(operatorSubject) || operatorSubject.length() > 255) {
      throw new WebhookValidationException("operator subject is required");
    }
    int activeVersion = properties.getEncryptionKeyVersion();
    List<EncryptedEndpoint> endpoints =
        jdbc.query(
            """
            SELECT id, secret_ciphertext, secret_key_version
            FROM ledgerx.webhook_endpoints
            WHERE secret_key_version <> ?
            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            (resultSet, rowNumber) ->
                new EncryptedEndpoint(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getBytes("secret_ciphertext"),
                    resultSet.getInt("secret_key_version")),
            activeVersion,
            limit);
    for (EncryptedEndpoint endpoint : endpoints) {
      String signingSecret = cipher.decrypt(endpoint.ciphertext(), endpoint.keyVersion());
      int updated =
          jdbc.update(
              """
              UPDATE ledgerx.webhook_endpoints
              SET secret_ciphertext = ?, secret_key_version = ?, updated_at = CURRENT_TIMESTAMP
              WHERE id = ? AND secret_key_version = ?
              """,
              cipher.encrypt(signingSecret),
              activeVersion,
              endpoint.id(),
              endpoint.keyVersion());
      if (updated != 1) {
        throw new IllegalStateException("webhook key rotation lost endpoint ownership");
      }
      jdbc.update(
          """
          INSERT INTO ledgerx.webhook_secret_reencryptions
            (id, webhook_endpoint_id, old_key_version, new_key_version, operator_subject, rotated_at)
          VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
          """,
          UUID.randomUUID(),
          endpoint.id(),
          endpoint.keyVersion(),
          activeVersion,
          operatorSubject);
    }
    return endpoints.size();
  }

  private record EncryptedEndpoint(UUID id, byte[] ciphertext, int keyVersion) {}
}
