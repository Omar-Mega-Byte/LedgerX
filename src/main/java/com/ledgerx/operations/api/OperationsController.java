package com.ledgerx.operations.api;

import com.ledgerx.api.MoneyResponse;
import com.ledgerx.ledger.application.LedgerBalanceQueryService;
import com.ledgerx.ledger.domain.AccountStatus;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.operations.OperationsConflictException;
import com.ledgerx.operations.OperationsNotFoundException;
import com.ledgerx.operations.OperationsValidationException;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerStatus;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Privileged setup and read-only operational evidence; guarded by the production filter chain. */
@Validated
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {

  private final WalletOwnerRepository ownerRepository;
  private final LedgerAccountRepository accountRepository;
  private final WalletAccountService walletAccountService;
  private final LedgerBalanceQueryService balanceQueryService;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public OperationsController(
      WalletOwnerRepository ownerRepository,
      LedgerAccountRepository accountRepository,
      WalletAccountService walletAccountService,
      LedgerBalanceQueryService balanceQueryService,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.ownerRepository = ownerRepository;
    this.accountRepository = accountRepository;
    this.walletAccountService = walletAccountService;
    this.balanceQueryService = balanceQueryService;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @GetMapping("/owners")
  @Transactional(readOnly = true)
  public List<OwnerResponse> listOwners(
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page) {
    return ownerRepository
        .findAll(PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
        .stream()
        .map(this::toOwnerResponse)
        .toList();
  }

  @GetMapping("/owners/{ownerId}")
  @Transactional(readOnly = true)
  public OwnerResponse findOwner(@PathVariable UUID ownerId) {
    return toOwnerResponse(owner(ownerId));
  }

  @PostMapping("/owners")
  @Transactional
  public OwnerResponse createOwner(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody CreateOwnerRequest request) {
    int claimed =
        jdbcTemplate.update(
            """
            INSERT INTO ledgerx.operator_provisioning_requests
                (idempotency_key, owner_type, owner_id, created_at, completed_at)
            VALUES (?, ?, NULL, ?, NULL)
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
            idempotencyKey,
            request.ownerType().name(),
            java.sql.Timestamp.from(clock.instant()));
    if (claimed == 0) {
      return jdbcTemplate
          .query(
              """
              SELECT owner_type, owner_id FROM ledgerx.operator_provisioning_requests
              WHERE idempotency_key = ?
              """,
              (resultSet, rowNumber) -> {
                if (!request.ownerType().name().equals(resultSet.getString("owner_type"))) {
                  throw new OperationsConflictException(
                      "idempotency key was used for another owner type");
                }
                UUID ownerId = resultSet.getObject("owner_id", UUID.class);
                if (ownerId == null) {
                  throw new OperationsConflictException("owner provisioning is still processing");
                }
                return findOwner(ownerId);
              },
              idempotencyKey)
          .stream()
          .findFirst()
          .orElseThrow(() -> new OperationsConflictException("provisioning result is unavailable"));
    }
    WalletRegistration registration = walletAccountService.createWallet(request.ownerType());
    jdbcTemplate.update(
        """
        UPDATE ledgerx.operator_provisioning_requests
        SET owner_id = ?, completed_at = ?
        WHERE idempotency_key = ? AND owner_id IS NULL
        """,
        registration.ownerId(),
        java.sql.Timestamp.from(clock.instant()),
        idempotencyKey);
    return findOwner(registration.ownerId());
  }

  @PostMapping("/owners/{ownerId}/wallets")
  public OwnerResponse createWallet(@PathVariable UUID ownerId) {
    owner(ownerId);
    if (!accountRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId).isEmpty()) {
      throw new OperationsValidationException("owner already has a USD wallet");
    }
    try {
      walletAccountService.createWalletForOwner(ownerId);
      return findOwner(ownerId);
    } catch (IllegalStateException exception) {
      throw new OperationsValidationException(exception.getMessage());
    }
  }

  @PostMapping("/owners/{ownerId}/suspend")
  @Transactional
  public OwnerResponse suspendOwner(@PathVariable UUID ownerId) {
    WalletOwner owner = owner(ownerId);
    if (owner.isActive()) {
      owner.suspend(clock);
    }
    return toOwnerResponse(owner);
  }

  @PostMapping("/wallets/{walletId}/suspend")
  public WalletResponse suspendWallet(@PathVariable UUID walletId) {
    wallet(walletId);
    try {
      walletAccountService.suspendWallet(walletId);
      return toWalletResponse(wallet(walletId));
    } catch (IllegalStateException exception) {
      throw new OperationsValidationException(exception.getMessage());
    }
  }

  @PostMapping("/wallets/{walletId}/close")
  public WalletResponse closeWallet(@PathVariable UUID walletId) {
    wallet(walletId);
    try {
      walletAccountService.closeWallet(walletId);
      return toWalletResponse(wallet(walletId));
    } catch (IllegalStateException exception) {
      throw new OperationsValidationException(exception.getMessage());
    }
  }

  @GetMapping("/summary")
  @Transactional(readOnly = true)
  public OperationsSummary summary() {
    return new OperationsSummary(
        count("SELECT COUNT(*) FROM ledgerx.outbox_events WHERE status = 'PENDING'"),
        count("SELECT COUNT(*) FROM ledgerx.webhook_deliveries WHERE status = 'PENDING'"),
        count("SELECT COUNT(*) FROM ledgerx.webhook_deliveries WHERE status = 'DEAD'"),
        jdbcTemplate
            .query(
                """
                SELECT id, status, finding_count, started_at, completed_at, failure_category
                FROM ledgerx.reconciliation_runs ORDER BY started_at DESC LIMIT 1
                """,
                (resultSet, rowNumber) -> run(resultSet))
            .stream()
            .findFirst()
            .orElse(null));
  }

  @GetMapping("/reconciliation-runs")
  @Transactional(readOnly = true)
  public List<ReconciliationRunResponse> runs(
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return jdbcTemplate.query(
        """
        SELECT id, status, finding_count, started_at, completed_at, failure_category
        FROM ledgerx.reconciliation_runs ORDER BY started_at DESC LIMIT ?
        """,
        (resultSet, rowNumber) -> run(resultSet),
        limit);
  }

  @GetMapping("/reconciliation-runs/{runId}/findings")
  @Transactional(readOnly = true)
  public List<ReconciliationFindingResponse> findings(@PathVariable UUID runId) {
    boolean exists =
        Boolean.TRUE.equals(
            jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ledgerx.reconciliation_runs WHERE id = ?)",
                Boolean.class,
                runId));
    if (!exists) {
      throw new OperationsNotFoundException("reconciliation run was not found");
    }
    return jdbcTemplate.query(
        """
        SELECT id, finding_type, severity, entity_type, entity_id, details, detected_at
        FROM ledgerx.reconciliation_findings
        WHERE reconciliation_run_id = ?
        ORDER BY detected_at, id
        """,
        (resultSet, rowNumber) ->
            new ReconciliationFindingResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("finding_type"),
                resultSet.getString("severity"),
                resultSet.getString("entity_type"),
                resultSet.getObject("entity_id", UUID.class),
                resultSet.getString("details"),
                resultSet.getTimestamp("detected_at").toInstant()),
        runId);
  }

  private WalletOwner owner(UUID ownerId) {
    return ownerRepository
        .findById(ownerId)
        .orElseThrow(() -> new OperationsNotFoundException("owner was not found"));
  }

  private LedgerAccount wallet(UUID walletId) {
    LedgerAccount account =
        accountRepository
            .findById(walletId)
            .orElseThrow(() -> new OperationsNotFoundException("wallet was not found"));
    if (!account.isWallet()) {
      throw new OperationsNotFoundException("wallet was not found");
    }
    return account;
  }

  private OwnerResponse toOwnerResponse(WalletOwner owner) {
    return new OwnerResponse(
        owner.id(),
        owner.ownerType(),
        owner.status(),
        accountRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.id()).stream()
            .map(this::toWalletResponse)
            .toList());
  }

  private WalletResponse toWalletResponse(LedgerAccount wallet) {
    return new WalletResponse(
        wallet.id(), wallet.status(), MoneyResponse.from(balanceQueryService.balanceOf(wallet)));
  }

  private int count(String sql) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }

  private ReconciliationRunResponse run(java.sql.ResultSet resultSet) throws java.sql.SQLException {
    java.sql.Timestamp completedAt = resultSet.getTimestamp("completed_at");
    return new ReconciliationRunResponse(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("status"),
        resultSet.getInt("finding_count"),
        resultSet.getTimestamp("started_at").toInstant(),
        completedAt == null ? null : completedAt.toInstant(),
        resultSet.getString("failure_category"));
  }

  public record CreateOwnerRequest(@NotNull OwnerType ownerType) {}

  public record OwnerResponse(
      UUID ownerId, OwnerType ownerType, OwnerStatus status, List<WalletResponse> wallets) {}

  public record WalletResponse(UUID walletId, AccountStatus status, MoneyResponse balance) {}

  public record OperationsSummary(
      int pendingOutboxEvents,
      int pendingWebhookDeliveries,
      int deadWebhookDeliveries,
      ReconciliationRunResponse latestReconciliation) {}

  public record ReconciliationRunResponse(
      UUID runId,
      String status,
      int findingCount,
      Instant startedAt,
      Instant completedAt,
      String failureCategory) {}

  public record ReconciliationFindingResponse(
      UUID findingId,
      String findingType,
      String severity,
      String entityType,
      UUID entityId,
      String details,
      Instant detectedAt) {}
}
