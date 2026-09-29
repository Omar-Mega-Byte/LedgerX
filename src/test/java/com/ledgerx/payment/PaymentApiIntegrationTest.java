package com.ledgerx.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.application.LedgerBalanceQueryService;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.AccountType;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.payment.application.PaymentApplicationService;
import com.ledgerx.payment.application.PaymentExecution;
import com.ledgerx.payment.application.RefundApplicationService;
import com.ledgerx.payment.domain.PaymentCommand;
import com.ledgerx.payment.domain.PaymentValidationException;
import com.ledgerx.payment.domain.RefundCommand;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentApiIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private WalletAccountService walletAccountService;
  @Autowired private LedgerPostingService ledgerPostingService;
  @Autowired private LedgerBalanceQueryService ledgerBalanceQueryService;
  @Autowired private PaymentApplicationService paymentApplicationService;
  @Autowired private RefundApplicationService refundApplicationService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeEach
  void clearFinancialData() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE ledgerx.webhook_secret_reencryptions, ledgerx.outbox_replay_requests, ledgerx.risk_review_actions, ledgerx.risk_review_cases, ledgerx.risk_assessments, ledgerx.demo_fundings, ledgerx.reconciliation_findings, ledgerx.reconciliation_runs, "
            + "ledgerx.webhook_delivery_attempts, ledgerx.webhook_deliveries, "
            + "ledgerx.webhook_endpoint_idempotency, ledgerx.webhook_endpoints, "
            + "ledgerx.processed_events, ledgerx.outbox_events, ledgerx.refund_idempotency, "
            + "ledgerx.payment_idempotency, ledgerx.refunds, ledgerx.payments, "
            + "ledgerx.transfer_idempotency, ledgerx.transfers, ledgerx.ledger_entries, "
            + "ledgerx.ledger_transactions, ledgerx.ledger_accounts, ledgerx.wallet_owners");
  }

  @Test
  void createsAnAuditablePaymentWithAnAtomicOutboxEvent() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);

    MvcResult result =
        postPayment(
                payer.ownerId(),
                "payment-create",
                payer.walletId(),
                merchant.walletAccountId(),
                "25.00")
            .andExpect(status().isCreated())
            .andExpect(
                header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/payments/")))
            .andExpect(jsonPath("$.payerWalletId").value(payer.walletId().toString()))
            .andExpect(jsonPath("$.merchantWalletId").value(merchant.walletAccountId().toString()))
            .andExpect(jsonPath("$.money.amount").value("25.00"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.refundedMoney.amount").value("0.00"))
            .andReturn();

    UUID paymentId = UUID.fromString(body(result).get("paymentId").asText());
    assertThat(ledgerBalanceQueryService.balanceOf(payer.walletId()).amount())
        .isEqualByComparingTo("75.00");
    assertThat(ledgerBalanceQueryService.balanceOf(merchant.walletAccountId()).amount())
        .isEqualByComparingTo("25.00");
    assertThat(count("SELECT COUNT(*) FROM ledgerx.payments WHERE id = ?", paymentId)).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM ledgerx.payment_idempotency WHERE payment_id = ?", paymentId))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM ledgerx.outbox_events WHERE aggregate_id = ? AND event_type = 'payment.completed.v1'",
                paymentId))
        .isEqualTo(1);
  }

  @Test
  void replaysAnIdenticalPaymentWithoutAnotherLedgerOrOutboxEffect() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);

    MvcResult first =
        postPayment(
                payer.ownerId(),
                "payment-replay",
                payer.walletId(),
                merchant.walletAccountId(),
                "10")
            .andExpect(status().isCreated())
            .andReturn();
    MvcResult replay =
        postPayment(
                payer.ownerId(),
                "payment-replay",
                payer.walletId(),
                merchant.walletAccountId(),
                "10.00")
            .andExpect(status().isOk())
            .andReturn();

    assertThat(body(replay).get("paymentId").asText())
        .isEqualTo(body(first).get("paymentId").asText());
    assertThat(count("SELECT COUNT(*) FROM ledgerx.payments")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.outbox_events")).isEqualTo(1);
    assertThat(ledgerBalanceQueryService.balanceOf(payer.walletId()).amount())
        .isEqualByComparingTo("90.00");
  }

  @Test
  void createsBoundedPartialAndFullCompensatingRefunds() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    UUID paymentId =
        paymentId(
            postPayment(
                    payer.ownerId(),
                    "payment-refunds",
                    payer.walletId(),
                    merchant.walletAccountId(),
                    "50.00")
                .andExpect(status().isCreated())
                .andReturn());

    MvcResult firstRefund =
        postRefund(merchant.ownerId(), "refund-one", paymentId, "20.00")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.money.amount").value("20.00"))
            .andReturn();
    UUID refundId = UUID.fromString(body(firstRefund).get("refundId").asText());
    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}/refunds", paymentId)
                .header("X-LedgerX-Owner-Id", payer.ownerId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].refundId").value(refundId.toString()));
    mockMvc
        .perform(
            get("/api/v1/refunds/{refundId}", refundId)
                .header("X-LedgerX-Owner-Id", merchant.ownerId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()));
    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", paymentId)
                .header("X-LedgerX-Owner-Id", payer.ownerId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
        .andExpect(jsonPath("$.remainingRefundableMoney.amount").value("30.00"));

    postRefund(merchant.ownerId(), "refund-two", paymentId, "30.00")
        .andExpect(status().isCreated());
    postRefund(merchant.ownerId(), "refund-too-much", paymentId, "0.01")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("REFUND_NOT_PROCESSABLE"));

    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", paymentId)
                .header("X-LedgerX-Owner-Id", merchant.ownerId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REFUNDED"))
        .andExpect(jsonPath("$.refundedMoney.amount").value("50.00"));
    assertThat(ledgerBalanceQueryService.balanceOf(payer.walletId()).amount())
        .isEqualByComparingTo("100.00");
    assertThat(ledgerBalanceQueryService.balanceOf(merchant.walletAccountId()).amount()).isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.refunds WHERE payment_id = ?", paymentId))
        .isEqualTo(2);
    assertThat(
            count("SELECT COUNT(*) FROM ledgerx.outbox_events WHERE aggregate_id = ?", paymentId))
        .isEqualTo(3);
  }

  @Test
  void rollsBackValidationFailureIncludingIdempotencyAndOutboxState() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "5.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);

    postPayment(
            payer.ownerId(),
            "insufficient-payment",
            payer.walletId(),
            merchant.walletAccountId(),
            "10.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PROCESSABLE"));

    assertThat(count("SELECT COUNT(*) FROM ledgerx.payments")).isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.payment_idempotency")).isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.outbox_events")).isZero();
    assertThat(ledgerBalanceQueryService.balanceOf(payer.walletId()).amount())
        .isEqualByComparingTo("5.00");
  }

  @Test
  void enforcesPaymentAndRefundOwnershipAndParticipantPrivacy() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    WalletRegistration unrelated = walletAccountService.createWallet(OwnerType.PERSON);

    postPayment(
            unrelated.ownerId(),
            "wrong-payer",
            payer.walletId(),
            merchant.walletAccountId(),
            "10.00")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_AUTHORIZED"));
    UUID paymentId =
        paymentId(
            postPayment(
                    payer.ownerId(),
                    "private-payment",
                    payer.walletId(),
                    merchant.walletAccountId(),
                    "10.00")
                .andExpect(status().isCreated())
                .andReturn());

    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", paymentId)
                .header("X-LedgerX-Owner-Id", unrelated.ownerId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    postRefund(payer.ownerId(), "payer-refund", paymentId, "1.00")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REFUND_NOT_AUTHORIZED"));
    postRefund(unrelated.ownerId(), "hidden-refund", paymentId, "1.00")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
  }

  @Test
  void concurrentRefundsCannotExceedTheOriginalPayment() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    PaymentExecution payment =
        paymentApplicationService.create(
            new OwnerContext(payer.ownerId()),
            new PaymentCommand(
                payer.walletId(),
                merchant.walletAccountId(),
                money("50.00"),
                "concurrent-payment"));

    List<Boolean> outcomes =
        runTogether(
            () ->
                attemptRefund(
                    merchant.ownerId(), payment.payment().id(), "30.00", "concurrent-refund-one"),
            () ->
                attemptRefund(
                    merchant.ownerId(), payment.payment().id(), "30.00", "concurrent-refund-two"));

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledgerx.refunds WHERE payment_id = ?",
                BigDecimal.class,
                payment.payment().id()))
        .isEqualByComparingTo("30.00");
    assertThat(ledgerBalanceQueryService.balanceOf(merchant.walletAccountId()).amount())
        .isEqualByComparingTo("20.00");
  }

  @Test
  void databasePreventsMutationOfPaymentRefundAndOutboxFacts() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    UUID paymentId =
        paymentId(
            postPayment(
                    payer.ownerId(),
                    "immutable-payment",
                    payer.walletId(),
                    merchant.walletAccountId(),
                    "10.00")
                .andExpect(status().isCreated())
                .andReturn());
    MvcResult refund =
        postRefund(merchant.ownerId(), "immutable-refund", paymentId, "1.00")
            .andExpect(status().isCreated())
            .andReturn();
    UUID refundId = UUID.fromString(body(refund).get("refundId").asText());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.payments SET amount = ? WHERE id = ?",
                    new BigDecimal("11.00"),
                    paymentId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.refunds SET amount = ? WHERE id = ?",
                    new BigDecimal("2.00"),
                    refundId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM ledgerx.outbox_events WHERE aggregate_id = ?", paymentId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void databaseRejectsRefundsThatExceedOrChangeTheOriginalPayment() throws Exception {
    FundedWallet payer = fundedWallet(OwnerType.PERSON, "20.00");
    WalletRegistration merchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    WalletRegistration otherMerchant = walletAccountService.createWallet(OwnerType.MERCHANT);
    MvcResult paymentResponse =
        postPayment(
                payer.ownerId(),
                "database-refund-guard",
                payer.walletId(),
                merchant.walletAccountId(),
                "10.00")
            .andExpect(status().isCreated())
            .andReturn();
    UUID paymentId = paymentId(paymentResponse);
    UUID existingJournalId =
        UUID.fromString(body(paymentResponse).get("ledgerTransactionId").asText());

    assertThatThrownBy(
            () ->
                insertRefundDirectly(
                    paymentId,
                    merchant.walletAccountId(),
                    payer.walletId(),
                    existingJournalId,
                    "10.01"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("refund total exceeds payment amount");
    assertThatThrownBy(
            () ->
                insertRefundDirectly(
                    paymentId,
                    otherMerchant.walletAccountId(),
                    payer.walletId(),
                    existingJournalId,
                    "1.00"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("refund participants differ from payment");
    assertThatThrownBy(
            () ->
                insertRefundDirectly(
                    paymentId,
                    merchant.walletAccountId(),
                    payer.walletId(),
                    existingJournalId,
                    "1.00"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("refund cannot reuse payment journal");
    assertThat(count("SELECT COUNT(*) FROM ledgerx.refunds WHERE payment_id = ?", paymentId))
        .isZero();
  }

  private void insertRefundDirectly(
      UUID paymentId, UUID merchantWalletId, UUID payerWalletId, UUID journalId, String amount) {
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.refunds
            (id, payment_id, merchant_wallet_account_id, payer_wallet_account_id,
             amount, currency, ledger_transaction_id, completed_at)
        VALUES (?, ?, ?, ?, ?, 'USD', ?, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        paymentId,
        merchantWalletId,
        payerWalletId,
        new BigDecimal(amount),
        journalId);
  }

  @Test
  void publishesPaymentEndpointsAndEditableExamplesInOpenApi() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

    JsonNode document = body(result);
    JsonNode createPayment = document.at("/paths/~1api~1v1~1payments/post");
    assertThat(createPayment.at("/operationId").asText()).isEqualTo("createPayment");
    assertThat(createPayment.at("/parameters").toString())
        .contains("X-LedgerX-Owner-Id", "Idempotency-Key", "payment-demo-0001");
    assertThat(
            createPayment
                .at(
                    "/requestBody/content/application~1json/examples/payer-to-merchant-payment/value/money/amount")
                .asText())
        .isEqualTo("25.00");
    assertThat(
            document
                .at("/paths/~1api~1v1~1payments~1{paymentId}~1refunds/post/operationId")
                .asText())
        .isEqualTo("createRefund");
  }

  private org.springframework.test.web.servlet.ResultActions postPayment(
      UUID ownerId, String idempotencyKey, UUID payerWalletId, UUID merchantWalletId, String amount)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/payments")
            .header("X-LedgerX-Owner-Id", ownerId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(paymentRequest(payerWalletId, merchantWalletId, amount)));
  }

  private org.springframework.test.web.servlet.ResultActions postRefund(
      UUID ownerId, String idempotencyKey, UUID paymentId, String amount) throws Exception {
    return mockMvc.perform(
        post("/api/v1/payments/{paymentId}/refunds", paymentId)
            .header("X-LedgerX-Owner-Id", ownerId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(refundRequest(amount)));
  }

  private String paymentRequest(UUID payerWalletId, UUID merchantWalletId, String amount) {
    return """
        {"payerWalletId":"%s","merchantWalletId":"%s","money":{"amount":"%s","currency":"USD"}}
        """
        .formatted(payerWalletId, merchantWalletId, amount);
  }

  private String refundRequest(String amount) {
    return """
        {"money":{"amount":"%s","currency":"USD"}}
        """
        .formatted(amount);
  }

  private UUID paymentId(MvcResult result) throws Exception {
    return UUID.fromString(body(result).get("paymentId").asText());
  }

  private JsonNode body(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
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

  private boolean attemptRefund(
      UUID merchantOwnerId, UUID paymentId, String amount, String idempotencyKey) {
    try {
      refundApplicationService.create(
          new OwnerContext(merchantOwnerId),
          new RefundCommand(paymentId, money(amount), idempotencyKey));
      return true;
    } catch (PaymentValidationException exception) {
      return false;
    }
  }

  @SafeVarargs
  private final <T> List<T> runTogether(Callable<T>... work) throws Exception {
    CountDownLatch ready = new CountDownLatch(work.length);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(work.length);
    try {
      List<Future<T>> futures =
          java.util.Arrays.stream(work)
              .map(
                  task ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            if (!start.await(5, TimeUnit.SECONDS)) {
                              throw new IllegalStateException("concurrent test did not start");
                            }
                            return task.call();
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return futures.stream().map(this::await).toList();
    } finally {
      executor.shutdownNow();
    }
  }

  private <T> T await(Future<T> future) {
    try {
      return future.get(15, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new IllegalStateException("concurrent refund did not complete", exception);
    }
  }

  private int count(String query, Object... parameters) {
    return jdbcTemplate.queryForObject(query, Integer.class, parameters);
  }

  private record FundedWallet(UUID ownerId, UUID walletId) {}
}
