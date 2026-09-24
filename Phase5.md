# Phase 5 — Payment Risk Decisions and Review

**Status: implementation in progress; PostgreSQL integration verification pending.** This brief is based on the repository state on 2026-09-25. Phase 4 was verified on 2026-09-23. The browser workbench, owner activity, operator APIs, and local demo funding were present in the working tree when Phase 5 began.

## 1. Goal

Add a small, explainable risk control to payer-authorized merchant payments. An operator can activate versioned USD rules, a payment attempt receives a durable `ALLOW`, `REVIEW`, or `BLOCK` decision, and a reviewed payer can complete the same request only after an operator approves that specific case. Every decision is auditable. A review or block moves no money.

This phase extends the existing payment flow and operator workbench. It does not create a second payment engine or let an operator spend from a payer's wallet.

## 2. Verified Starting Point and Dependencies

| Existing capability | Phase 5 use |
|---|---|
| Immutable USD ledger, derived wallet balances, and ordered account locks | Approved payments still post through `LedgerPostingService`; no risk component writes journal entries. |
| Payer-owned `POST /api/v1/payments` with owner-scoped idempotency | Risk outcomes must become replayable results under the existing `(owner_id, idempotency_key)` identity. |
| Merchant-authorized refunds, payment outbox, Kafka, and signed webhooks | Only a completed payment creates the existing `payment.completed.v1` event. Risk cases are not payment or webhook events. |
| Keycloak JWT owner context and `ledgerx-operator` role in production | Payers see their own cases; operators manage policy and review cases without acquiring payer authority. |
| Phase 4 reconciliation and operational metrics | Missing decision evidence for new payments can be detected; queue counts can be exposed without sensitive rule inputs. |
| Browser workbench and operator screens currently in the working tree | Add payer decision status and operator review controls after those changes are verified and landed. |

The current `payment_idempotency` schema permits only `PROCESSING -> COMPLETED`, and `payments` rows are immutable. The implementation must add forward-only migrations for review/block outcomes and audit evidence; it cannot make existing posted payments mutable. Historical payments predate risk evaluation and need no fabricated decisions.

## 3. Scope

### Included

- Operator-managed, versioned risk policy with a hard per-payment amount limit and rolling 24-hour payer payment count and amount thresholds. The active policy is global for this first slice; thresholds are exact USD decimals.
- Synchronous risk evaluation on new merchant payment attempts with deterministic `BLOCK > REVIEW > ALLOW` precedence and a recorded policy version, matched rule codes, and evaluation time.
- Durable, payer-visible review cases with operator approve/decline actions and append-only action history. Open cases expire after seven days; approval authorizes one exact payer retry for at most 24 hours; it does not post a payment.
- Durable block outcomes, owner-scoped idempotent replays, and clear HTTP responses for review, block, and completion.
- A bounded review queue and policy controls in the existing operator workbench, plus payer-facing status in the existing payment view.
- Forward-only PostgreSQL migration, OpenAPI and configuration updates, focused metrics and reconciliation evidence, and integration/concurrency/security tests.

### Deferred

- Merchant-defined rules, machine-learning scoring, third-party fraud services, identity/KYC checks, card data, and automatic blacklists.
- Holds, reservations, delayed capture, operator-created payments, and risk checks on transfers or refunds. A review does not reserve balance; balance and account state are checked again when the payer retries.
- Merchant API keys, Redis rate limiting, provider simulation, payouts, FX, and new currencies. Each has a separate security or accounting contract and should not enter this slice merely because the roadmap lists it as a candidate.
- New Kafka/webhook event types for attempted, blocked, or reviewed payments. Phase 5 keeps the completed payment/refund external contract stable.
- Bulk historical rescoring. Old completed payments remain valid facts without a Phase 5 assessment.

## 4. Risk Rules and Decision Contract

| Rule | Input | Outcome when exceeded | Why |
|---|---|---|---|
| Maximum single payment | Requested USD amount | `BLOCK` | A simple hard ceiling prevents an unusually large new payment. |
| Rolling completed payment count | Payer's completed payments in `[now - 24h, now]` | `REVIEW` | Shows velocity checks without an external scoring system. |
| Rolling completed payment total | Sum of those payments plus requested amount | `REVIEW` | Limits sudden increases in committed spending. |

An operator creates an immutable policy version and activates it atomically. Exactly one version is active. Store the actor, activation time, thresholds, and a short change reason. Start with a documented permissive policy and an explicit enablement setting so rollout cannot unexpectedly block existing users. A policy edit creates a new version; past assessments keep the original version ID and rule codes.

Use `Money` and PostgreSQL `NUMERIC(19,2)` for amounts. An exact threshold match is allowed; a value above it matches the rule. The rolling window uses database time or an injected `Clock` consistently and counts only completed payments, including payments that resulted from a prior approval. An empty history counts as zero. Limit active review cases per payer to a small configured maximum; exceeding it produces a durable `BLOCK` outcome to keep the queue bounded.

The public response includes a decision ID, outcome, case ID when relevant, and a safe short reason code. It does not disclose thresholds, other owners, internal scoring inputs, or operator notes. Operators can inspect the matched rule codes and bounded supporting totals. Neither logs nor metrics include owner IDs, idempotency keys, or amounts as metric labels.

## 5. Payment and Review Flow

1. Resolve the caller as the payer using the existing profile-specific identity boundary. Validate request shape, owner and wallet relationships, currency, and the existing idempotency fingerprint.
2. Claim or lock the payer's `(owner_id, idempotency_key)` record. A reused key with a different fingerprint remains `409`; an exact replay returns the durable outcome, including a pending review or block.
3. Serialize risk checks for the payer with a PostgreSQL advisory transaction lock before reading the rolling payment window. Keep this separate from ledger account/owner row locks to avoid reversing the ledger's existing lock order. Read the active immutable policy version and evaluate all rules in one transaction.
4. On `ALLOW`, call the existing ledger posting path and save the payment, risk assessment, outbox event, and idempotency completion in the same PostgreSQL transaction. Return the existing payment response (`201` on creation, `200` on replay).
5. On `REVIEW`, save the assessment, review case, and idempotency state together. Return `202 Accepted` with a case location. No payment row, journal, outbox event, Kafka message, or webhook delivery is created.
6. On `BLOCK`, save the assessment and terminal idempotency result together. Return a stable `422` risk response. No financial or external event is created. A new key can be assessed under the then-active policy, but the blocked key never changes its result.
7. An operator approves or declines an open case under a row lock. Each action records the operator's JWT subject, time, reason, and prior/new state. Duplicate actions replay the current case; conflicting actions return `409`. Decline is terminal. An undecided case expires after seven days; an approval expires 24 hours after the operator's decision unless consumed.
8. After approval, only the original payer may repeat the **same payload and idempotency key**. Lock the idempotency row and case in a fixed order, confirm its fingerprint and unused approval, and recheck current owner/account status, hard block rules, and available balance. A newly matched hard block appends a `BLOCK` assessment and terminally blocks that key without posting. Otherwise, post once through the existing payment path, append a new `ALLOW` assessment linked to the original review, and atomically mark the approval consumed. A failed posting rolls back consumption, so the payer can safely retry until expiry. A concurrent retry observes either the open attempt or the completed payment.

Approval exempts that single matching attempt from the original review thresholds during its validity window; it never bypasses a current hard block, ownership check, inactive wallet, or insufficient funds. Expired and declined cases cannot post with their original key and return stable terminal responses. The payer must start a new request with a new key if they still want to pay.

## 6. Data Model and Database Migration

Use the next available Flyway version **after** the workbench's V11/V12 migrations are finalized. Suggested tables and changes:

| Record | Required constraints and role |
|---|---|
| `risk_policy_versions` | Immutable version ID, exact USD thresholds, enabled flag, creator/activation metadata, and reason; a single active-version pointer is changed transactionally. |
| `risk_assessments` | Append-only decision ID, payer owner/wallet, merchant wallet, fingerprint, policy version, matched rule codes, bounded input snapshot, outcome, evaluation time, and optional completed payment ID. Unique payment ID when present. |
| `risk_review_cases` | One case per initial `REVIEW` assessment, payer owner, request fingerprint, status (`OPEN`, `APPROVED`, `DECLINED`, `EXPIRED`, `POLICY_BLOCKED`, `CONSUMED`), open/approval expiry times, optional completed payment ID, and optimistic version or row-lock transition guard. |
| `risk_review_actions` | Append-only operator decision/action history with actor subject, reason, timestamp, and case ID. No deletion or update. |
| `payment_idempotency` | Add references and state constraints for durable `REVIEW` and `BLOCKED` results, plus guarded `REVIEW -> COMPLETED` and `REVIEW -> BLOCKED` transitions after an approved retry. Preserve every existing completed row and its `PROCESSING -> COMPLETED` behavior. |

Foreign keys, uniqueness, check constraints, and transition guards must prevent a case being consumed twice and prevent duplicate risk links for one new payment. The payment service must commit its idempotency completion and risk evidence atomically with the payment. Index the payer/time fields used for the rolling window and the case status/creation time used by the operator queue. Do not change the immutability of ledger, payment, refund, or outbox facts. Keep any risk evidence attached to a newly completed payment in the same commit.

## 7. API and Workbench Contract

| Route | Caller | Behavior |
|---|---|---|
| Existing `POST /api/v1/payments` | Payer owner | Retain current success responses; add `202 REVIEW_REQUIRED` and `422 RISK_BLOCKED` bodies with stable decision/case references. Same-key retries replay an unchanged outcome; an approved review advances through the explicit payer retry. |
| `GET /api/v1/payment-risk-cases/{caseId}` | Original payer | Return the case status and expiry only. Unrelated owners receive `404`. |
| `GET /api/v1/operations/risk/policy` and `POST /api/v1/operations/risk/policies` | Operator | Read the active version; create and activate a validated version with an idempotency key and expected-version guard. |
| `GET /api/v1/operations/risk/cases` | Operator | Bounded, paginated queue ordered by creation time and case ID. Filter by status; never return unbounded history. |
| `POST /api/v1/operations/risk/cases/{caseId}/approve` and `/decline` | Operator | Idempotent status transitions with required reason and explicit conflict responses. These routes never post money. |

Production routes use JWT owner identity and the existing `ledgerx-operator` role. The local/test owner header stays development-only and cannot grant operator powers in production. The payer workbench shows `Needs review`, `Blocked`, `Approved — retry payment`, and completed states without implying that reviewed funds have moved. The operator view shows the queue, rule version, safe supporting evidence, decision history, and explicit approve/decline actions. Reuse the workbench's existing request-key recovery behavior so an uncertain response never creates another payment.

## 8. Invariants and Failure Handling

| Invariant | Enforcement and proof |
|---|---|
| A `REVIEW` or `BLOCK` decision has no payment, journal, outbox event, or webhook work. | One transaction boundary; PostgreSQL/API integration tests. |
| One payer key and fingerprint produces at most one completed payment, even across review approval and concurrent retries. | Unique owner/key, row lock, guarded transitions, concurrent integration test. |
| Operator approval cannot move money or impersonate a payer. | Role/owner separation and production JWT tests. |
| Velocity checks do not race through the threshold for one payer. | Payer-scoped lock and concurrent different-key payment test. |
| Rule edits do not rewrite old decisions or retroactively change blocked-key replay. | Versioned immutable policies/assessments and replay tests. |
| A declined, expired, or already consumed approval cannot authorize posting. | State transition guard and fixed-clock expiry/concurrency tests. |
| An allowed payment and its risk evidence, journal, idempotency result, and outbox event commit or roll back together. | Failure injection and PostgreSQL integration tests. |
| Existing transfers, refunds, completed payments, webhooks, and historic records retain their current contracts. | Regression tests and a forward-only migration test against existing data. |

If policy storage is unavailable, fail the new payment command closed with a retryable service error; never silently skip enabled risk rules. If no active policy exists while risk is enabled, startup/health should report misconfiguration. Existing read APIs and already committed financial facts remain readable. Review queue delivery is database-backed; Kafka availability does not decide whether a payment may be posted.

## 9. Operational Evidence

Expose bounded counts for `ALLOW`, `REVIEW`, and `BLOCK`, open/aged review cases, approval expiry, and risk evaluation errors. Keep owner and policy IDs out of metric tags. Structured logs use decision/case correlation IDs and rule codes but omit raw request payloads and operator notes. Extend reconciliation to detect newly completed payments missing Phase 5 assessment evidence, while explicitly excluding payments completed before activation. Findings are read-only; no reconciliation job changes money or risk outcomes.

Document policy activation, rollback to a prior immutable version, review queue triage, and the difference between approving a case and completing a payment. Deployment should start with conservative thresholds and monitor decision counts before tightening them.

## 10. Implementation Sequence

| Milestone | Deliverable | Acceptance signal |
|---|---|---|
| 5.0 | Verify and land the current workbench/API changes; confirm Phase 4 baseline and migration numbering. | `mvnw.cmd clean verify` and `npm run check:ui` pass before risk changes begin. |
| 5.1 | Define rule/decision semantics, API schemas, policy configuration, and a forward-only migration. | Existing payment/idempotency data migrates unchanged; database guards reject invalid transitions. |
| 5.2 | Implement deterministic evaluator and payer-scoped serialization; integrate `ALLOW`, `REVIEW`, and `BLOCK` into payment idempotency. | Same-key replay and concurrent different-key threshold tests pass. |
| 5.3 | Add operator policy activation and review decisions, payer case reads, and expiry behavior. | Production security and transition tests prove role separation and no operator money movement. |
| 5.4 | Extend payer/operator workbench views, OpenAPI, metrics, and reconciliation. | A browser demo shows review, approval, exact-key retry, one completed payment, and one completed-payment webhook event. |
| 5.5 | Update README, architecture, roadmap/backlog, deployment guide, and run full verification. | Claims match code; Maven and UI checks pass with no regression. |

## 11. Definition of Done

Phase 5 is complete only when a payer can create an allowed payment, receive a durable blocked result, or receive a durable review case; an operator can activate a versioned policy and decide a review case; the original payer can complete an approved case exactly once using the same request and key; and all negative outcomes leave ledger/outbox/webhook state unchanged. PostgreSQL constraints, concurrency and production-authorization tests must prove those claims. The workbench and API docs must accurately show the new states, and full Maven plus UI verification must pass.

Until those checks pass, this document is a plan. The presence of a route, table, or UI panel alone does not make Phase 5 implemented.
