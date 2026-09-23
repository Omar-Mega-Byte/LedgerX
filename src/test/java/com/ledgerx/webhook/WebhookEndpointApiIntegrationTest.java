package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WebhookEndpointApiIntegrationTest {

  private static final String SIGNING_SECRET = "merchant-signing-secret-0123456789";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private WalletOwnerRepository walletOwnerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Clock clock;

  private UUID merchantOwnerId;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "ledgerx.webhooks.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  @BeforeEach
  void clearData() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE ledgerx.reconciliation_findings, ledgerx.reconciliation_runs, "
            + "ledgerx.webhook_delivery_attempts, ledgerx.webhook_deliveries, "
            + "ledgerx.webhook_endpoint_idempotency, ledgerx.webhook_endpoints, "
            + "ledgerx.processed_events, ledgerx.outbox_events, ledgerx.refund_idempotency, "
            + "ledgerx.payment_idempotency, ledgerx.refunds, ledgerx.payments, "
            + "ledgerx.transfer_idempotency, ledgerx.transfers, ledgerx.ledger_entries, "
            + "ledgerx.ledger_transactions, ledgerx.ledger_accounts, ledgerx.wallet_owners");
    merchantOwnerId =
        walletOwnerRepository.save(WalletOwner.create(OwnerType.MERCHANT, clock)).id();
  }

  @Test
  void createsEncryptedMerchantEndpointAndReplaysTheSameConfigurationWithoutASecretResponse()
      throws Exception {
    HttpHeaders headers = ownerHeaders("webhook-registration-0001");
    Map<String, Object> request =
        Map.of(
            "url",
            "https://merchant.example.com/ledgerx/webhooks",
            "eventTypes",
            List.of("payment.completed.v1", "refund.completed.v1"),
            "signingSecret",
            SIGNING_SECRET);

    ResponseEntity<String> created =
        restTemplate.exchange(
            "/api/v1/webhook-endpoints",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            String.class);
    ResponseEntity<String> replayed =
        restTemplate.exchange(
            "/api/v1/webhook-endpoints",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            String.class);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(created.getBody()).doesNotContain(SIGNING_SECRET);
    assertThat(replayed.getBody()).doesNotContain(SIGNING_SECRET);
    JsonNode endpoint = objectMapper.readTree(created.getBody());
    assertThat(endpoint.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(endpoint.path("secretKeyVersion").asInt()).isEqualTo(1);
    byte[] ciphertext =
        jdbcTemplate.queryForObject(
            "SELECT secret_ciphertext FROM ledgerx.webhook_endpoints", byte[].class);
    assertThat(Arrays.equals(ciphertext, SIGNING_SECRET.getBytes(StandardCharsets.UTF_8)))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledgerx.webhook_endpoints", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rejectsChangedIdempotentRegistrationAndDoesNotCreateAnotherEndpoint() throws Exception {
    Map<String, Object> original = request("https://merchant.example.com/first", SIGNING_SECRET);
    HttpHeaders headers = ownerHeaders("same-registration-key");
    assertThat(create(original, headers).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> conflict =
        create(request("https://merchant.example.com/second", SIGNING_SECRET), headers);

    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(objectMapper.readTree(conflict.getBody()).path("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(count("ledgerx.webhook_endpoints")).isEqualTo(1);
    assertThat(count("ledgerx.webhook_endpoint_idempotency")).isEqualTo(1);
  }

  @Test
  void enforcesMerchantOwnershipAndRejectsInvalidRegistrationWithoutAClaim() throws Exception {
    UUID personId = walletOwnerRepository.save(WalletOwner.create(OwnerType.PERSON, clock)).id();
    UUID otherMerchantId =
        walletOwnerRepository.save(WalletOwner.create(OwnerType.MERCHANT, clock)).id();
    Map<String, Object> valid = request("https://merchant.example.com/hooks", SIGNING_SECRET);

    assertThat(create(valid, headersFor(personId, "person-key")).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    ResponseEntity<String> created = create(valid, ownerHeaders("merchant-key"));
    UUID endpointId =
        UUID.fromString(objectMapper.readTree(created.getBody()).path("endpointId").asText());
    ResponseEntity<String> otherOwnerRead =
        restTemplate.exchange(
            "/api/v1/webhook-endpoints/" + endpointId,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(otherMerchantId, null)),
            String.class);
    assertThat(otherOwnerRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<String> invalidUrl =
        create(request("http://127.0.0.1/hooks", SIGNING_SECRET), ownerHeaders("invalid-url"));
    assertThat(invalidUrl.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    ResponseEntity<String> invalidSecret =
        create(
            request("https://merchant.example.com/other", "short"), ownerHeaders("invalid-secret"));
    assertThat(invalidSecret.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(count("ledgerx.webhook_endpoints")).isEqualTo(1);
    assertThat(count("ledgerx.webhook_endpoint_idempotency")).isEqualTo(1);
  }

  @Test
  void rotationAndDisablementRemainPrivateAndRedactSecrets() throws Exception {
    ResponseEntity<String> created =
        create(
            request("https://merchant.example.com/hooks", SIGNING_SECRET),
            ownerHeaders("register"));
    UUID endpointId =
        UUID.fromString(objectMapper.readTree(created.getBody()).path("endpointId").asText());
    String replacement = "replacement-signing-secret-0123456789";

    ResponseEntity<String> rotated =
        restTemplate.exchange(
            "/api/v1/webhook-endpoints/" + endpointId + "/rotate-secret",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("signingSecret", replacement), ownerHeaders(null)),
            String.class);
    assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(rotated.getBody()).doesNotContain(replacement, SIGNING_SECRET);

    ResponseEntity<String> disabled =
        restTemplate.exchange(
            "/api/v1/webhook-endpoints/" + endpointId + "/disable",
            HttpMethod.POST,
            new HttpEntity<>(ownerHeaders(null)),
            String.class);
    assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(objectMapper.readTree(disabled.getBody()).path("status").asText())
        .isEqualTo("DISABLED");
    assertThat(
            restTemplate
                .exchange(
                    "/api/v1/webhook-endpoints/" + endpointId + "/rotate-secret",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("signingSecret", SIGNING_SECRET), ownerHeaders(null)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  private ResponseEntity<String> create(Map<String, Object> request, HttpHeaders headers) {
    return restTemplate.exchange(
        "/api/v1/webhook-endpoints",
        HttpMethod.POST,
        new HttpEntity<>(request, headers),
        String.class);
  }

  private Map<String, Object> request(String url, String signingSecret) {
    return Map.of(
        "url", url, "eventTypes", List.of("payment.completed.v1"), "signingSecret", signingSecret);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private HttpHeaders ownerHeaders(String idempotencyKey) {
    return headersFor(merchantOwnerId, idempotencyKey);
  }

  private HttpHeaders headersFor(UUID ownerId, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-LedgerX-Owner-Id", ownerId.toString());
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return headers;
  }
}
