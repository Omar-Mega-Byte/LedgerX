package com.ledgerx.transfer;

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
import com.ledgerx.ledger.domain.InsufficientFundsException;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.transfer.application.TransferApplicationService;
import com.ledgerx.transfer.application.TransferExecution;
import com.ledgerx.transfer.domain.TransferCommand;
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
class TransferApiIntegrationTest {

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
  @Autowired private TransferApplicationService transferApplicationService;
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
        "TRUNCATE TABLE ledgerx.reconciliation_findings, ledgerx.reconciliation_runs, "
            + "ledgerx.webhook_delivery_attempts, ledgerx.webhook_deliveries, "
            + "ledgerx.webhook_endpoint_idempotency, ledgerx.webhook_endpoints, "
            + "ledgerx.processed_events, ledgerx.outbox_events, ledgerx.refund_idempotency, "
            + "ledgerx.payment_idempotency, ledgerx.refunds, ledgerx.payments, "
            + "ledgerx.transfer_idempotency, ledgerx.transfers, ledgerx.ledger_entries, "
            + "ledgerx.ledger_transactions, ledgerx.ledger_accounts, ledgerx.wallet_owners");
  }

  @Test
  void createsAnAuditableTransferAndReturnsTheDocumentedResponse() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);

    MvcResult result =
        postTransfer(
                source.ownerId(),
                "create-transfer",
                source.walletId(),
                destination.walletAccountId(),
                "25.00")
            .andExpect(status().isCreated())
            .andExpect(
                header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/transfers/")))
            .andExpect(jsonPath("$.sourceWalletId").value(source.walletId().toString()))
            .andExpect(
                jsonPath("$.destinationWalletId").value(destination.walletAccountId().toString()))
            .andExpect(jsonPath("$.money.amount").value("25.00"))
            .andExpect(jsonPath("$.money.currency").value("USD"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andReturn();

    JsonNode response = body(result);
    UUID transferId = UUID.fromString(response.get("transferId").asText());
    UUID ledgerTransactionId = UUID.fromString(response.get("ledgerTransactionId").asText());
    assertThat(ledgerBalanceQueryService.balanceOf(source.walletId()).amount())
        .isEqualByComparingTo("75.00");
    assertThat(ledgerBalanceQueryService.balanceOf(destination.walletAccountId()).amount())
        .isEqualByComparingTo("25.00");
    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers WHERE id = ?", transferId))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM ledgerx.ledger_entries WHERE ledger_transaction_id = ?",
                ledgerTransactionId))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT state FROM ledgerx.transfer_idempotency WHERE idempotency_key = ?",
                String.class,
                "create-transfer"))
        .isEqualTo("COMPLETED");
  }

  @Test
  void transfersTheSmallestExactBalanceAcrossEveryOwnerTypePair() throws Exception {
    int transferNumber = 0;
    for (OwnerType sourceType : OwnerType.values()) {
      for (OwnerType destinationType : OwnerType.values()) {
        FundedWallet source = fundedWallet(sourceType, "0.01");
        WalletRegistration destination = walletAccountService.createWallet(destinationType);
        postTransfer(
                source.ownerId(),
                "owner-pair-" + transferNumber++,
                source.walletId(),
                destination.walletAccountId(),
                "0.01")
            .andExpect(status().isCreated());
        assertThat(ledgerBalanceQueryService.balanceOf(source.walletId()).amount())
            .isEqualByComparingTo("0.00");
        assertThat(ledgerBalanceQueryService.balanceOf(destination.walletAccountId()).amount())
            .isEqualByComparingTo("0.01");
      }
    }

    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers")).isEqualTo(4);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.ledger_transactions")).isEqualTo(8);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.ledger_entries")).isEqualTo(16);
    assertThat(
            count(
                "SELECT COUNT(*) FROM (SELECT ledger_transaction_id FROM ledgerx.ledger_entries GROUP BY ledger_transaction_id HAVING SUM(CASE WHEN side = 'DEBIT' THEN amount ELSE -amount END) <> 0) AS unbalanced"))
        .isZero();
  }

  @Test
  void replaysAnIdenticalScopedKeyWithoutAnotherFinancialEffect() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);

    MvcResult first =
        postTransfer(
                source.ownerId(),
                "replay-key",
                source.walletId(),
                destination.walletAccountId(),
                "10")
            .andExpect(status().isCreated())
            .andReturn();
    MvcResult replay =
        postTransfer(
                source.ownerId(),
                "replay-key",
                source.walletId(),
                destination.walletAccountId(),
                "10.00")
            .andExpect(status().isOk())
            .andReturn();

    assertThat(body(replay).get("transferId").asText())
        .isEqualTo(body(first).get("transferId").asText());
    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.ledger_transactions")).isEqualTo(2);
    assertThat(ledgerBalanceQueryService.balanceOf(source.walletId()).amount())
        .isEqualByComparingTo("90.00");
  }

  @Test
  void rejectsReuseOfAKeyForADifferentRequestWithoutAnotherTransfer() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);

    postTransfer(
            source.ownerId(),
            "conflict-key",
            source.walletId(),
            destination.walletAccountId(),
            "10.00")
        .andExpect(status().isCreated());
    postTransfer(
            source.ownerId(),
            "conflict-key",
            source.walletId(),
            destination.walletAccountId(),
            "11.00")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers")).isEqualTo(1);
    assertThat(ledgerBalanceQueryService.balanceOf(source.walletId()).amount())
        .isEqualByComparingTo("90.00");
  }

  @Test
  void rollsBackFailedTransfersIncludingTheIdempotencyClaim() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "5.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);

    postTransfer(
            source.ownerId(),
            "insufficient-key",
            source.walletId(),
            destination.walletAccountId(),
            "10.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_PROCESSABLE"));

    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers")).isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM ledgerx.transfer_idempotency WHERE idempotency_key = ?",
                "insufficient-key"))
        .isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.ledger_transactions")).isEqualTo(1);
    assertThat(ledgerBalanceQueryService.balanceOf(source.walletId()).amount())
        .isEqualByComparingTo("5.00");
  }

  @Test
  void enforcesSourceOwnershipAndKeepsParticipatingTransferReadsPrivate() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);
    WalletRegistration unrelated = walletAccountService.createWallet(OwnerType.PERSON);

    postTransfer(
            unrelated.ownerId(),
            "unauthorized-key",
            source.walletId(),
            destination.walletAccountId(),
            "10.00")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_AUTHORIZED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM ledgerx.transfer_idempotency WHERE idempotency_key = ?",
                "unauthorized-key"))
        .isZero();

    MvcResult created =
        postTransfer(
                source.ownerId(),
                "read-key",
                source.walletId(),
                destination.walletAccountId(),
                "10.00")
            .andExpect(status().isCreated())
            .andReturn();
    UUID transferId = UUID.fromString(body(created).get("transferId").asText());

    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}", transferId)
                .header("X-LedgerX-Owner-Id", destination.ownerId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferId").value(transferId.toString()));
    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}", transferId)
                .header("X-LedgerX-Owner-Id", unrelated.ownerId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
  }

  @Test
  void scopesTheSameIdempotencyKeyToEachSourceOwner() throws Exception {
    FundedWallet firstSource = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration firstDestination = walletAccountService.createWallet(OwnerType.MERCHANT);
    FundedWallet secondSource = fundedWallet(OwnerType.MERCHANT, "100.00");
    WalletRegistration secondDestination = walletAccountService.createWallet(OwnerType.PERSON);

    postTransfer(
            firstSource.ownerId(),
            "shared-client-key",
            firstSource.walletId(),
            firstDestination.walletAccountId(),
            "10.00")
        .andExpect(status().isCreated());
    postTransfer(
            secondSource.ownerId(),
            "shared-client-key",
            secondSource.walletId(),
            secondDestination.walletAccountId(),
            "10.00")
        .andExpect(status().isCreated());

    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers")).isEqualTo(2);
    assertThat(
            count(
                "SELECT COUNT(*) FROM ledgerx.transfer_idempotency WHERE idempotency_key = ?",
                "shared-client-key"))
        .isEqualTo(2);
  }

  @Test
  void returnsStableProblemShapesForMalformedAndUnprocessableRequests() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);

    mockMvc
        .perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", "missing-owner")
                .contentType("application/json")
                .content(request(source.walletId(), destination.walletAccountId(), "1.00")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        .andExpect(jsonPath("$.details").isArray());
    postTransfer(
            source.ownerId(),
            "zero-amount",
            source.walletId(),
            destination.walletAccountId(),
            "0.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_PROCESSABLE"));
    postTransfer(source.ownerId(), "same-wallet", source.walletId(), source.walletId(), "1.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_PROCESSABLE"));
    postTransfer(
            source.ownerId(),
            "negative-amount",
            source.walletId(),
            destination.walletAccountId(),
            "-1.00")
        .andExpect(status().isUnprocessableEntity());
    postTransfer(
            source.ownerId(),
            "excessive-scale",
            source.walletId(),
            destination.walletAccountId(),
            "0.001")
        .andExpect(status().isUnprocessableEntity());
    postTransfer(
            source.ownerId(),
            "malformed-amount",
            source.walletId(),
            destination.walletAccountId(),
            "not-a-number")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    postTransfer(
            source.ownerId(),
            "database-overflow",
            source.walletId(),
            destination.walletAccountId(),
            "100000000000000000.00")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_PROCESSABLE"));
    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers")).isZero();
    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfer_idempotency")).isZero();
    assertThat(ledgerBalanceQueryService.balanceOf(source.walletId()).amount())
        .isEqualByComparingTo("100.00");
  }

  @Test
  void concurrentDuplicateKeysCreateOneTransferAndReplayTheOtherResult() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);
    TransferCommand command =
        command(source.walletId(), destination.walletAccountId(), "25.00", "concurrent-key");

    List<TransferExecution> results =
        runTogether(
            () -> transfer(source.ownerId(), command), () -> transfer(source.ownerId(), command));

    assertThat(results.getFirst().transfer().id()).isEqualTo(results.getLast().transfer().id());
    assertThat(results)
        .extracting(TransferExecution::replayed)
        .containsExactlyInAnyOrder(false, true);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.transfers")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM ledgerx.ledger_transactions")).isEqualTo(2);
  }

  @Test
  void competingDebitsAndOppositeDirectionTransfersReuseTheLedgerLockProtocol() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration firstDestination = walletAccountService.createWallet(OwnerType.MERCHANT);
    WalletRegistration secondDestination = walletAccountService.createWallet(OwnerType.PERSON);

    List<Boolean> debitOutcomes =
        runTogether(
            () ->
                attemptTransfer(
                    source.ownerId(),
                    command(
                        source.walletId(),
                        firstDestination.walletAccountId(),
                        "80.00",
                        "debit-one")),
            () ->
                attemptTransfer(
                    source.ownerId(),
                    command(
                        source.walletId(),
                        secondDestination.walletAccountId(),
                        "80.00",
                        "debit-two")));

    assertThat(debitOutcomes).containsExactlyInAnyOrder(true, false);
    assertThat(ledgerBalanceQueryService.balanceOf(source.walletId()).amount())
        .isEqualByComparingTo("20.00");

    FundedWallet first = fundedWallet(OwnerType.PERSON, "100.00");
    FundedWallet second = fundedWallet(OwnerType.MERCHANT, "100.00");
    runTogether(
        () ->
            transfer(
                first.ownerId(), command(first.walletId(), second.walletId(), "20.00", "forward")),
        () ->
            transfer(
                second.ownerId(),
                command(second.walletId(), first.walletId(), "30.00", "reverse")));

    assertThat(ledgerBalanceQueryService.balanceOf(first.walletId()).amount())
        .isEqualByComparingTo("110.00");
    assertThat(ledgerBalanceQueryService.balanceOf(second.walletId()).amount())
        .isEqualByComparingTo("90.00");
  }

  @Test
  void databasePreventsMutationOfCompletedTransferAndIdempotencyFacts() throws Exception {
    FundedWallet source = fundedWallet(OwnerType.PERSON, "100.00");
    WalletRegistration destination = walletAccountService.createWallet(OwnerType.MERCHANT);
    MvcResult created =
        postTransfer(
                source.ownerId(),
                "immutable-key",
                source.walletId(),
                destination.walletAccountId(),
                "10.00")
            .andExpect(status().isCreated())
            .andReturn();
    UUID transferId = UUID.fromString(body(created).get("transferId").asText());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.transfers SET amount = ? WHERE id = ?",
                    new BigDecimal("11.00"),
                    transferId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.transfer_idempotency SET idempotency_key = ? WHERE idempotency_key = ?",
                    "changed-key",
                    "immutable-key"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void publishesInteractiveOpenApiDocumentationWithReadyToEditExamples() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

    JsonNode document = body(result);
    JsonNode createTransfer = document.at("/paths/~1api~1v1~1transfers/post");
    assertThat(document.at("/info/title").asText()).isEqualTo("LedgerX API");
    assertThat(createTransfer.at("/operationId").asText()).isEqualTo("createTransfer");
    assertThat(createTransfer.at("/responses/201/description").asText())
        .contains("completed atomically");
    assertThat(
            createTransfer
                .at(
                    "/requestBody/content/application~1json/examples/usd-wallet-transfer/value/money/amount")
                .asText())
        .isEqualTo("25.00");
    assertThat(createTransfer.at("/parameters").toString())
        .contains("X-LedgerX-Owner-Id", "Idempotency-Key", "transfer-demo-0001");
    assertThat(document.at("/paths/~1api~1v1~1transfers~1{transferId}/get/operationId").asText())
        .isEqualTo("getTransfer");

    mockMvc.perform(get("/swagger")).andExpect(status().is3xxRedirection());
  }

  private org.springframework.test.web.servlet.ResultActions postTransfer(
      UUID ownerId,
      String idempotencyKey,
      UUID sourceWalletId,
      UUID destinationWalletId,
      String amount)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/transfers")
            .header("X-LedgerX-Owner-Id", ownerId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(request(sourceWalletId, destinationWalletId, amount)));
  }

  private String request(UUID sourceWalletId, UUID destinationWalletId, String amount) {
    return """
        {"sourceWalletId":"%s","destinationWalletId":"%s","money":{"amount":"%s","currency":"USD"}}
        """
        .formatted(sourceWalletId, destinationWalletId, amount);
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
            new PostingLine(
                clearingAccountId,
                EntrySide.DEBIT,
                new Money(new BigDecimal(amount), CurrencyCode.USD)),
            new PostingLine(
                wallet.walletAccountId(),
                EntrySide.CREDIT,
                new Money(new BigDecimal(amount), CurrencyCode.USD))));
    return new FundedWallet(wallet.ownerId(), wallet.walletAccountId());
  }

  private TransferCommand command(
      UUID sourceWalletId, UUID destinationWalletId, String amount, String idempotencyKey) {
    return new TransferCommand(
        sourceWalletId,
        destinationWalletId,
        new Money(new BigDecimal(amount), CurrencyCode.USD),
        idempotencyKey);
  }

  private TransferExecution transfer(UUID ownerId, TransferCommand command) {
    return transferApplicationService.transfer(new OwnerContext(ownerId), command);
  }

  private boolean attemptTransfer(UUID ownerId, TransferCommand command) {
    try {
      transfer(ownerId, command);
      return true;
    } catch (InsufficientFundsException exception) {
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
      throw new IllegalStateException("concurrent transfer did not complete", exception);
    }
  }

  private int count(String query, Object... parameters) {
    return jdbcTemplate.queryForObject(query, Integer.class, parameters);
  }

  private record FundedWallet(UUID ownerId, UUID walletId) {}
}
