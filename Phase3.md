# Phase 3 — Reliable Merchant Payments, Refunds, and Event Delivery

> Historical design and verification record. See [current architecture](docs/architecture.md) for the integrated application.

**Status: implemented and verified on 2026-09-21.**

## 1. Goal

Build the first merchant-facing financial workflow without weakening the ledger: a payer can complete a USD payment to a merchant, that merchant can issue bounded full or partial refunds, and each committed payment/refund produces a durable event for reliable asynchronous delivery.

Phase 3 demonstrates two different kinds of correctness:

- the synchronous command is a balanced, atomic ledger operation; and
- its database fact and eventual Kafka event cannot silently diverge.

It remains a modular monolith. Kafka is an asynchronous propagation mechanism, never the source of truth for money.

## 2. Verified Starting Point

The implementation audit found Phase 1 and Phase 2 complete:

| Existing capability | Phase 3 use |
|---|---|
| Exact USD `Money`, active PERSON/MERCHANT owners, and one owned wallet per currency | Identifies payer and merchant wallets without introducing another balance model. |
| Immutable balanced journal with PostgreSQL constraints/triggers | Records payment and refund value movement. |
| Derived balances plus stable-order pessimistic account locks | Prevents a payer or merchant wallet from overspending under concurrent commands. |
| Completed, idempotent wallet transfers | Supplies the command/replay pattern, error shape, OpenAPI convention, and temporary caller-owner seam. |
| PostgreSQL/Testcontainers integration and MockMvc tests | Supplies the test approach for schema, financial, API, and concurrency behaviour. |

Phase 3 adds the `payment` and `reliability` modules, V6/V7 Flyway migrations, Kafka dependencies and Compose service, REST/OpenAPI endpoints, and focused unit/integration tests. The original planning decisions below are now the implemented contract.

## 3. Scope

### Included

- A synchronous payer-to-merchant USD payment command and immutable completed payment record.
- A small, explicit payment lifecycle derived from immutable payment/refund facts.
- Merchant-authorized full and partial refunds, each represented by a new compensating ledger journal and immutable refund record.
- Successful-result idempotency for payment and refund commands.
- A PostgreSQL transactional outbox written in the same transaction as each completed payment/refund.
- A leased, retrying outbox publisher that publishes versioned payment events to Kafka at least once.
- One concrete idempotent Kafka consumer that records durable event receipts, proving safe duplicate handling without performing another financial write.
- REST/OpenAPI endpoints for payment creation, payment lookup, and refunds, using the existing development-only owner-header seam.
- PostgreSQL/Testcontainers, Kafka/Testcontainers, MockMvc, rollback, and concurrency coverage.

### Explicitly deferred

- Real authentication, JWTs, merchant API keys, customer consent flows, and production authorization.
- Card/bank/external-provider authorization, asynchronous capture, payment attempts, holds, cancellation, or failed-payment persistence.
- Fees, payouts, settlement, chargebacks, disputes, FX, multi-currency, and payment methods.
- Transfer outbox backfill. Phase 3 emits events for new payment/refund facts only; it does not retrofit a new responsibility into completed Phase 2 transfers.
- Webhook delivery, notification delivery, dead-letter operations UI, and manual replay. Phase 4 consumes the published events for those external effects.
- Redis, distributed locks, a balance cache, event sourcing, a generic workflow engine, and microservice extraction.

## 4. Business Flow

### Payment command

```text
POST /api/v1/payments + payer owner header + idempotency key
  -> parse and validate DTO / canonical USD money
  -> claim or replay payer-scoped idempotency result
  -> verify payer wallet, merchant wallet, owners, and command ownership
  -> LedgerPostingService locks wallets in UUID order and checks live balances
  -> create balanced journal: DEBIT payer wallet / CREDIT merchant wallet
  -> persist immutable completed payment and payment.completed outbox event
  -> complete idempotency record
  -> commit one PostgreSQL transaction
  -> return the created or replayed payment view
```

### Refund command

```text
POST /api/v1/payments/{paymentId}/refunds + merchant owner header + idempotency key
  -> claim or replay merchant-scoped idempotency result
  -> lock the original payment row
  -> verify caller owns the original merchant wallet and remaining refundable amount
  -> LedgerPostingService locks wallets in UUID order and checks merchant funds
  -> create compensating journal: DEBIT merchant wallet / CREDIT payer wallet
  -> persist immutable completed refund and refund.completed outbox event
  -> complete idempotency record
  -> commit one PostgreSQL transaction
```

The outbox publisher runs after commit. It may publish an event more than once, but it must never lose a committed event. A consumer deduplicates by stable event ID before applying its local effect.

## 5. Key Engineering Decisions

| Decision | Choice | Why |
|---|---|---|
| Payment initiator | The payer owns the source wallet and initiates the payment; destination must be a MERCHANT wallet. | The current header can only model source-owner authority. Letting a merchant debit a payer before real authentication/consent would be a dangerous fiction. |
| Payment completion | Payment completes synchronously in the command transaction. | There is no external provider or authorization stage yet. Persisting artificial PENDING/FAILED rows would add misleading workflow state. |
| Lifecycle state | `COMPLETED`, `PARTIALLY_REFUNDED`, and `REFUNDED` are derived from immutable facts. | Refund totals remain authoritative in immutable refund rows; a mutable financial total/status cannot drift from history. |
| Financial records | Payments and refunds are immutable business records, each linked one-to-one to a ledger transaction. | The ledger remains accounting truth while concise records support workflow and API queries. |
| Refund technique | Create a new compensating journal; never mutate the original payment or entries. | Preserves Phase 1 auditability and correctly represents a reversal of value. |
| Idempotency | Separate payment/refund tables, scoped to the caller owner and linked by real foreign keys. | This preserves Phase 2 semantics without a polymorphic generic table that would weaken referential integrity. |
| Event reliability | PostgreSQL transactional outbox plus Kafka at-least-once publication. | A database transaction cannot atomically commit a broker send; the outbox makes committed work durable before asynchronous delivery. |
| Consumer safety | Persist `(consumer_name, event_id)` before one local audit/receipt effect. | Kafka can redeliver; uniqueness gives effectively-once local processing without pretending transport is exactly-once. |
| Concurrency | Reuse Phase 1 account locks; additionally lock one payment row before calculating a refund total. | Account locks protect wallet funds; the payment lock serializes competing partial refunds. |

## 6. Payment and Refund Model

### Payment

A completed payment is an immutable business fact:

| Field | Meaning |
|---|---|
| `id` | Public UUID. |
| `payer_wallet_account_id` | Active PERSON wallet debited for the original payment. |
| `merchant_wallet_account_id` | Active MERCHANT wallet credited for the original payment. |
| `amount`, `currency` | Exact positive USD amount copied for concise workflow queries. |
| `ledger_transaction_id` | Unique link to the authoritative original journal. |
| `completed_at` | UTC completion timestamp. |

The Phase 3 payment command permits PERSON -> MERCHANT only. Phase 2 transfers remain the correct mechanism for other owner-type combinations.

### Refund

An immutable completed refund refers to exactly one payment and records the inverse movement:

| Field | Meaning |
|---|---|
| `id`, `payment_id` | Public refund UUID and original payment reference. |
| `merchant_wallet_account_id`, `payer_wallet_account_id` | Copied original participants; the merchant is debited and payer credited. |
| `amount`, `currency` | Exact positive USD refund amount. |
| `ledger_transaction_id` | Unique link to the compensating journal. |
| `completed_at` | UTC completion timestamp. |

Copying wallet IDs and money is justified for direct payment/refund history queries. Ledger entries remain the accounting source of truth. The application is the sole writer and tests prove that each business fact corresponds to its journal.

### Lifecycle

`PaymentStatus` is calculated while reading a payment:

| Derived state | Condition | Permitted next state |
|---|---|---|
| `COMPLETED` | Sum of completed refunds is zero. | `PARTIALLY_REFUNDED` or `REFUNDED` |
| `PARTIALLY_REFUNDED` | Sum is greater than zero and less than original amount. | `PARTIALLY_REFUNDED` or `REFUNDED` |
| `REFUNDED` | Sum equals original amount. | Terminal |

`PENDING`, `FAILED`, `CANCELLED`, and a mutable `setStatus` API are deliberately absent. Validation or persistence failures roll back with no payment/refund fact. A later asynchronous provider flow may add durable intermediate states only when it has real business meaning.

## 7. Ledger Integration

Each payment and refund delegates exclusively to `LedgerPostingService`:

| Operation | Wallet entry 1 | Wallet entry 2 |
|---|---|---|
| Payment | payer LIABILITY wallet: `DEBIT` | merchant LIABILITY wallet: `CREDIT` |
| Refund | merchant LIABILITY wallet: `DEBIT` | payer LIABILITY wallet: `CREDIT` |

No controller, payment service, publisher, or Kafka consumer writes `LedgerEntry` records or a balance column directly. The existing posting service performs ordered locking, active account/owner checks, currency validation, non-negative wallet validation, immutable journal creation, and deferred PostgreSQL balance verification.

Payment/refund records, their journals, idempotency completion, and their outbox event share one REQUIRED transaction. A failure in any insert, journal posting, or event creation rolls back all of them.

## 8. Ownership and Command Boundaries

`X-LedgerX-Owner-Id` remains a **development-only, forgeable pre-auth seam**. It must remain prominent in OpenAPI and production documentation; Phase 3 does not make it safe for public deployment.

| Operation | Required caller relationship |
|---|---|
| Create payment | Caller owns the PERSON payer wallet. The destination must be an active wallet owned by an active MERCHANT. |
| Read payment | Caller owns either original participating wallet. An unrelated caller receives `404`. |
| Create refund | Caller owns the original MERCHANT wallet. A participating payer receives `403`; an unrelated caller receives `404`. |

The owner UUID stays out of JSON bodies. Application services receive a neutral `OwnerContext`, not an HTTP request. Because payments now share it with transfers, move `OwnerContext` out of `transfer.domain` into a small neutral pre-auth package during implementation; this is a cohesion fix, not an authentication feature.

## 9. Idempotency Model

Payments and refunds follow Phase 2's successful-result-only rule.

| Command | Scope | Canonical fingerprint | Success/retry behaviour |
|---|---|---|---|
| Payment | payer owner + `Idempotency-Key` | payer wallet, merchant wallet, canonical money | Same fingerprint replays the completed payment; a different fingerprint is `409`. |
| Refund | merchant owner + `Idempotency-Key` | payment ID, canonical money | Same fingerprint replays the completed refund; a different fingerprint is `409`. |

Each table uses PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` to claim a `PROCESSING` record. In its enclosing transaction, a success creates the financial fact and changes the record to `COMPLETED`; any validation, funds, journal, event, or persistence failure rolls back the claim. Failed responses are not cached.

`PROCESSING` after a conflicting insert maps to a retryable `409`. In the normal case PostgreSQL waits for the first transaction, then the duplicate command reads its durable completion and replays. Idempotency keys have no silent expiry in Phase 3; retention requires an explicit future policy.

## 10. Transaction and Concurrency Boundaries

Payment creation has the same boundary and lock protocol as a Phase 2 transfer. The ledger service locks both wallet accounts by ascending UUID immediately before balance validation and posting. Competing payer debits therefore serialize and cannot create a negative derived balance.

Refund creation adds an intentional first lock:

1. Claim/replay merchant-scoped idempotency in the outer transaction.
2. Lock the single payment row with `PESSIMISTIC_WRITE`.
3. Sum immutable completed refunds for that payment and reject an amount greater than the remaining amount.
4. Call `LedgerPostingService`, which locks the merchant and payer accounts in its existing stable UUID order.
5. Insert refund, idempotency completion, and the next payment-aggregate outbox event before commit.

Every refund writer follows this payment-then-account order. Payment creation never locks a payment row, and no other operation locks accounts then a payment row, so this introduces no cyclic lock order. PostgreSQL `READ COMMITTED`, explicit locks, and the existing `409` retryable concurrency mapping remain the model; there are no distributed locks or blind automatic retries.

## 11. Transactional Outbox and Kafka Publication

### Outbox fact

For each successful financial command, insert exactly one immutable event payload in the same database transaction:

| Event | Aggregate | Sequence | Payload minimum |
|---|---|---:|---|
| `payment.completed.v1` | payment ID | 1 | event ID, payment ID, payer/merchant wallet IDs, money, completion time, schema version |
| `refund.completed.v1` | original payment ID | 2+ | event ID, payment/refund IDs, money, completion time, schema version |

The payment row lock serializes refund creation and assigns a monotonically increasing event sequence. A unique `(aggregate_type, aggregate_id, sequence)` constraint preserves causal order. Payloads contain no headers, raw idempotency keys, credentials, or secrets.

### Publisher behaviour

The publisher must not hold a database transaction open while waiting for Kafka. It uses short transactions and a lease:

```text
claim eligible event with FOR UPDATE SKIP LOCKED
  -> mark IN_FLIGHT with lease token/until and increment attempts
  -> commit claim
  -> publish event to Kafka using payment ID as the message key
  -> mark PUBLISHED only if the lease token still matches
```

On a broker failure, the matching lease changes back to `PENDING` with an exponential, bounded `next_attempt_at`. A timed-out `IN_FLIGHT` lease is reclaimable. A process crash after Kafka accepts a record but before PostgreSQL records `PUBLISHED` causes a duplicate publication, never a lost event. The publisher only claims the earliest non-published sequence for an aggregate, preserving payment/refund order even with multiple publisher instances.

Kafka topic: `ledgerx.payment-events.v1`. The stable payment-ID key keeps an aggregate's records on one partition. Topic count, retention, retry limits, lease length, poll interval, and batch size are typed external configuration, not constants in services.

## 12. Idempotent Consumer

Phase 3 adds one narrow, real consumer pattern rather than inventing notifications before Phase 4:

- `PaymentEventAuditConsumer` validates known `v1` envelopes and writes a durable receipt.
- `processed_events` has primary key `(consumer_name, event_id)` plus event type, aggregate ID, payload SHA-256, and `processed_at`.
- The consumer inserts the receipt in its local PostgreSQL transaction before acknowledging successful processing. A duplicate event ID with the same hash is a successful no-op; the same ID with a different hash is treated as corrupted input and is not silently accepted.
- The consumer never creates ledger entries, changes a payment/refund, or issues another financial command.

This gives a concrete, testable downstream side effect and a reusable deduplication pattern for future webhook/notification consumers. It is intentionally at-least-once transport with effectively-once local business handling, not an unsupported claim of end-to-end exactly-once delivery.

## 13. REST API and Error Contract

All endpoints remain under `/api/v1`; IDs are UUIDs, timestamps are UTC ISO-8601 instants, and money is `{ "amount": "12.50", "currency": "USD" }`. DTOs, never JPA entities, cross HTTP.

| Endpoint | Purpose | Required headers | Success |
|---|---|---|---|
| `POST /payments` | Create/replay a payer-initiated merchant payment. | owner header, idempotency key | `201 Created`, or `200 OK` replay; `Location` points to the payment. |
| `GET /payments/{paymentId}` | Read payment fact plus derived refund state/amount. | owner header | `200 OK` for either participant. |
| `POST /payments/{paymentId}/refunds` | Create/replay a merchant full/partial refund. | owner header, idempotency key | `201 Created`, or `200 OK` replay. |

`PaymentResponse` contains original payment fields, derived `status`, `refundedMoney`, and `remainingRefundableMoney`. `RefundResponse` contains the immutable refund facts and its ledger transaction ID. A separate refund-history list is deferred until pagination and query needs are real.

Use the existing stable problem shape (`timestamp`, `status`, `code`, `message`, `path`, `details`) and refactor its transfer-specific package only enough to share it across the new API. Do not expose database error text.

| Situation | HTTP | Code |
|---|---:|---|
| Malformed JSON/UUID/header or unsupported currency | 400 | `MALFORMED_REQUEST` |
| Invalid money, wrong wallet type/owner type, inactive participant, same wallet, insufficient payer/merchant funds, or refund above remaining amount | 422 | `PAYMENT_NOT_PROCESSABLE` or `REFUND_NOT_PROCESSABLE` |
| Missing payment or caller unrelated to it | 404 | `PAYMENT_NOT_FOUND` |
| Payer attempts a known payment refund | 403 | `REFUND_NOT_AUTHORIZED` |
| Reused key with a different request, processing duplicate, or recognized database concurrency conflict | 409 | idempotency/concurrency-specific code |
| Unexpected server failure | 500 | `INTERNAL_ERROR` |

Swagger at `/swagger` and OpenAPI at `/api-docs` must include editable USD payment/refund examples, owner/idempotency fields, response examples, and the production warning. The existing production profile continues to disable public docs while the forgeable header exists.

## 14. Database Design

Create forward-only Flyway migrations after V5; do not alter Phase 1/2 migrations.

| Table | Responsibility | Core integrity |
|---|---|---|
| `payments` | Immutable original payment fact. | Composite wallet/currency FKs; distinct wallets; positive USD amount; unique journal link; indexes for payer/merchant completion history; UPDATE/DELETE rejection trigger. |
| `payment_idempotency` | Payer-scoped payment replay result. | Owner FK; `(owner_id, idempotency_key)` unique; SHA-256 check; unique payment link; `PROCESSING -> COMPLETED` trigger. |
| `refunds` | Immutable compensating refund fact. | Payment FK; copied participant/currency FKs; positive USD amount; unique journal link; payment/completion index; UPDATE/DELETE rejection trigger. |
| `refund_idempotency` | Merchant-scoped refund replay result. | Same state/fingerprint protections, with a refund FK. |
| `outbox_events` | Immutable event envelope/payload with mutable delivery metadata. | UUID event ID; aggregate type/ID/sequence uniqueness; valid event/status checks; JSONB payload; immutable business fields; constrained delivery-state transition trigger; eligible-event index. |
| `processed_events` | Per-consumer durable deduplication receipt. | `(consumer_name, event_id)` primary key; immutable event identity/hash; no financial foreign-key mutation. |

`outbox_events` stores `PENDING`, `IN_FLIGHT`, or `PUBLISHED`, `attempt_count`, `next_attempt_at`, `lease_token`, `lease_until`, `published_at`, and safely truncated failure metadata. Its event type, aggregate identity/sequence, occurrence time, schema version, and payload are immutable. Delivery metadata changes only through allowed transitions; outbox rows are retained rather than deleted in Phase 3.

No SQL constraint can prove that a refund's cumulative amount is at most its payment, or that copied business values match ledger entries. The service's payment lock, one controlled writer, immutable rows, and PostgreSQL integration tests establish those cross-row rules.

## 15. Java/Spring Component Design

| Area | Components | Owns / must not own |
|---|---|---|
| `com.ledgerx.payment.domain` | `Payment`, `Refund`, derived `PaymentStatus`, commands, canonical fingerprints, focused exceptions | Immutable business facts and lifecycle calculation; never direct entry persistence. |
| `com.ledgerx.payment.persistence` | Payment/refund repositories, payment-row lock query, refund-total query, concrete idempotency stores | Focused PostgreSQL persistence; no HTTP mapping or wallet balance calculation. |
| `com.ledgerx.payment.application` | `PaymentApplicationService`, `RefundApplicationService`, `PaymentQueryService` | Command orchestration, ownership checks, one transaction, and ledger/outbox delegation. |
| `com.ledgerx.reliability` | `OutboxEvent`, repository/claim store, `OutboxPublisher`, Kafka envelope/producer, `PaymentEventAuditConsumer` | Durable event dispatch/deduplication; never financial business rules or direct ledger writes. |
| `com.ledgerx.payment.api` | requests/responses/controller/OpenAPI annotations | Parsing, validation, mapping, `Location`, and thin HTTP behaviour. |
| neutral API/security seam | relocated `OwnerContext`, shared API problem model/exception advice | Boundary-only caller identity and errors; no trust in an HTTP owner value inside application code. |
| existing ledger/wallet modules | posting, balance, account/owner persistence | Retain financial posting/lifecycle authority. |

Add Spring Kafka and Kafka Testcontainers dependencies only with the reliability implementation. Add scheduler and Kafka properties through typed configuration. Do not add repository/service interfaces, a mapper framework, or a generic event bus without a demonstrated second use.

## 16. Phase 3 Invariants

| Rule | Primary enforcement |
|---|---|
| A payment is PERSON payer -> MERCHANT wallet, uses positive exact USD, and has distinct active participants with active owners. | DTO/domain validation, application metadata checks, Phase 1 lock-time validation, schema constraints, tests. |
| Only the payer owner creates a payment; only its merchant owner creates refunds; unrelated reads do not disclose payment existence. | `OwnerContext` application checks and API tests. |
| Every completed payment/refund has exactly one balanced immutable linked journal. | One transaction, unique journal FK, existing ledger trigger, immutable rows, tests. |
| No payment/refund command bypasses `LedgerPostingService` or writes wallet balances directly. | Component boundaries and integration tests. |
| Refund total is never greater than original payment amount. | Payment `PESSIMISTIC_WRITE` lock, refund-total query, validation, concurrency test. |
| A refund cannot make the merchant wallet negative. | Existing ledger account locks and prospective balance check. |
| Completed financial facts and their ledger links cannot mutate or delete. | Encapsulated models, restrictive FKs, PostgreSQL triggers, tests. |
| Same scoped idempotency key/fingerprint creates one financial effect; a changed payload conflicts; failures leave no idempotency claim. | PostgreSQL uniqueness/claim logic and transaction tests. |
| A committed payment/refund has one durable immutable outbox event in the same transaction. | Outbox insert in command transaction, constraints, rollback tests. |
| A committed outbox event is eventually retried until published; a crash may duplicate but never silently drops it. | Lease/retry publisher, state transitions, publisher tests. |
| A consumer applies its receipt effect once per event ID and never causes money movement. | `(consumer_name, event_id)` PK, transactional consumer, duplicate-delivery tests. |

All Phase 1/2 ledger, ownership, idempotency, atomicity, and concurrency invariants continue to apply.

## 17. Testing Strategy

| Level | Required evidence |
|---|---|
| Unit | Derived payment status; money/fingerprint canonicalization; state boundaries; event envelope validation; retry-delay calculation; no invalid refund amount. |
| Application | Payer/merchant authorization; payment/refund orchestration delegates to ledger/outbox; replay/conflict behaviour; incorrect wallet owner type; refund remaining calculation. |
| PostgreSQL Testcontainers | V6+ schema checks, immutable payment/refund/event fields, links to journals, idempotency transitions, outbox transition guards, command/event atomic rollback, and refund totals. |
| Concurrency | Competing payments spend one payer safely; simultaneous partial refunds cannot exceed the payment; merchant refund funds remain non-negative; duplicate payment/refund keys create one effect. |
| MockMvc/OpenAPI | `201` versus replay `200`, `Location`, participant visibility, refund authorization, problem shapes, serialised money/status, and generated docs/examples. |
| Kafka Testcontainers | Publisher sends committed events, publishes in payment/refund sequence, retries broker failure, reclaims an expired lease, and consumer processes duplicate event delivery once. |

Critical proof cases:

- A `25.00 USD` payment reduces payer balance by `25.00`, increases merchant balance by `25.00`, creates one journal, one payment, and one pending outbox event.
- A `10.00` then `15.00` refund derives `PARTIALLY_REFUNDED` then `REFUNDED`; a further cent is rejected with no extra journal/event.
- A forced payment/refund/outbox persistence failure leaves no journal, business fact, completed idempotency result, or event.
- Kafka outage does not roll back a committed payment; its outbox row remains retryable. A crash-after-send simulation permits a duplicate event, and the consumer writes one receipt.
- The full Maven `clean verify` run passes with PostgreSQL and Kafka Testcontainers available.

## 18. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Merchant-initiated debit looks plausible without customer authentication. | Restrict payment creation to the payer-owned source wallet and retain the explicit development-only warning. |
| A mutable payment refund total drifts from refunds. | Derive total and status from immutable refund rows while holding the payment lock for new refunds. |
| Partial refunds race past the payment amount. | One locked payment row serializes total calculation and refund insertion. |
| Database commit succeeds but Kafka is unavailable. | Commit immutable outbox event with the financial facts; publisher retries asynchronously. |
| Publisher crashes after send. | Use at-least-once semantics and a consumer deduplication key; do not claim exactly-once broker delivery. |
| Events for a payment publish out of order. | Stable payment key, aggregate sequences, and earliest-unpublished claim rule. |
| Outbox delivery metadata becomes untrustworthy. | Constrained status transitions, matching lease tokens, immutable payload fields, and retention. |
| Kafka/consumer complexity obscures financial correctness. | Keep one topic, one small consumer, no consumer-led ledger writes, and integration tests around explicit failure modes. |
| Stale project documentation misstates progress. | Update roadmap/backlog/README/architecture in a separate, reviewed documentation commit when implementation is complete. |

## 19. Implementation Sequence

| Milestone | Goal | Acceptance result |
|---|---|---|
| 3.1 | Add payment/refund domain types, neutralize shared owner/error seams, and create V6 payment/refund/idempotency schema with immutability protections. | Unit and PostgreSQL tests prove valid facts and database constraints. |
| 3.2 | Implement synchronous payer-to-merchant payment orchestration using the existing ledger service and payer-scoped idempotency. | Atomic payment/journal/idempotency behaviour and competing-debit tests pass. |
| 3.3 | Implement merchant refunds, derived status, payment-row locking, and merchant-scoped idempotency. | Partial/full/refund-limit and concurrent-refund tests pass. |
| 3.4 | Add V7 outbox/processed-event schema and insert versioned outbox facts atomically with payments/refunds. | Forced failures prove no committed financial fact lacks its event. |
| 3.5 | Add Kafka publisher, typed settings, Compose/Testcontainers Kafka, lease/retry logic, and the idempotent receipt consumer. | Success, retry, duplicate, lease-recovery, and ordered-event tests pass. |
| 3.6 | Add REST/OpenAPI endpoints, shared error mappings, editable Swagger examples, documentation updates, and full verification. | API contract tests and `clean verify` pass; Git diff contains only scoped work. |

Each milestone should be a small logical commit (schema/domain, payment, refunds, outbox, Kafka, API/docs/tests), with no generated artifacts or unrelated local changes included.

## 20. Definition of Done

Phase 3 is complete when:

- a payer-owned PERSON wallet can make an idempotent USD payment to an active MERCHANT wallet;
- a merchant can make idempotent bounded partial/full refunds, represented by compensating immutable journals;
- payment/refund views derive the documented lifecycle correctly without a mutable financial total;
- all payment/refund financial state, idempotency completion, and exactly one corresponding outbox event commit or roll back together;
- the publisher reliably retries to Kafka with causal per-payment ordering and at-least-once delivery semantics;
- the consumer proves duplicate event safety with durable per-consumer deduplication and never moves money;
- database triggers/constraints, ordered locks, error contracts, Swagger, and focused unit/integration/concurrency tests substantiate every invariant;
- `mvn clean verify` passes with Docker/Testcontainers, and configuration/docs contain no secrets or stale claims; and
- no deferred authentication, provider processing, fees, webhooks, or unrelated infrastructure is smuggled into the change set.

## 21. What Phase 3 Enables

LedgerX will have an auditable merchant payment/refund workflow whose synchronous financial truth and asynchronous event stream are connected safely. Phase 4 can consume `payment.completed.v1` and `refund.completed.v1` for signed webhook delivery, observability, and reconciliation without redesigning payment, refund, ledger, or duplicate-event rules.

---

## Implementation and verification record

Phase 3 is implemented as the documented modular-monolith vertical slice. Payment and refund facts, their balanced ledger journals, idempotency completions, and one immutable outbox event are committed atomically. The publisher provides leased, ordered, at-least-once Kafka delivery; the audit consumer provides durable duplicate-event safety without making financial writes.

The completed verification suite covers payment/refund creation and replay, financial atomicity and rollback, partial/full/refund-limit state derivation, authorization/privacy, concurrent refunds, record immutability, OpenAPI generation, Kafka publication, and duplicate consumer delivery. On 2026-09-21, `mvnw.cmd clean verify` passed **17 unit tests** and **31 PostgreSQL/Kafka Testcontainers integration tests** with zero failures, errors, or skips.
