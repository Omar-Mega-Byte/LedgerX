package com.ledgerx.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.AccountType;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.payment.application.PaymentApplicationService;
import com.ledgerx.payment.application.PaymentExecution;
import com.ledgerx.payment.domain.PaymentCommand;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.webhook.WebhookEndpointApplicationService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "ledgerx.outbox.publisher-enabled=true",
      "ledgerx.kafka.consumer-enabled=true",
      "ledgerx.webhooks.consumer-enabled=true",
      "ledgerx.webhooks.dispatcher-enabled=false",
      "ledgerx.outbox.poll-delay=PT1H"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaOutboxIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

  @Autowired private WalletAccountService walletAccountService;
  @Autowired private LedgerPostingService ledgerPostingService;
  @Autowired private PaymentApplicationService paymentApplicationService;
  @Autowired private WebhookEndpointApplicationService webhookEndpointService;
  @Autowired private OutboxPublisher outboxPublisher;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void configureInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add(
        "ledgerx.webhooks.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  @Test
  void publishesCommittedPaymentEventsAndDeduplicatesKafkaRedelivery() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    webhookEndpointService.create(
        new OwnerContext(merchant.ownerId()),
        "https://merchant.example.com/hooks",
        Set.of("payment.completed.v1"),
        "merchant-signing-secret-0123456789",
        "kafka-webhook-registration");
    PaymentExecution payment =
        paymentApplicationService.create(
            new OwnerContext(payer.ownerId()),
            new PaymentCommand(
                payer.walletId(), merchant.walletAccountId(), money("25.00"), "kafka-payment"));

    outboxPublisher.publishAvailable();
    awaitProcessedEvent(payment.payment().id(), 2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM ledgerx.outbox_events WHERE aggregate_id = ?",
                String.class,
                payment.payment().id()))
        .isEqualTo("PUBLISHED");

    String payload =
        jdbcTemplate.queryForObject(
            "SELECT payload::text FROM ledgerx.outbox_events WHERE aggregate_id = ?",
            String.class,
            payment.payment().id());
    RecordMetadata duplicateMetadata =
        kafkaTemplate
            .send("ledgerx.payment-events.v1", payment.payment().id().toString(), payload)
            .get(5, TimeUnit.SECONDS)
            .getRecordMetadata();
    awaitConsumerOffsetPast(duplicateMetadata, "ledgerx-payment-event-audit-v1");
    awaitConsumerOffsetPast(duplicateMetadata, "ledgerx-webhook-delivery-enqueuer-v1");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledgerx.processed_events WHERE aggregate_id = ?",
                Integer.class,
                payment.payment().id()))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledgerx.webhook_deliveries WHERE aggregate_id = ?",
                Integer.class,
                payment.payment().id()))
        .isEqualTo(1);
    String publicPayload =
        jdbcTemplate.queryForObject(
            "SELECT payload FROM ledgerx.webhook_deliveries WHERE aggregate_id = ?",
            String.class,
            payment.payment().id());
    assertThat(publicPayload)
        .contains(payment.payment().id().toString())
        .doesNotContain("payerWalletId", "merchantWalletId", "ledgerTransactionId");
  }

  private void awaitProcessedEvent(UUID paymentId, int expectedCount) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM ledgerx.processed_events WHERE aggregate_id = ?",
                            Integer.class,
                            paymentId))
                    .isEqualTo(expectedCount));
  }

  private void awaitConsumerOffsetPast(RecordMetadata metadata, String groupId) throws Exception {
    TopicPartition partition = new TopicPartition(metadata.topic(), metadata.partition());
    try (AdminClient adminClient =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                OffsetAndMetadata committedOffset =
                    adminClient
                        .listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS)
                        .get(partition);
                assertThat(committedOffset).isNotNull();
                assertThat(committedOffset.offset()).isGreaterThan(metadata.offset());
              });
    }
  }

  private FundedWallet fundedWallet(OwnerType ownerType, String amount) {
    WalletRegistration wallet = walletAccountService.createWallet(ownerType);
    UUID clearingAccountId =
        walletAccountService.createSystemAccount(
            AccountType.ASSET, "CLEARING-" + UUID.randomUUID());
    ledgerPostingService.post(
        "Initial funding",
        List.of(
            new PostingLine(clearingAccountId, EntrySide.DEBIT, money(amount)),
            new PostingLine(wallet.walletAccountId(), EntrySide.CREDIT, money(amount))));
    return new FundedWallet(wallet.ownerId(), wallet.walletAccountId());
  }

  private Money money(String amount) {
    return new Money(new BigDecimal(amount), CurrencyCode.USD);
  }

  private record FundedWallet(UUID ownerId, UUID walletId) {}
}
