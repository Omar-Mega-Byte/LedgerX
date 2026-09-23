package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.access.OwnerContext;
import com.ledgerx.reliability.PaymentEventEnvelope;
import com.ledgerx.reliability.PaymentEventType;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class WebhookEventEnqueueIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private WalletAccountService walletAccountService;
  @Autowired private WebhookEndpointApplicationService endpointService;
  @Autowired private WebhookEventEnqueueService enqueueService;
  @Autowired private WebhookDeliveryStore deliveryStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private WalletRegistration merchant;
  private WebhookEndpoint endpoint;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "ledgerx.webhooks.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  @BeforeEach
  void clearAndRegisterEndpoint() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE ledgerx.reconciliation_findings, ledgerx.reconciliation_runs, "
            + "ledgerx.webhook_delivery_attempts, ledgerx.webhook_deliveries, "
            + "ledgerx.webhook_endpoint_idempotency, ledgerx.webhook_endpoints, "
            + "ledgerx.processed_events, ledgerx.outbox_events, ledgerx.refund_idempotency, "
            + "ledgerx.payment_idempotency, ledgerx.refunds, ledgerx.payments, "
            + "ledgerx.transfer_idempotency, ledgerx.transfers, ledgerx.ledger_entries, "
            + "ledgerx.ledger_transactions, ledgerx.ledger_accounts, ledgerx.wallet_owners");
    merchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    endpoint =
        endpointService
            .create(
                new OwnerContext(merchant.ownerId()),
                "https://merchant.example.com/hooks",
                Set.of("payment.completed.v1", "refund.completed.v1"),
                "merchant-signing-secret-0123456789",
                "endpoint-registration")
            .endpoint();
  }

  @Test
  void storesOneRedactedDeliveryAndReceiptAcrossDuplicateEventDelivery() throws Exception {
    UUID paymentId = UUID.randomUUID();
    PaymentEventEnvelope event = event(paymentId, 1, PaymentEventType.PAYMENT_COMPLETED);
    String internalPayload = objectMapper.writeValueAsString(event);

    enqueueService.enqueue(event, internalPayload);
    enqueueService.enqueue(event, internalPayload);

    assertThat(count("ledgerx.processed_events")).isEqualTo(1);
    assertThat(count("ledgerx.webhook_deliveries")).isEqualTo(1);
    WebhookDelivery delivery =
        deliveryStore.findAllForEndpointOwner(endpoint.id(), merchant.ownerId(), 10).getFirst();
    JsonNode publicEvent = objectMapper.readTree(delivery.payload());
    assertThat(publicEvent.path("id").asText()).isEqualTo(event.eventId().toString());
    assertThat(publicEvent.path("data").path("paymentId").asText()).isEqualTo(paymentId.toString());
    assertThat(publicEvent.path("data").path("amount").asText()).isEqualTo("25.00");
    assertThat(delivery.payload())
        .doesNotContain("payerWalletId", "merchantWalletId", "ledgerTransactionId");
    assertThat(delivery.payloadHash()).hasSize(64);

    assertThatThrownBy(() -> enqueueService.enqueue(event, internalPayload + " "))
        .hasRootCauseMessage("event id was redelivered with a different payload");
    assertThat(count("ledgerx.webhook_deliveries")).isEqualTo(1);
  }

  @Test
  void failedEnqueueRollsBackReceiptAndCanBeRetried() throws Exception {
    PaymentEventEnvelope valid = event(UUID.randomUUID(), 1, PaymentEventType.PAYMENT_COMPLETED);
    Map<String, Object> invalidData = new HashMap<>(valid.data());
    invalidData.put("merchantWalletId", UUID.randomUUID().toString());
    PaymentEventEnvelope invalid =
        new PaymentEventEnvelope(
            valid.eventId(),
            valid.eventType(),
            valid.aggregateId(),
            valid.aggregateSequence(),
            valid.schemaVersion(),
            valid.occurredAt(),
            invalidData);

    assertThatThrownBy(
            () -> enqueueService.enqueue(invalid, objectMapper.writeValueAsString(invalid)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count("ledgerx.processed_events")).isZero();
    assertThat(count("ledgerx.webhook_deliveries")).isZero();

    enqueueService.enqueue(valid, objectMapper.writeValueAsString(valid));
    assertThat(count("ledgerx.processed_events")).isEqualTo(1);
    assertThat(count("ledgerx.webhook_deliveries")).isEqualTo(1);
  }

  @Test
  void deadDeliveryBlocksLaterEventUntilOwnedReplayCompletes() throws Exception {
    UUID paymentId = UUID.randomUUID();
    PaymentEventEnvelope payment = event(paymentId, 1, PaymentEventType.PAYMENT_COMPLETED);
    PaymentEventEnvelope refund = event(paymentId, 2, PaymentEventType.REFUND_COMPLETED);
    enqueueService.enqueue(payment, objectMapper.writeValueAsString(payment));
    enqueueService.enqueue(refund, objectMapper.writeValueAsString(refund));

    Instant now = Instant.now().plusSeconds(1);
    WebhookClaim first = deliveryStore.claimNext(now, Duration.ofSeconds(30)).orElseThrow();
    assertThat(first.delivery().eventId()).isEqualTo(payment.eventId());
    assertThat(deliveryStore.claimNext(now, Duration.ofSeconds(30))).isEmpty();
    deliveryStore.markDead(first, now, now, 400, "HTTP_400");
    assertThat(deliveryStore.claimNext(now, Duration.ofSeconds(30))).isEmpty();

    endpointService.replay(
        new OwnerContext(merchant.ownerId()), endpoint.id(), first.delivery().id());
    WebhookClaim replayed =
        deliveryStore.claimNext(now.plusSeconds(1), Duration.ofSeconds(30)).orElseThrow();
    assertThat(replayed.delivery().eventId()).isEqualTo(payment.eventId());
    assertThat(replayed.delivery().replayCount()).isEqualTo(1);
    deliveryStore.markDelivered(replayed, now.plusSeconds(1), now.plusSeconds(1), 204);
    WebhookClaim second =
        deliveryStore.claimNext(now.plusSeconds(2), Duration.ofSeconds(30)).orElseThrow();
    assertThat(second.delivery().eventId()).isEqualTo(refund.eventId());
    assertThat(count("ledgerx.webhook_delivery_attempts")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT replay_count FROM ledgerx.webhook_delivery_attempts WHERE webhook_delivery_id = ? ORDER BY replay_count",
                Integer.class,
                first.delivery().id()))
        .containsExactly(0, 1);
    assertThat(count("ledgerx.ledger_transactions")).isZero();
  }

  @Test
  void databaseKeepsDeliveryPayloadAndAttemptHistoryImmutable() throws Exception {
    PaymentEventEnvelope event = event(UUID.randomUUID(), 1, PaymentEventType.PAYMENT_COMPLETED);
    enqueueService.enqueue(event, objectMapper.writeValueAsString(event));
    Instant now = Instant.now().plusSeconds(1);
    WebhookClaim claim = deliveryStore.claimNext(now, Duration.ofSeconds(30)).orElseThrow();
    deliveryStore.markDelivered(claim, now, now, 204);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.webhook_deliveries SET payload = ? WHERE id = ?",
                    "{}",
                    claim.delivery().id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.webhook_delivery_attempts SET http_status = 500 WHERE webhook_delivery_id = ?",
                    claim.delivery().id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM ledgerx.webhook_delivery_attempts WHERE webhook_delivery_id = ?",
                    claim.delivery().id()))
        .isInstanceOf(DataAccessException.class);
    assertThat(count("ledgerx.webhook_delivery_attempts")).isEqualTo(1);
  }

  @Test
  void concurrentDuplicateEventsCreateOneReceiptAndDelivery() throws Exception {
    PaymentEventEnvelope event = event(UUID.randomUUID(), 1, PaymentEventType.PAYMENT_COMPLETED);
    String payload = objectMapper.writeValueAsString(event);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> enqueueTogether(event, payload, ready, start));
      Future<?> second = executor.submit(() -> enqueueTogether(event, payload, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("ledgerx.processed_events")).isEqualTo(1);
    assertThat(count("ledgerx.webhook_deliveries")).isEqualTo(1);
  }

  private void enqueueTogether(
      PaymentEventEnvelope event, String payload, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrent enqueue test did not start");
      }
      enqueueService.enqueue(event, payload);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent enqueue was interrupted", exception);
    }
  }

  private PaymentEventEnvelope event(UUID paymentId, long sequence, PaymentEventType type) {
    Map<String, Object> data = new HashMap<>();
    data.put("paymentId", paymentId.toString());
    data.put("merchantWalletId", merchant.walletAccountId().toString());
    data.put("payerWalletId", UUID.randomUUID().toString());
    data.put("ledgerTransactionId", UUID.randomUUID().toString());
    data.put("amount", "25.00");
    data.put("currency", "USD");
    if (type == PaymentEventType.REFUND_COMPLETED) {
      data.put("refundId", UUID.randomUUID().toString());
      data.put("refundAmount", "10.00");
    }
    return new PaymentEventEnvelope(
        UUID.randomUUID(), type.wireName(), paymentId, sequence, 1, Instant.now(), data);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }
}
