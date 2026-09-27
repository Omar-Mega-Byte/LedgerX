package com.ledgerx.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PaymentRiskIntegrationTest {

  private static final MutableClock TEST_CLOCK = new MutableClock();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private WalletAccountService wallets;
  @Autowired private LedgerPostingService ledger;
  @Autowired private PaymentApplicationService payments;
  @Autowired private PaymentRiskService risk;

  @DynamicPropertySource
  static void dataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeEach
  void reset() {
    TEST_CLOCK.set(Instant.now());
    jdbc.execute(
        "TRUNCATE TABLE ledgerx.risk_review_actions, ledgerx.risk_review_cases, "
            + "ledgerx.risk_assessments, ledgerx.demo_fundings, "
            + "ledgerx.reconciliation_findings, ledgerx.reconciliation_runs, "
            + "ledgerx.webhook_delivery_attempts, ledgerx.webhook_deliveries, "
            + "ledgerx.webhook_endpoint_idempotency, ledgerx.webhook_endpoints, "
            + "ledgerx.processed_events, ledgerx.outbox_events, ledgerx.refund_idempotency, "
            + "ledgerx.payment_idempotency, ledgerx.refunds, ledgerx.payments, "
            + "ledgerx.transfer_idempotency, ledgerx.transfers, ledgerx.ledger_entries, "
            + "ledgerx.ledger_transactions, ledgerx.ledger_accounts, ledgerx.wallet_owners");
    jdbc.update(
        "UPDATE ledgerx.risk_policy_activation SET policy_id = ? WHERE singleton_id = 1",
        UUID.fromString("00000000-0000-4000-8000-000000000001"));
  }

  @Test
  void allowedPaymentCommitsAssessmentWithLedgerAndOutbox() throws Exception {
    activate("100.00", 10, "1000.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);

    MvcResult created =
        postPayment(payer, merchant, "allow-key", "20.00")
            .andExpect(status().isCreated())
            .andReturn();
    UUID paymentId = UUID.fromString(body(created).get("paymentId").asText());
    assertThat(
            count(
                "SELECT COUNT(*) FROM ledgerx.risk_assessments WHERE payment_id = ? AND outcome = 'ALLOW'",
                paymentId))
        .isEqualTo(1);
    assertThat(
            count("SELECT COUNT(*) FROM ledgerx.outbox_events WHERE aggregate_id = ?", paymentId))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.ledger_transactions")).isEqualTo(2);
  }

  @Test
  void reviewApprovalRequiresOriginalPayerRetryAndCompletesOnce() throws Exception {
    activate("100.00", 10, "10.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);

    MvcResult first =
        postPayment(payer, merchant, "review-key", "20.00")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.outcome").value("REVIEW"))
            .andExpect(jsonPath("$.caseStatus").value("OPEN"))
            .andReturn();
    UUID caseId = UUID.fromString(body(first).get("caseId").asText());
    postPayment(payer, merchant, "review-key", "20.00")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.caseId").value(caseId.toString()));
    assertNoPaymentEffect();

    WalletRegistration unrelated = wallets.createWallet(OwnerType.PERSON);
    mockMvc
        .perform(
            get("/api/v1/payment-risk-cases/{caseId}", caseId)
                .header("X-LedgerX-Owner-Id", unrelated.ownerId()))
        .andExpect(status().isNotFound());
    risk.decide(caseId, "APPROVE", "reviewed transaction", "operator-subject");
    assertNoPaymentEffect();

    MvcResult completed =
        postPayment(payer, merchant, "review-key", "20.00")
            .andExpect(status().isCreated())
            .andReturn();
    postPayment(payer, merchant, "review-key", "20.00")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(body(completed).get("paymentId").asText()));
    assertThat(count("SELECT COUNT(*) FROM ledgerx.payments")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.outbox_events")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.risk_assessments")).isEqualTo(2);
    assertThat(risk.caseForOwner(caseId, payer.ownerId()).status()).isEqualTo("CONSUMED");
  }

  @Test
  void blockedPaymentReplaysWithoutFinancialEffects() throws Exception {
    activate("10.00", 10, "1000.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);

    MvcResult first =
        postPayment(payer, merchant, "block-key", "20.00")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.outcome").value("BLOCK"))
            .andReturn();
    postPayment(payer, merchant, "block-key", "20.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.decisionId").value(body(first).get("decisionId").asText()));
    assertNoPaymentEffect();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.risk_assessments WHERE outcome = 'BLOCK'"))
        .isEqualTo(1);
  }

  @Test
  void ledgerFailureRollsBackRiskAndIdempotencyEvidence() throws Exception {
    activate("1000.00", 10, "1000.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);

    postPayment(payer, merchant, "insufficient-risk-key", "200.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PROCESSABLE"));

    assertNoPaymentEffect();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.risk_assessments")).isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.payment_idempotency")).isZero();
  }

  @Test
  void reviewKeyCannotBeReusedForDifferentPayment() throws Exception {
    activate("100.00", 10, "10.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);
    postPayment(payer, merchant, "same-key", "20.00").andExpect(status().isAccepted());
    postPayment(payer, merchant, "same-key", "21.00")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertNoPaymentEffect();
  }

  @Test
  void declinedReviewIsTerminalAndItsActionIsAudited() throws Exception {
    activate("100.00", 10, "10.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);
    MvcResult pending =
        postPayment(payer, merchant, "declined-key", "20.00")
            .andExpect(status().isAccepted())
            .andReturn();
    UUID caseId = UUID.fromString(body(pending).get("caseId").asText());

    risk.decide(caseId, "DECLINE", "failed manual review", "operator-subject");
    risk.decide(caseId, "DECLINE", "failed manual review", "operator-subject");
    assertThatThrownBy(() -> risk.decide(caseId, "APPROVE", "changed mind", "operator-subject"))
        .isInstanceOf(RiskConflictException.class);
    postPayment(payer, merchant, "declined-key", "20.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RISK_REVIEW_CLOSED"));
    assertThat(count("SELECT COUNT(*) FROM ledgerx.risk_review_actions WHERE case_id = ?", caseId))
        .isEqualTo(1);
    assertNoPaymentEffect();
  }

  @Test
  void openReviewExpiresWithoutPosting() throws Exception {
    activate("100.00", 10, "10.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);
    MvcResult pending =
        postPayment(payer, merchant, "open-expiry-key", "20.00")
            .andExpect(status().isAccepted())
            .andReturn();
    UUID caseId = UUID.fromString(body(pending).get("caseId").asText());

    TEST_CLOCK.set(TEST_CLOCK.instant().plusSeconds(8 * 24 * 60 * 60));
    risk.expireCases();

    assertThat(risk.caseForOwner(caseId, payer.ownerId()).status()).isEqualTo("EXPIRED");
    postPayment(payer, merchant, "open-expiry-key", "20.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RISK_REVIEW_CLOSED"));
    assertNoPaymentEffect();
  }

  @Test
  void approvalExpiresWithoutPosting() throws Exception {
    activate("100.00", 10, "10.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);
    MvcResult pending =
        postPayment(payer, merchant, "approval-expiry-key", "20.00")
            .andExpect(status().isAccepted())
            .andReturn();
    UUID caseId = UUID.fromString(body(pending).get("caseId").asText());
    risk.decide(caseId, "APPROVE", "approve for expiry test", "operator-subject");

    TEST_CLOCK.set(TEST_CLOCK.instant().plusSeconds(25 * 60 * 60));
    risk.expireCases();

    assertThat(risk.caseForOwner(caseId, payer.ownerId()).status()).isEqualTo("EXPIRED");
    postPayment(payer, merchant, "approval-expiry-key", "20.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RISK_REVIEW_CLOSED"));
    assertNoPaymentEffect();
  }

  @Test
  void policyActivationReplaysAndRejectsStaleOrChangedCommands() {
    long expected = risk.activePolicy().versionNumber();
    String key = UUID.randomUUID().toString();
    PaymentRiskService.Policy created =
        risk.activate(
            true,
            new BigDecimal("100.00"),
            5,
            new BigDecimal("500.00"),
            "operator-subject",
            "enable bounded checks",
            key,
            expected);
    PaymentRiskService.Policy replay =
        risk.activate(
            true,
            new BigDecimal("100.00"),
            5,
            new BigDecimal("500.00"),
            "operator-subject",
            "enable bounded checks",
            key,
            expected);
    assertThat(replay.id()).isEqualTo(created.id());
    assertThatThrownBy(
            () ->
                risk.activate(
                    true,
                    new BigDecimal("99.00"),
                    5,
                    new BigDecimal("500.00"),
                    "operator-subject",
                    "enable bounded checks",
                    key,
                    expected))
        .isInstanceOf(RiskConflictException.class);
    assertThatThrownBy(
            () ->
                risk.activate(
                    true,
                    new BigDecimal("100.00"),
                    5,
                    new BigDecimal("500.00"),
                    "operator-subject",
                    "stale activation",
                    UUID.randomUUID().toString(),
                    expected))
        .isInstanceOf(RiskConflictException.class);
    assertThat(
            count("SELECT COUNT(*) FROM ledgerx.risk_policy_versions WHERE command_key = ?", key))
        .isEqualTo(1);
  }

  @Test
  void differentKeysForSamePayerCannotBothPassVelocityThreshold() throws Exception {
    activate("100.00", 1, "1000.00");
    WalletRegistration payer = fundedPayer();
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<PaymentExecution> first =
          executor.submit(() -> attempt(payer, merchant, "velocity-1", ready, start));
      Future<PaymentExecution> second =
          executor.submit(() -> attempt(payer, merchant, "velocity-2", ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<PaymentExecution> outcomes =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(outcomes.stream().filter(item -> item.payment() != null).count()).isEqualTo(1);
      assertThat(outcomes.stream().filter(item -> item.risk() != null).count()).isEqualTo(1);
      assertThat(count("SELECT COUNT(*) FROM ledgerx.payments")).isEqualTo(1);
      assertThat(count("SELECT COUNT(*) FROM ledgerx.risk_review_cases")).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private PaymentExecution attempt(
      WalletRegistration payer,
      WalletRegistration merchant,
      String key,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    return payments.create(
        new OwnerContext(payer.ownerId()),
        new PaymentCommand(
            payer.walletAccountId(), merchant.walletAccountId(), money("10.00"), key));
  }

  private void activate(String maximum, int count, String total) {
    PaymentRiskService.Policy current = risk.activePolicy();
    risk.activate(
        true,
        new BigDecimal(maximum),
        count,
        new BigDecimal(total),
        "operator-subject",
        "integration test policy",
        UUID.randomUUID().toString(),
        current.versionNumber());
  }

  private WalletRegistration fundedPayer() {
    WalletRegistration payer = wallets.createWallet(OwnerType.PERSON);
    UUID clearing =
        wallets.createSystemAccount(AccountType.ASSET, "RISK-CLEARING-" + UUID.randomUUID());
    ledger.post(
        "Risk test funding",
        List.of(
            new PostingLine(clearing, EntrySide.DEBIT, money("100.00")),
            new PostingLine(payer.walletAccountId(), EntrySide.CREDIT, money("100.00"))));
    return payer;
  }

  private Money money(String amount) {
    return new Money(new BigDecimal(amount), CurrencyCode.USD);
  }

  private org.springframework.test.web.servlet.ResultActions postPayment(
      WalletRegistration payer, WalletRegistration merchant, String key, String amount)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/payments")
            .header("X-LedgerX-Owner-Id", payer.ownerId())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"payerWalletId":"%s","merchantWalletId":"%s","money":{"amount":"%s","currency":"USD"}}
                """
                    .formatted(payer.walletAccountId(), merchant.walletAccountId(), amount)));
  }

  private void assertNoPaymentEffect() {
    assertThat(count("SELECT COUNT(*) FROM ledgerx.payments")).isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.outbox_events")).isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.ledger_transactions")).isEqualTo(1);
  }

  private int count(String sql, Object... arguments) {
    Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
    return count == null ? 0 : count;
  }

  private JsonNode body(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  @TestConfiguration
  static class RiskClockConfiguration {
    @Bean
    @Primary
    Clock riskTestClock() {
      return TEST_CLOCK;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now());

    void set(Instant instant) {
      current.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
