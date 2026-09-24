package com.ledgerx.risk;

import com.ledgerx.money.Money;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed payment risk decisions. Operators never call the financial posting path. */
@Service
public class PaymentRiskService {

  private static final Duration OPEN_LIFETIME = Duration.ofDays(7);
  private static final Duration APPROVAL_LIFETIME = Duration.ofHours(24);
  private static final int MAX_OPEN_CASES = 10;

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final RiskMetrics metrics;

  public PaymentRiskService(JdbcTemplate jdbc, Clock clock, RiskMetrics metrics) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.metrics = metrics;
  }

  public Policy activePolicy() {
    return jdbc
        .query(
            """
            SELECT p.id, p.version_number, p.enabled, p.max_payment_amount,
                   p.review_payment_count, p.review_payment_total, p.actor_subject,
                   p.change_reason, p.created_at
            FROM ledgerx.risk_policy_activation a
            JOIN ledgerx.risk_policy_versions p ON p.id = a.policy_id
            WHERE a.singleton_id = 1
            FOR SHARE OF a
            """,
            (rs, row) ->
                new Policy(
                    rs.getObject("id", UUID.class),
                    rs.getLong("version_number"),
                    rs.getBoolean("enabled"),
                    rs.getBigDecimal("max_payment_amount"),
                    rs.getInt("review_payment_count"),
                    rs.getBigDecimal("review_payment_total"),
                    rs.getString("actor_subject"),
                    rs.getString("change_reason"),
                    rs.getTimestamp("created_at").toInstant()))
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("active risk policy is missing"));
  }

  /**
   * A payer-scoped advisory transaction lock serializes payment risk windows without changing
   * ledger lock order.
   */
  public Decision assess(UUID payerOwnerId, Money money, boolean approvedReview) {
    Policy policy = activePolicy();
    if (!policy.enabled()) {
      return new Decision(policy.id(), approvedReview, "ALLOW", "", 0, BigDecimal.ZERO);
    }
    jdbc.query(
        "SELECT pg_advisory_xact_lock(91225405, hashtext(?))",
        rs -> {
          rs.next();
          return null;
        },
        payerOwnerId.toString());
    Instant now = clock.instant();
    Window window =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) AS payment_count, COALESCE(SUM(p.amount), 0) AS payment_total
            FROM ledgerx.payments p
            JOIN ledgerx.ledger_accounts wallet ON wallet.id = p.payer_wallet_account_id
            WHERE wallet.owner_id = ? AND p.completed_at >= ? AND p.completed_at < ?
            """,
            (rs, row) -> new Window(rs.getInt("payment_count"), rs.getBigDecimal("payment_total")),
            payerOwnerId,
            Timestamp.from(now.minus(Duration.ofHours(24))),
            Timestamp.from(now));
    List<String> rules = new ArrayList<>();
    if (money.amount().compareTo(policy.maxPaymentAmount()) > 0) {
      rules.add("MAX_PAYMENT_AMOUNT");
    }
    int openCases =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM ledgerx.risk_review_cases
            WHERE payer_owner_id = ? AND status IN ('OPEN', 'APPROVED')
              AND CASE WHEN status = 'APPROVED' THEN approval_expires_at
                       ELSE open_expires_at END > ?
            """,
            Integer.class,
            payerOwnerId,
            Timestamp.from(now));
    if (openCases >= MAX_OPEN_CASES && !approvedReview) {
      rules.add("OPEN_CASE_LIMIT");
    }
    if (!rules.isEmpty()) {
      return new Decision(
          policy.id(), true, "BLOCK", String.join(",", rules), window.count(), window.total());
    }
    if (!approvedReview) {
      if ((long) window.count() + 1 > policy.reviewPaymentCount()) {
        rules.add("PAYMENT_COUNT_24H");
      }
      if (window.total().add(money.amount()).compareTo(policy.reviewPaymentTotal()) > 0) {
        rules.add("PAYMENT_TOTAL_24H");
      }
    }
    return new Decision(
        policy.id(),
        true,
        rules.isEmpty() ? "ALLOW" : "REVIEW",
        String.join(",", rules),
        window.count(),
        window.total());
  }

  public void recordAllow(
      Decision decision,
      UUID idempotencyId,
      UUID payerOwnerId,
      UUID payerWalletId,
      UUID merchantWalletId,
      String fingerprint,
      UUID paymentId) {
    if (decision.enabled()) {
      insertAssessment(
          decision,
          idempotencyId,
          payerOwnerId,
          payerWalletId,
          merchantWalletId,
          fingerprint,
          paymentId);
      metrics.decided("ALLOW");
    }
  }

  public RiskResult recordReview(
      Decision decision,
      UUID idempotencyId,
      UUID payerOwnerId,
      UUID payerWalletId,
      UUID merchantWalletId,
      String fingerprint) {
    UUID assessmentId =
        insertAssessment(
            decision,
            idempotencyId,
            payerOwnerId,
            payerWalletId,
            merchantWalletId,
            fingerprint,
            null);
    UUID caseId = UUID.randomUUID();
    Instant now = clock.instant();
    Instant expiresAt = now.plus(OPEN_LIFETIME);
    jdbc.update(
        """
        INSERT INTO ledgerx.risk_review_cases
            (id, assessment_id, payment_idempotency_id, payer_owner_id, status,
             open_expires_at, approval_expires_at, payment_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, 'OPEN', ?, NULL, NULL, ?, ?)
        """,
        caseId,
        assessmentId,
        idempotencyId,
        payerOwnerId,
        Timestamp.from(expiresAt),
        Timestamp.from(now),
        Timestamp.from(now));
    metrics.decided("REVIEW");
    return new RiskResult(assessmentId, caseId, "REVIEW", "OPEN", expiresAt);
  }

  public RiskResult recordBlock(
      Decision decision,
      UUID idempotencyId,
      UUID payerOwnerId,
      UUID payerWalletId,
      UUID merchantWalletId,
      String fingerprint,
      UUID reviewedCaseId) {
    UUID decisionId =
        insertAssessment(
            decision,
            idempotencyId,
            payerOwnerId,
            payerWalletId,
            merchantWalletId,
            fingerprint,
            null);
    if (reviewedCaseId != null) {
      jdbc.update(
          """
          UPDATE ledgerx.risk_review_cases
          SET status = 'POLICY_BLOCKED', updated_at = ?
          WHERE id = ? AND status = 'APPROVED'
          """,
          Timestamp.from(clock.instant()),
          reviewedCaseId);
    }
    metrics.decided("BLOCK");
    return new RiskResult(
        decisionId,
        reviewedCaseId,
        "BLOCK",
        reviewedCaseId == null ? null : "POLICY_BLOCKED",
        null);
  }

  private UUID insertAssessment(
      Decision decision,
      UUID idempotencyId,
      UUID payerOwnerId,
      UUID payerWalletId,
      UUID merchantWalletId,
      String fingerprint,
      UUID paymentId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ledgerx.risk_assessments
            (id, payment_idempotency_id, payer_owner_id, payer_wallet_id,
             merchant_wallet_id, policy_id, request_fingerprint, outcome, rule_codes,
             completed_count, completed_total, payment_id, evaluated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        idempotencyId,
        payerOwnerId,
        payerWalletId,
        merchantWalletId,
        decision.policyId(),
        fingerprint,
        decision.outcome(),
        decision.ruleCodes(),
        decision.completedCount(),
        decision.completedTotal(),
        paymentId,
        Timestamp.from(clock.instant()));
    return id;
  }

  /** Called with the payment idempotency row locked. */
  public ReviewResolution resolveReview(UUID idempotencyId) {
    ReviewCase review = caseByIdempotency(idempotencyId, true);
    Instant now = clock.instant();
    boolean expired =
        (review.status().equals("OPEN") && !now.isBefore(review.openExpiresAt()))
            || (review.status().equals("APPROVED") && !now.isBefore(review.approvalExpiresAt()));
    if (expired) {
      jdbc.update(
          "UPDATE ledgerx.risk_review_cases SET status = 'EXPIRED', updated_at = ? WHERE id = ?",
          Timestamp.from(now),
          review.id());
      review =
          new ReviewCase(
              review.id(),
              review.assessmentId(),
              "EXPIRED",
              review.openExpiresAt(),
              review.approvalExpiresAt(),
              null);
    }
    Instant expiresAt =
        review.approvalExpiresAt() == null ? review.openExpiresAt() : review.approvalExpiresAt();
    return new ReviewResolution(
        new RiskResult(review.assessmentId(), review.id(), "REVIEW", review.status(), expiresAt),
        review.status().equals("APPROVED"));
  }

  public RiskResult blockedResult(UUID idempotencyId) {
    return jdbc
        .query(
            """
            SELECT a.id, c.id AS case_id, c.status AS case_status
            FROM ledgerx.risk_assessments a
            LEFT JOIN ledgerx.risk_review_cases c ON c.payment_idempotency_id = a.payment_idempotency_id
            WHERE a.payment_idempotency_id = ? AND a.outcome = 'BLOCK'
            ORDER BY a.evaluated_at DESC, a.id DESC LIMIT 1
            """,
            (rs, row) ->
                new RiskResult(
                    rs.getObject("id", UUID.class),
                    rs.getObject("case_id", UUID.class),
                    "BLOCK",
                    rs.getString("case_status"),
                    null),
            idempotencyId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("blocked payment has no risk decision"));
  }

  public void consumeReview(UUID idempotencyId, UUID paymentId) {
    int updated =
        jdbc.update(
            """
            UPDATE ledgerx.risk_review_cases
            SET status = 'CONSUMED', payment_id = ?, updated_at = ?
            WHERE payment_idempotency_id = ? AND status = 'APPROVED'
              AND approval_expires_at > ?
            """,
            paymentId,
            Timestamp.from(clock.instant()),
            idempotencyId,
            Timestamp.from(clock.instant()));
    if (updated != 1) {
      throw new RiskConflictException("review approval is no longer valid");
    }
  }

  public ReviewCase caseForOwner(UUID caseId, UUID ownerId) {
    ReviewCase review =
        jdbc
            .query(
                """
            SELECT c.id, c.assessment_id, c.status, c.open_expires_at,
                   c.approval_expires_at, c.payment_id
            FROM ledgerx.risk_review_cases c WHERE c.id = ? AND c.payer_owner_id = ?
            """,
                (rs, row) -> mapCase(rs),
                caseId,
                ownerId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new RiskNotFoundException("risk review case was not found"));
    return effectiveCase(review);
  }

  private ReviewCase effectiveCase(ReviewCase review) {
    boolean expired =
        (review.status().equals("OPEN") && !clock.instant().isBefore(review.openExpiresAt()))
            || (review.status().equals("APPROVED")
                && !clock.instant().isBefore(review.approvalExpiresAt()));
    return expired
        ? new ReviewCase(
            review.id(),
            review.assessmentId(),
            "EXPIRED",
            review.openExpiresAt(),
            review.approvalExpiresAt(),
            review.paymentId())
        : review;
  }

  @Scheduled(fixedDelayString = "${ledgerx.risk.expiry-poll-delay:PT1M}")
  @Transactional
  public void expireCases() {
    Timestamp now = Timestamp.from(clock.instant());
    jdbc.update(
        """
        UPDATE ledgerx.risk_review_cases SET status = 'EXPIRED', updated_at = ?
        WHERE status = 'OPEN' AND open_expires_at <= ?
        """,
        now,
        now);
    jdbc.update(
        """
        UPDATE ledgerx.risk_review_cases SET status = 'EXPIRED', updated_at = ?
        WHERE status = 'APPROVED' AND approval_expires_at <= ?
        """,
        now,
        now);
  }

  private ReviewCase caseByIdempotency(UUID idempotencyId, boolean lock) {
    return jdbc
        .query(
            """
            SELECT id, assessment_id, status, open_expires_at, approval_expires_at, payment_id
            FROM ledgerx.risk_review_cases WHERE payment_idempotency_id = ?
            """
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> mapCase(rs),
            idempotencyId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("review payment has no risk case"));
  }

  private ReviewCase mapCase(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp approval = rs.getTimestamp("approval_expires_at");
    return new ReviewCase(
        rs.getObject("id", UUID.class),
        rs.getObject("assessment_id", UUID.class),
        rs.getString("status"),
        rs.getTimestamp("open_expires_at").toInstant(),
        approval == null ? null : approval.toInstant(),
        rs.getObject("payment_id", UUID.class));
  }

  @Transactional
  public Policy activate(
      boolean enabled,
      BigDecimal maxPaymentAmount,
      int reviewPaymentCount,
      BigDecimal reviewPaymentTotal,
      String actor,
      String reason,
      String commandKey,
      long expectedVersion) {
    if (actor == null
        || actor.isBlank()
        || actor.length() > 255
        || maxPaymentAmount == null
        || maxPaymentAmount.signum() <= 0
        || maxPaymentAmount.scale() > 2
        || maxPaymentAmount.precision() - maxPaymentAmount.scale() > 17
        || reviewPaymentCount <= 0
        || reviewPaymentTotal == null
        || reviewPaymentTotal.signum() <= 0
        || reviewPaymentTotal.scale() > 2
        || reviewPaymentTotal.precision() - reviewPaymentTotal.scale() > 17
        || reason == null
        || reason.isBlank()
        || reason.length() > 500
        || commandKey == null
        || commandKey.isBlank()
        || commandKey.length() > 255) {
      throw new RiskValidationException("risk policy values or idempotency key are invalid");
    }
    jdbc.queryForObject(
        "SELECT policy_id FROM ledgerx.risk_policy_activation WHERE singleton_id = 1 FOR UPDATE",
        UUID.class);
    Policy existing = policyByCommandKey(commandKey);
    if (existing != null) {
      if (existing.enabled() != enabled
          || existing.maxPaymentAmount().compareTo(maxPaymentAmount) != 0
          || existing.reviewPaymentCount() != reviewPaymentCount
          || existing.reviewPaymentTotal().compareTo(reviewPaymentTotal) != 0
          || !existing.changeReason().equals(reason)) {
        throw new RiskConflictException("idempotency key was used for a different risk policy");
      }
      return existing;
    }
    Policy current = activePolicy();
    if (current.versionNumber() != expectedVersion) {
      throw new RiskConflictException("active risk policy version has changed");
    }
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    jdbc.update(
        """
        INSERT INTO ledgerx.risk_policy_versions
            (id, version_number, enabled, max_payment_amount, review_payment_count,
             review_payment_total, command_key, actor_subject, change_reason, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        current.versionNumber() + 1,
        enabled,
        maxPaymentAmount,
        reviewPaymentCount,
        reviewPaymentTotal,
        commandKey,
        actor,
        reason,
        Timestamp.from(now));
    jdbc.update(
        "UPDATE ledgerx.risk_policy_activation SET policy_id = ? WHERE singleton_id = 1", id);
    return new Policy(
        id,
        current.versionNumber() + 1,
        enabled,
        maxPaymentAmount,
        reviewPaymentCount,
        reviewPaymentTotal,
        actor,
        reason,
        now);
  }

  private Policy policyByCommandKey(String commandKey) {
    return jdbc
        .query(
            """
            SELECT id, version_number, enabled, max_payment_amount, review_payment_count,
                   review_payment_total, actor_subject, change_reason, created_at
            FROM ledgerx.risk_policy_versions WHERE command_key = ?
            """,
            (rs, row) ->
                new Policy(
                    rs.getObject("id", UUID.class),
                    rs.getLong("version_number"),
                    rs.getBoolean("enabled"),
                    rs.getBigDecimal("max_payment_amount"),
                    rs.getInt("review_payment_count"),
                    rs.getBigDecimal("review_payment_total"),
                    rs.getString("actor_subject"),
                    rs.getString("change_reason"),
                    rs.getTimestamp("created_at").toInstant()),
            commandKey)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Transactional
  public ReviewCase decide(UUID caseId, String action, String reason, String actor) {
    if (!action.equals("APPROVE") && !action.equals("DECLINE")) {
      throw new RiskValidationException("unsupported risk review action");
    }
    if (actor == null
        || actor.isBlank()
        || actor.length() > 255
        || reason == null
        || reason.isBlank()
        || reason.length() > 500) {
      throw new RiskValidationException("a short review reason is required");
    }
    ReviewCase current =
        jdbc
            .query(
                """
                SELECT id, assessment_id, status, open_expires_at, approval_expires_at, payment_id
                FROM ledgerx.risk_review_cases WHERE id = ? FOR UPDATE
                """,
                (rs, row) -> mapCase(rs),
                caseId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new RiskNotFoundException("risk review case was not found"));
    if (!clock.instant().isBefore(current.openExpiresAt()) && current.status().equals("OPEN")) {
      throw new RiskConflictException("risk review case has expired");
    }
    String desired = action.equals("APPROVE") ? "APPROVED" : "DECLINED";
    if (current.status().equals(desired)) {
      boolean identicalAction =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  """
                  SELECT EXISTS (
                      SELECT 1 FROM ledgerx.risk_review_actions
                      WHERE case_id = ? AND action = ? AND actor_subject = ? AND reason = ?
                  )
                  """,
                  Boolean.class,
                  caseId,
                  action,
                  actor,
                  reason));
      if (!identicalAction) {
        throw new RiskConflictException("risk review decision was already made differently");
      }
      return current;
    }
    if (!current.status().equals("OPEN")) {
      throw new RiskConflictException("risk review case is no longer open");
    }
    Instant now = clock.instant();
    Instant approvalExpiry = desired.equals("APPROVED") ? now.plus(APPROVAL_LIFETIME) : null;
    jdbc.update(
        """
        UPDATE ledgerx.risk_review_cases
        SET status = ?, approval_expires_at = ?, updated_at = ?
        WHERE id = ? AND status = 'OPEN'
        """,
        desired,
        approvalExpiry == null ? null : Timestamp.from(approvalExpiry),
        Timestamp.from(now),
        caseId);
    jdbc.update(
        """
        INSERT INTO ledgerx.risk_review_actions
            (id, case_id, actor_subject, action, prior_status, new_status, reason, created_at)
        VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)
        """,
        UUID.randomUUID(),
        caseId,
        actor,
        action,
        desired,
        reason,
        Timestamp.from(now));
    return new ReviewCase(
        current.id(),
        current.assessmentId(),
        desired,
        current.openExpiresAt(),
        approvalExpiry,
        null);
  }

  @Transactional(readOnly = true)
  public List<OperatorCase> listCases(String status, int limit, int page) {
    if (limit < 1 || limit > 100 || page < 0 || page > 100000) {
      throw new RiskValidationException("risk case page is invalid");
    }
    if (status != null
        && !List.of("OPEN", "APPROVED", "DECLINED", "EXPIRED", "POLICY_BLOCKED", "CONSUMED")
            .contains(status)) {
      throw new RiskValidationException("risk case status is invalid");
    }
    String filter = status == null ? "" : "WHERE c.status = ?";
    String sql =
        """
        SELECT c.id, c.payer_owner_id, c.status, c.created_at, c.open_expires_at,
               c.approval_expires_at, c.payment_id, a.outcome, a.rule_codes,
               a.completed_count, a.completed_total, a.policy_id
        FROM ledgerx.risk_review_cases c
        JOIN ledgerx.risk_assessments a ON a.id = c.assessment_id
        """
            + filter
            + " ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?";
    Object[] params =
        status == null
            ? new Object[] {limit, page * limit}
            : new Object[] {status, limit, page * limit};
    return jdbc.query(sql, (rs, row) -> mapOperatorCase(rs), params);
  }

  @Transactional(readOnly = true)
  public OperatorCase operatorCase(UUID caseId) {
    return jdbc
        .query(
            """
            SELECT c.id, c.payer_owner_id, c.status, c.created_at, c.open_expires_at,
                   c.approval_expires_at, c.payment_id, a.rule_codes,
                   a.completed_count, a.completed_total, a.policy_id
            FROM ledgerx.risk_review_cases c
            JOIN ledgerx.risk_assessments a ON a.id = c.assessment_id
            WHERE c.id = ?
            """,
            (rs, row) -> mapOperatorCase(rs),
            caseId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new RiskNotFoundException("risk review case was not found"));
  }

  @Transactional(readOnly = true)
  public List<ReviewAction> actions(UUID caseId) {
    operatorCase(caseId);
    return jdbc.query(
        """
        SELECT id, actor_subject, action, prior_status, new_status, reason, created_at
        FROM ledgerx.risk_review_actions WHERE case_id = ? ORDER BY created_at, id LIMIT 100
        """,
        (rs, row) ->
            new ReviewAction(
                rs.getObject("id", UUID.class),
                rs.getString("actor_subject"),
                rs.getString("action"),
                rs.getString("prior_status"),
                rs.getString("new_status"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()),
        caseId);
  }

  private OperatorCase mapOperatorCase(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp approval = rs.getTimestamp("approval_expires_at");
    return new OperatorCase(
        rs.getObject("id", UUID.class),
        rs.getObject("payer_owner_id", UUID.class),
        rs.getString("status"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("open_expires_at").toInstant(),
        approval == null ? null : approval.toInstant(),
        rs.getObject("payment_id", UUID.class),
        rs.getString("rule_codes"),
        rs.getInt("completed_count"),
        rs.getBigDecimal("completed_total"),
        rs.getObject("policy_id", UUID.class));
  }

  public record Policy(
      UUID id,
      long versionNumber,
      boolean enabled,
      BigDecimal maxPaymentAmount,
      int reviewPaymentCount,
      BigDecimal reviewPaymentTotal,
      String actorSubject,
      String changeReason,
      Instant createdAt) {}

  public record Decision(
      UUID policyId,
      boolean enabled,
      String outcome,
      String ruleCodes,
      int completedCount,
      BigDecimal completedTotal) {}

  private record Window(int count, BigDecimal total) {}

  public record ReviewCase(
      UUID id,
      UUID assessmentId,
      String status,
      Instant openExpiresAt,
      Instant approvalExpiresAt,
      UUID paymentId) {}

  public record ReviewResolution(RiskResult result, boolean approved) {}

  public record OperatorCase(
      UUID caseId,
      UUID payerOwnerId,
      String status,
      Instant createdAt,
      Instant openExpiresAt,
      Instant approvalExpiresAt,
      UUID paymentId,
      String ruleCodes,
      int completedCount,
      BigDecimal completedTotal,
      UUID policyId) {}

  public record ReviewAction(
      UUID actionId,
      String actorSubject,
      String action,
      String priorStatus,
      String newStatus,
      String reason,
      Instant createdAt) {}
}
