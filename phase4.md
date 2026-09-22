# Phase 4 — Signed Webhook Delivery and Operational Integrity

**Status: planned.**

## 1. Goal

Turn LedgerX's committed payment and refund events into a safe, observable external contract without
weakening the ledger or treating delivery as part of financial completion. A merchant can register a
signed HTTPS webhook endpoint, receive payment/refund notifications with at-least-once semantics,
inspect delivery history, and manually recover a terminal delivery failure. The application can also
surface delivery/backlog health and record integrity findings without automatically changing money.

Phase 4 demonstrates a third kind of correctness alongside the earlier phases:

- financial facts are immutable and atomically committed (Phases 1–3);
- their internal events are reliably published at least once (Phase 3); and
- effects outside LedgerX are traceable, retryable, deduplicable by the receiver, and never allowed to
  rewrite financial truth (this phase).

LedgerX remains a modular monolith. PostgreSQL remains authoritative for money and for the durable
webhook work queue. Kafka remains an internal propagation mechanism; a merchant webhook is not a
Kafka consumer and must not be considered a financial acknowledgement.

## 2. Verified Starting Point

The implementation audit found the following already present and usable by this phase:

| Existing capability | Phase 4 use |
|---|---|
| Immutable USD ledger, payments, and compensating refunds | Webhooks report completed facts only; they never create, update, or compensate ledger entries. |
| Payment/refund outbox events | `payment.completed.v1` and `refund.completed.v1` supply stable event IDs, payment aggregate IDs, sequences, occurrence times, and versioned data. |
| Kafka publisher with leases, retries, and payment ordering | A Kafka record may be delivered more than once, so webhook enqueueing must be idempotent. |
| `processed_events` receipt pattern | Supplies the durable consumer-deduplication contract, but the webhook enqueuer must insert its receipt and all delivery work in one transaction. |
| Keycloak JWT production owner context and local/test owner-header seam | Webhook configuration and delivery history can be restricted to the owning active merchant in every profile. |
| PostgreSQL/Flyway/Testcontainers/MockMvc/Kafka tests | Supplies the schema, failure, concurrency, and API verification approach. |
| Actuator health probes and containerized deployment | Supplies the narrow operational boundary for health and metrics. |

Two planning discrepancies are resolved deliberately:

- Phase 3 already implemented full and partial refunds, despite an older `PROJECT_CONTEXT.txt` ordering
  that placed refunds in Phase 4. Refund work is **not** repeated here.
- `docs/architecture.md` names a Webhooks module before it exists. It must not describe it as current
  implementation until this phase's code and verification have landed.

## 3. Scope

### Included

- Merchant-owned registration, listing, disablement, signing-secret rotation, and bounded retention of
  webhook endpoints.
- A dedicated idempotent Kafka consumer that turns completed payment/refund events into durable
  per-endpoint delivery work.
- A leased HTTP dispatcher with HMAC-SHA-256 signatures, a timeout, retry classification, capped
  exponential backoff with bounded jitter, delivery attempts, and an explicit terminal failure state.
- Merchant-visible delivery history and a controlled manual replay of a terminal delivery through the
  normal dispatcher.
- A stable, minimal merchant webhook payload contract that does not expose raw internal outbox data or
  financial secrets.
- Delivery/outbox/reconciliation metrics, correlation-friendly structured logs, and health details that
  distinguish application availability from a merchant endpoint failure.
- A scheduled, read-only reconciliation run that records discrepancies between immutable financial
  facts, their journals, their outbox events, and their delivery work.
- V8/V9 forward-only Flyway migrations, typed configuration, local/test/prod configuration changes,
  Docker/CI documentation, OpenAPI examples, and focused unit, integration, Kafka, HTTP, and
  concurrency tests.

### Explicitly deferred

- Redis, distributed locks, a balance cache, general API rate limiting, and merchant API keys. Redis is
  useful only after a concrete throughput or cross-instance abuse-control need is measured; PostgreSQL
  leases already coordinate this phase's workers.
- Generic notifications, email/SMS, arbitrary event routing, a workflow engine, and a generic event bus.
- Customer-facing webhook endpoints, per-event filtering expressions, event-schema negotiation, and
  automatic historical backfill. New endpoints receive eligible events committed after activation.
- Payment-provider authorization/capture, fees, payouts, disputes, chargebacks, FX, and multi-currency.
- An operator UI, automatic data repair, automatic financial compensation, dead-letter Kafka topics,
  retention deletion jobs, and full distributed tracing/dashboard infrastructure. This phase records
  enough durable evidence and metrics for those decisions to be made honestly later.
- A claim of exactly-once external delivery. The receiver must deduplicate by the event ID.

## 4. External Event and Webhook Contract

### Source events

Only these already-committed internal events are eligible:

| Internal event | Merchant webhook type | Recipient | Meaning |
|---|---|---|---|
| `payment.completed.v1` | `payment.completed.v1` | Owner of the payment's merchant wallet | A completed payment immutable fact exists. |
| `refund.completed.v1` | `refund.completed.v1` | Owner of the original payment's merchant wallet | A completed compensating refund immutable fact exists. |

The consumer resolves the merchant owner from the event's immutable `merchantWalletId`. It does not
trust a merchant owner ID supplied by HTTP, and it does not create a delivery for a payer, unrelated
wallet, inactive endpoint, or unknown event type.

Do **not** POST `PaymentEventEnvelope` or raw `outbox_events.payload` to the public internet. Those
payloads are internal integration contracts and include internal wallet/ledger identifiers that a
merchant does not need. Phase 4 projects each event into a distinct public JSON body and stores the
exact UTF-8 bytes once, before any send:

```json
{
  "id": "0d96656d-47c8-4ad7-9719-01cd6c1c8c05",
  "type": "payment.completed.v1",
  "apiVersion": "v1",
  "occurredAt": "2026-09-22T10:15:30Z",
  "data": {
    "paymentId": "3a6e32a0-7024-4fad-8ac8-cdd0d607e8e1",
    "amount": "25.00",
    "currency": "USD"
  }
}
```

A refund body has the same envelope and contains `paymentId`, `refundId`, `amount`, and `currency`.
No payload contains a payer wallet ID, raw idempotency key, database error, signing secret, bearer
token, or ledger transaction ID. The event ID is the stable receiver deduplication key; a delivery ID
identifies one LedgerX delivery instruction and is diagnostic only.

Public payload bytes and their SHA-256 hash are immutable once queued. A later Java DTO refactor must
not silently change `v1`; a materially different public representation requires a new type/version.

### HMAC signing

Each endpoint has an independent high-entropy signing secret. The merchant supplies it over an
authenticated TLS request; LedgerX validates a minimum entropy/length policy, encrypts it at rest with
an externally configured application key, and never returns, logs, serializes, or includes it in an
exception after receipt. The encryption key is a required secret in production and is absent from
tracked configuration and Docker image layers.

Each HTTP attempt sends these headers:

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `User-Agent` | `LedgerX-Webhooks/1.0` |
| `LedgerX-Event-Id` | Stable public event UUID. |
| `LedgerX-Event-Type` | Public webhook type, for example `payment.completed.v1`. |
| `LedgerX-Delivery-Id` | Stable delivery UUID. |
| `LedgerX-Timestamp` | UTC Unix epoch seconds chosen for this attempt. |
| `LedgerX-Signature` | `v1=<lowercase-hex-hmac>` |

The signature is `HMAC-SHA-256(secret, timestamp + "." + rawBodyUtf8)`. The documented receiver
procedure is to reject stale timestamps within its own tolerance, calculate the HMAC over the exact
raw request body, compare in constant time, then deduplicate by `LedgerX-Event-Id`. Retried or manually
replayed deliveries may have new timestamps and signatures but retain the same body and event ID.

Secret rotation replaces the active encrypted secret only after the caller proves merchant ownership.
Existing attempts retain their recorded secret version and signature metadata for audit, while future
attempts use the new secret. A receiver must tolerate a short operational overlap during its own secret
deployment; dual-secret delivery is intentionally not introduced without an actual integration need.

### Endpoint safety

Webhook endpoints are a server-side request forgery boundary. Production registration accepts an
absolute `https` URL only, rejects credentials, fragments, malformed hosts, and unsafe redirect
behaviour, and the dispatcher never follows redirects. DNS/IP validation is necessary but not enough;
production deployment must also enforce outbound network policy that denies loopback, link-local,
private, and instance-metadata ranges after resolution. Local/test can use an explicit test-only
allowlist for a mock receiver, never a production bypass.

Endpoint URLs, request bodies, response bodies, authorization headers, and secrets are never emitted
verbatim in application logs or metrics. Persist only bounded, sanitized diagnostics such as HTTP
status, elapsed time, error category, and a truncated safe failure message.

## 5. Business Flow

### Configuration flow

```text
merchant JWT/local owner context + endpoint URL + event types + signing secret
  -> resolve active MERCHANT owner
  -> validate URL, event subset, secret policy, and endpoint-count limit
  -> encrypt signing secret and persist endpoint/configuration audit data
  -> return redacted endpoint representation
```

`POST /api/v1/webhook-endpoints` requires an `Idempotency-Key`. A retry with the same merchant scope,
key, and canonical configuration returns the original redacted endpoint; a changed request conflicts.
This avoids accidental duplicate subscriptions and duplicate external effects. Rotation and disablement
are explicit endpoint operations; they must use optimistic/row-lock protection so concurrent updates do
not re-enable an endpoint or replace a newer secret.

An endpoint is active only after successful registration. Disabling it prevents future enqueueing and
cancels any unsent deliveries; historical endpoint and attempt rows remain available to the owner.
There is no physical deletion in this phase.

### Kafka-to-delivery flow

```text
published payment/refund Kafka record
  -> parse and validate known v1 internal envelope
  -> resolve merchant owner from immutable merchant wallet
  -> one PostgreSQL transaction
       ├─ record webhook-consumer receipt for event ID/payload hash
       └─ insert one immutable public delivery instruction per active eligible endpoint
  -> commit and acknowledge Kafka record
```

The receipt and all delivery inserts are one transaction. If delivery work cannot be created, the
receipt rolls back and Kafka can redeliver. If the record is delivered again after commit, the same event
ID/hash is a no-op; an event ID with a different payload hash is rejected as corrupted input. A unique
`(webhook_endpoint_id, event_id)` constraint independently protects against duplicate instructions.

The public payload projection must be deterministic. The stored delivery carries the source payment
aggregate ID and sequence, allowing the dispatcher to preserve order for one payment at one endpoint.
An un-delivered earlier event blocks a later event for that endpoint/payment; a terminal failure is
visible and requires deliberate manual replay rather than silently delivering a refund before the
payment notification.

### HTTP delivery flow

```text
claim eligible delivery with FOR UPDATE SKIP LOCKED + lease token
  -> commit short claim transaction
  -> sign immutable stored body and POST with a finite timeout, no redirects
  -> append immutable delivery-attempt record
  -> matching lease marks DELIVERED, reschedules PENDING, or marks DEAD
```

No database transaction is held while waiting for a merchant server. A crash or timeout after the remote
server accepts the request but before LedgerX records success can cause another attempt. That is the
correct at-least-once trade-off; receivers use `LedgerX-Event-Id` to make their local effect idempotent.

`2xx` responses complete delivery. Network errors, timeouts, `408`, `425`, `429`, and `5xx` are
retryable; `Retry-After` is honored only within the configured maximum. Other `3xx`/`4xx` responses are
terminal because redirects are not followed. Retry delay is capped exponential backoff plus bounded
jitter, with time, jitter, and the HTTP transport injected for deterministic tests. Once the configured
attempt limit is reached, status becomes `DEAD`; no background worker silently retries it forever.

An expired `IN_FLIGHT` lease becomes eligible again and records that recovery reason. Lease ownership is
checked for every success/failure update so a slow worker cannot overwrite a newer worker's result.

### Manual replay flow

```text
merchant owner requests replay for one owned DEAD delivery
  -> lock delivery and endpoint
  -> reject disabled endpoint, in-flight delivery, or non-terminal delivery
  -> transition DEAD -> PENDING with an explicit replay audit marker
  -> normal dispatcher signs and sends the original immutable public body
```

Manual replay does not recreate a payment, refund, journal, outbox event, or public event ID. It changes
only delivery workflow metadata and appends another delivery-attempt history row. It is intentionally
not a broad bulk-replay or repair tool.

## 6. Data Model and Database Design

Create forward-only migrations after V7; do not edit completed migrations.

| Table | Responsibility | Core integrity |
|---|---|---|
| `webhook_endpoints` | Merchant-owned endpoint configuration and encrypted active signing secret. | Owner FK; normalized URL; active/disabled state; safe event-type subset; secret ciphertext/key version; endpoint limit enforced by service; no plaintext secret. |
| `webhook_endpoint_idempotency` | Merchant-scoped replay outcome for endpoint creation. | `(owner_id, idempotency_key)` uniqueness, canonical request hash, one endpoint link, `PROCESSING -> COMPLETED` guard matching established command semantics. |
| `webhook_deliveries` | One durable public event instruction for one endpoint. | Endpoint/source event uniqueness; public event type/version; immutable UTF-8 body and SHA-256; payment aggregate ID/sequence; constrained delivery state, leases, counters, and bounded failure metadata. |
| `webhook_delivery_attempts` | Append-only audit of each outbound HTTP attempt. | Delivery FK; unique positive attempt number; start/completion times; outcome category; optional valid HTTP status; duration; sanitized bounded diagnostic; no request/response secrets or bodies. |
| `reconciliation_runs` | Immutable record of a bounded read-only verification run. | Start/end timestamps, versioned check set, outcome, counts, and safe summary; failed runs are recorded rather than hidden. |
| `reconciliation_findings` | Immutable discrepancy evidence attached to a run. | Run FK, stable finding type/severity/entity reference, deduplication fingerprint, sanitized JSON details, and detected time. |

`webhook_deliveries` states are `PENDING`, `IN_FLIGHT`, `DELIVERED`, `DEAD`, and `CANCELLED`.
Transitions are database-guarded: normal worker claims and completes/reschedules only with its lease;
disablement can cancel unsent work; and manual replay alone can move `DEAD` back to `PENDING` while
incrementing replay metadata. Public event identity/body/hash, endpoint identity, source aggregate
identity/sequence, and original queue time are immutable. Delivery attempts and reconciliation findings
are append-only. All foreign keys use restrictive deletion semantics.

Use an eligible-delivery index over state/next-attempt time and an endpoint/aggregate/sequence lookup
to enforce ordering without table scans. Use owner/created-time and endpoint/delivery-time indexes for
the paginated merchant API. Use no Redis queue and no JPA entity mutation loop for leasing; retain the
short JDBC `FOR UPDATE SKIP LOCKED` style established by `OutboxEventStore`.

V8 creates the endpoint, idempotency, delivery, and attempt schema plus its state/immutability guards.
V9 creates reconciliation persistence. Migration tests must prove constraints and triggers reject invalid
states, payload mutation, deletion, attempt renumbering, and secret plaintext columns.

## 7. Reconciliation and Operational Signals

The ledger has no cached balance to compare: wallet balance is already derived from immutable
`ledger_entries`. Reconciliation therefore checks relationships that SQL foreign keys and transaction
triggers cannot fully prove, in bounded read-only batches:

| Check | Finding when violated | Automatic action |
|---|---|---|
| Journal balance | A committed journal is missing entries or its debits/credits differ by currency. | Record critical finding and expose health/metric signal; never alter entries. |
| Payment/refund journal shape | A payment/refund's linked journal does not contain the expected participant accounts, sides, currency, and amount. | Record critical finding; no compensation or mutation. |
| Payment/refund event completeness | A completed fact has no matching immutable outbox event, or an event has inconsistent type/aggregate/version data. | Record critical finding; do not synthesize a replacement event automatically. |
| Webhook fan-out | An active endpoint that was eligible when an event was consumed lacks its delivery instruction, or its stored public body/hash is inconsistent. | Record error finding; do not resend automatically. |
| Workflow backlog | Outbox or webhook work exceeds the configured age/attempt threshold. | Increment metrics and record warning/error evidence; do not make readiness fail merely because a merchant server is down. |

Run these checks on a configurable schedule and expose the last run time, duration, outcome, and
finding counts through protected operational metrics/health details. Use a single-run database lease so
multiple application instances do not duplicate an expensive scan. Runs must use a cutoff/cursor and
record the check version, making results explainable and avoiding an unbounded full-table scan on every
schedule.

Add Micrometer counters/timers/gauges with bounded labels only:

- webhook enqueue and attempt outcomes by public event type/outcome;
- delivery latency, pending count, oldest pending age, dead count, and lease recoveries;
- outbox pending count/oldest age/publish failures, preserving Phase 3 visibility;
- reconciliation run duration/outcome/finding count; and
- no labels for UUIDs, URLs, merchant IDs, error messages, or raw exception classes.

Structured logs carry correlation-safe event ID, delivery ID, aggregate ID, and attempt number. Secrets,
URLs, payloads, tokens, and customer identifiers are excluded. Liveness remains an application-process
signal; readiness includes required local dependencies such as the database, but a third-party webhook
failure is an operational delivery problem, not a reason to claim LedgerX is down.

## 8. REST API and Error Contract

All endpoints remain under `/api/v1`, use UUIDs and UTC ISO-8601 instants, return DTOs rather than
entities, and resolve callers through `OwnerContextResolver`. In production the Keycloak owner claim is
required; the forgeable header remains local/test only. A caller must be an active `MERCHANT` owner and
can see only its own endpoints/deliveries. Ownership failures should avoid leaking another merchant's
endpoint or delivery existence.

| Endpoint | Purpose | Success |
|---|---|---|
| `POST /webhook-endpoints` | Register an endpoint, event subset, and signing secret. Requires `Idempotency-Key`. | `201 Created`, or `200 OK` replay with a redacted endpoint. |
| `GET /webhook-endpoints` | Cursor-paginated list of the caller's redacted endpoints. | `200 OK` |
| `GET /webhook-endpoints/{endpointId}` | Read one owned redacted endpoint and summary counts. | `200 OK` |
| `POST /webhook-endpoints/{endpointId}/disable` | Disable an endpoint and cancel unsent delivery work. | `200 OK` |
| `POST /webhook-endpoints/{endpointId}/rotate-secret` | Replace the signing secret using an idempotent configuration command. | `200 OK` |
| `GET /webhook-endpoints/{endpointId}/deliveries` | Cursor-paginated delivery summaries for one owned endpoint. | `200 OK` |
| `GET /webhook-endpoints/{endpointId}/deliveries/{deliveryId}` | Read delivery state and redacted attempt history. | `200 OK` |
| `POST /webhook-endpoints/{endpointId}/deliveries/{deliveryId}/replay` | Requeue one owned terminal delivery. | `202 Accepted` |

Endpoint responses may show a normalized URL only to the owning merchant, event types, active status,
secret version (never secret/ciphertext), timestamps, and delivery counts. Delivery responses show the
public event ID/type, status, attempts, timestamps, safe HTTP status/error category, and no raw body or
signature. Document the public webhook body and verification algorithm separately in OpenAPI/README
examples; the signing secret must not appear in an example, test fixture committed to source, or error.

Use the existing API problem shape. Add focused webhook exceptions/mappings:

| Situation | HTTP | Code |
|---|---:|---|
| Invalid URL, secret, event subset, endpoint limit, inactive merchant, or forbidden delivery transition | 422 | `WEBHOOK_NOT_PROCESSABLE` |
| Missing/malformed body, UUID, cursor, or idempotency header | 400 | `MALFORMED_REQUEST` |
| Caller is not an active merchant or does not own the resource | 403 or opaque 404 | `WEBHOOK_NOT_AUTHORIZED` / `WEBHOOK_ENDPOINT_NOT_FOUND` |
| Changed configuration under an existing idempotency key, concurrent configuration/replay conflict | 409 | `IDEMPOTENCY_KEY_REUSED` / `WEBHOOK_DELIVERY_CONFLICT` |
| No owned endpoint/delivery exists | 404 | `WEBHOOK_ENDPOINT_NOT_FOUND` / `WEBHOOK_DELIVERY_NOT_FOUND` |
| Unexpected encryption, transport, or persistence failure | 500 | `INTERNAL_ERROR` |

Production continues to disable public Swagger UI. Local/test OpenAPI must include clear warnings about
the development owner header and a concise receiver HMAC verification example.

## 9. Java/Spring Component Design

| Area | Components | Owns / must not own |
|---|---|---|
| `com.ledgerx.webhook.domain` | Endpoint configuration/value types, delivery states/outcomes, public event projection, and focused validation exceptions. | Valid non-financial delivery concepts; never ledger, payment mutation, or HTTP client I/O. |
| `com.ledgerx.webhook.persistence` | Endpoint/idempotency/delivery/attempt JDBC stores and short lease/transition queries. | PostgreSQL persistence and state guards; no controller mapping or secret logging. |
| `com.ledgerx.webhook.application` | Endpoint configuration service, event-enqueue service, dispatcher, replay service, merchant delivery query service. | Ownership/configuration checks and transaction boundaries; no direct journal writes. |
| `com.ledgerx.webhook.transport` | HTTP sender abstraction, HMAC signer, SSRF-safe URL policy, typed properties. | One outbound request at a time; no persistence transaction while waiting. |
| `com.ledgerx.webhook.api` | Requests/responses/controller/OpenAPI annotations. | Parsing, redaction, pagination, and thin HTTP behaviour. |
| `com.ledgerx.reliability` | Existing outbox event types/envelope and a dedicated webhook Kafka listener. | Parses internal events and delegates atomically; does not share audit-consumer receipts or send HTTP inside the listener. |
| `com.ledgerx.operations` | Reconciliation runner, read-only checks, metrics/health contributors. | Evidence and signals only; cannot repair data or issue financial commands. |

The webhook Kafka listener needs a distinct consumer group and a distinct `processed_events.consumer_name`,
for example `webhook-delivery-enqueuer-v1`; the existing audit consumer remains unchanged. Its receipt
insertion and delivery fan-out belong in one transaction-aware store/service rather than in two nested,
independently committed methods.

Use Spring's supported JDK HTTP client or `RestClient` behind a small interface, with a connection/read
timeout, redirect disabled, and testable transport. Add encryption/signing through a small explicit
service with injected keys/clock; do not introduce a cloud KMS SDK, reactive stack, message broker, or
generic retry framework solely for this phase.

## 10. Transaction, Concurrency, and Security Boundaries

| Rule | Enforcement |
|---|---|
| Webhook work begins only for a committed Kafka event; it never shares the original payment/refund database transaction. | Phase 3 outbox publication followed by transactional consumer enqueueing. |
| Duplicate Kafka delivery creates at most one base instruction per active endpoint/event. | One consumer receipt plus `(endpoint_id, event_id)` unique constraint in the same transaction. |
| A dispatcher sends no work while holding a PostgreSQL transaction. | Short claim, remote call, short outcome transaction pattern. |
| One active worker owns a delivery state change. | `FOR UPDATE SKIP LOCKED`, lease token/until, and matching-token updates. |
| A remote receiver can receive duplicate requests but has a stable dedupe key. | At-least-once workflow; `LedgerX-Event-Id` is invariant across attempts/replays. |
| Same-payment event ordering is preserved for one endpoint. | Persist aggregate ID/sequence and do not claim a later delivery until earlier work is delivered or deliberately addressed. |
| Disabled endpoints receive no future external request. | Endpoint lock/state check on queue/claim; cancel unsent work; no redirect or alternate URL. |
| A merchant cannot configure/read/replay another merchant's endpoint. | JWT/local owner context, active merchant/type validation, ownership-filtered queries, API tests. |
| Signing material and outbound data do not leak. | Encryption at rest, response redaction, safe logs/metrics, HTTP header allowlist, and tests that inspect serialized/API/loggable values. |
| Reconciliation cannot create or repair money. | Read-only queries, immutable finding records, no access to posting/command services. |

There are no distributed transactions between PostgreSQL, Kafka, and a merchant endpoint. The system
therefore promises durable internal handoff plus at-least-once external attempt, not a synchronous
merchant acknowledgement and not end-to-end exactly-once processing.

## 11. Configuration and Deployment

Introduce typed `ledgerx.webhooks` and `ledgerx.reconciliation` properties, with safe defaults disabled
in tests. Expected settings include:

- webhook consumer/dispatcher enabled flags and Kafka consumer group;
- poll delay, batch size, lease duration, connection/read timeout, maximum attempts, retry base/max
  delays, maximum accepted `Retry-After`, endpoint-count limit, and allowed event types;
- strict production HTTPS/egress policy controls, plus a test-only mock receiver allowance;
- secret-encryption key material/active key version supplied only by environment or secret store; and
- reconciliation enabled flag, schedule, single-run lease, batch size/cutoff, and alert-age thresholds.

Validate configuration eagerly at startup: a production-enabled dispatcher without a usable encryption
key, secure endpoint policy, nonzero attempt limit, or finite timeout must fail fast. Never place an
actual signing or encryption key in `application*.yml`, `.env.example`, Compose, tests, or docs. Add
placeholder variable names and minimum-format guidance to `.env.example`/configuration documentation.

Docker Compose needs only configuration plumbing and a mock webhook receiver for local/demo use; it does
not need Redis or another broker. CI runs unit tests plus PostgreSQL/Kafka/Testcontainers integration
tests. Any HTTP mock must assert headers/body bytes locally and must not make an internet request.

## 12. Phase 4 Invariants

| Rule | Primary enforcement |
|---|---|
| A completed payment/refund remains financially complete whether all webhooks fail or not. | Separate asynchronous queue; webhook modules have no ledger command dependencies. |
| A public webhook maps only to a known committed internal event and intended merchant. | Validated v1 consumer, immutable wallet-owner lookup, transactional enqueueing, tests. |
| The same source event is queued once per eligible endpoint, despite Kafka redelivery. | Consumer receipt/hash plus endpoint/event uniqueness. |
| Webhook requests are signed with a per-endpoint secret over exact immutable body bytes and attempt timestamp. | Encrypted secret service, HMAC unit/integration tests, immutable body/hash. |
| LedgerX never claims exactly-once external delivery. | Leased at-least-once dispatcher and stable public event ID documentation. |
| A receiver can distinguish source event identity from a delivery attempt. | Event-ID and delivery-ID headers with documented roles. |
| Public payloads and event versions are stable and do not disclose unnecessary internal identifiers. | Explicit projection DTO, persisted bytes/hash, contract tests, review. |
| Outbound delivery cannot become an SSRF bypass or leak credentials. | URL policy, no redirects, production egress control, encryption, redaction, safe logs. |
| Retry/lease/replay metadata may change only through documented transitions; attempt evidence does not mutate. | Database triggers/constraints, matching lease tokens, append-only attempts, tests. |
| A merchant sees/configures only its own endpoints and delivery audit. | Owner context/application filtering, opaque lookup behaviour, MockMvc tests. |
| Integrity checks report evidence but never repair journals, facts, events, or delivery data. | Read-only reconciliation boundary, immutable findings, tests. |

All Phase 1–3 money, ownership, idempotency, atomicity, ordering, and concurrency invariants continue to
apply unchanged.

## 13. Testing Strategy

| Level | Required evidence |
|---|---|
| Unit | URL policy, public payload projection, deterministic raw-body serialization/hash, HMAC vectors/constant-time verification helper, secret encryption round trip, retry classification/delay cap/jitter, state transitions, and finding classifiers. |
| Application | Active merchant ownership, endpoint limits/configuration idempotency, secret redaction, Kafka receipt-plus-fan-out atomicity, disabled endpoint behaviour, replay eligibility, and reconciliation's read-only boundary. |
| PostgreSQL Testcontainers | V8/V9 FKs/checks/indexes, endpoint creation replay/conflict, encrypted-secret storage shape, immutable payloads/attempts/findings, delivery transition/lease guards, disabled cancellation, and unique event fan-out. |
| Kafka Testcontainers | Published payment/refund events create intended merchant work once; duplicate Kafka delivery is a no-op; corrupt same-ID/different-hash payload fails; audit consumer remains independent. |
| HTTP dispatcher | Mock receiver verifies exact headers/signature/body, 2xx completion, timeout/network retry, retryable/terminal status handling, no redirects, `Retry-After` cap, crashed/expired lease recovery, and stale-worker token rejection. |
| Concurrency | Competing dispatcher instances send one leased attempt at a time; concurrent duplicate event handling creates one delivery; disable/claim and replay/claim races preserve terminal/audit invariants. |
| MockMvc/OpenAPI | JWT/local merchant ownership, endpoint CRUD-like operations, redacted responses, cursor pagination, problem shapes, replay `202`, no cross-merchant disclosure, and generated docs/examples. |
| Reconciliation | Seed deliberate cross-row/outbox/delivery anomalies through controlled fixture setup; assert one immutable finding, cursor/cutoff behaviour, metrics, and no money/data mutation. |

Critical proof cases:

- A `25.00 USD` payment still commits once with one journal/outbox event while its merchant endpoint is
  unavailable; a later delivery succeeds without another financial effect.
- Kafka delivers the same payment event twice; the consumer has one receipt and one delivery for each
  endpoint, while the receiver may safely see a duplicate HTTP request after a simulated crash.
- The receiver recomputes the documented HMAC from `timestamp + "." + rawBody` and obtains the supplied
  signature; the same body with a changed byte or timestamp fails verification.
- An attacker-supplied loopback/credential/redirect URL is rejected or never followed; no secret or
  unredacted body appears in returned DTOs, persisted attempt diagnostics, or captured logs.
- `429`/`503` schedules a bounded retry, `400` becomes `DEAD`, and an owner can replay that `DEAD`
  delivery without creating a new payment/refund/event. A disabled endpoint cannot be replayed.
- A forced delivery insert failure rolls back the consumer receipt so Kafka can redeliver; a forced
  reconciliation failure records a failed run but changes no financial or delivery fact.
- The full `mvnw.cmd clean verify` suite passes with PostgreSQL and Kafka Testcontainers available.

## 14. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Treating a webhook response as payment completion | Financial commit precedes event/outbox; delivery state is a separate non-financial workflow. |
| Duplicate HTTP deliveries cause merchant-side duplicate fulfilment | Document and preserve stable event ID; sign every attempt; test crash-after-send behaviour. |
| Public webhook payload accidentally leaks internal/financial data | Use a dedicated minimal projection rather than raw outbox JSON; contract/redaction tests. |
| Webhook registration becomes an SSRF primitive | Strict URL/redirect policy, production egress control, no userinfo/private targets, and tests. |
| Signing keys leak through database, response, logs, or diagnostics | Encrypt at rest, accept over TLS, redact DTOs, bounded safe diagnostics, configuration discipline. |
| A slow receiver exhausts worker/database resources | Finite timeout, leased batch claims, no transaction during I/O, capped retries, and metrics. |
| A terminal first event causes a later refund event to appear out of order | Persist per-endpoint payment sequence and deliberately block later work pending recovery. |
| Consumer deduplication records success before fan-out is durable | Persist receipt and delivery rows in the same transaction; rollback both on failure. |
| Reconciliation quietly changes data or becomes an expensive full scan | Read-only checks, immutable evidence, leases, batches/cutoffs, schedules, and no repair path. |
| Phase scope turns into general messaging/observability infrastructure | Limit public types, one sender, one queue, bounded metrics, and defer dashboards/general notifications. |

## 15. Implementation Sequence

| Milestone | Goal | Acceptance result |
|---|---|---|
| 4.1 | Audit current event payloads/docs, define public v1 webhook contract and security/SSRF policy, add typed configuration skeleton. | Contract has no raw outbox leakage; production key/timeout validation is specified and tested. |
| 4.2 | Add V8 endpoint/configuration-idempotency/delivery/attempt schema with trigger guards and repositories. | PostgreSQL tests prove uniqueness, encryption shape, immutable body/attempt history, lease transitions, and endpoint disablement. |
| 4.3 | Implement merchant endpoint API, ownership enforcement, configuration idempotency, redaction, secret rotation, and paginated queries. | MockMvc/OpenAPI tests prove no secret or cross-merchant disclosure. |
| 4.4 | Implement the distinct Kafka webhook enqueuer and transactional receipt-plus-fan-out workflow. | Kafka/Testcontainers duplicate, rollback, and intended-merchant tests pass. |
| 4.5 | Implement HMAC signer, safe HTTP transport, leased dispatcher, retry/ordering/lease recovery, attempt audit, and manual replay. | Mock receiver and concurrency tests prove signatures, retry classification, no redirects, no duplicate local queueing, and at-least-once semantics. |
| 4.6 | Add V9 reconciliation records/checks plus delivery/outbox metrics, health details, structured correlation logs, and configuration/deployment documentation. | Seeded discrepancies are recorded without mutation; metrics/health distinguish backlog from availability. |
| 4.7 | Update README, architecture/backlog/roadmap, configuration/Docker guides, OpenAPI examples, and run full verification. | Documentation makes only verified claims and `mvnw.cmd clean verify` passes. |

Each milestone should be a small logical commit (contract/config, schema, endpoint API, consumer, dispatcher,
operations, docs/tests). Do not mix generated artifacts, unrelated deployment edits, Redis, or future
payment features into the phase.

## 16. Definition of Done

Phase 4 is complete when:

- an active merchant can securely configure and manage only its own signed webhook endpoints;
- each eligible committed payment/refund event is transactionally enqueued once per active endpoint,
  despite Kafka redelivery, without affecting financial state;
- LedgerX posts a versioned minimal public body with documented HMAC headers, finite timeout, no redirect
  following, and stable event identity for receiver deduplication;
- delivery retry, bounded backoff, terminal failure, expired lease recovery, ordering, attempt audit, and
  owned manual replay behave exactly as documented;
- signing/encryption material and sensitive outbound data are absent from source, configuration, API
  responses, logs, metrics, and persisted diagnostics in plaintext;
- reconciliation detects and records bounded evidence of meaningful ledger/payment/outbox/delivery
  discrepancies without automatically mutating or compensating anything;
- metrics, health details, configuration, deployment guidance, OpenAPI, and product docs make external
  delivery state observable without falsely marking the service unavailable for a merchant outage;
- PostgreSQL/Kafka/HTTP/MockMvc/concurrency tests substantiate the invariants and `mvnw.cmd clean verify`
  passes; and
- Redis, generic notification infrastructure, general rate limiting, API keys, provider workflows, and
  other deferred scope remain absent from the change set.

## 17. What Phase 4 Enables

LedgerX will have a complete and explainable asynchronous boundary: a balanced payment/refund commits,
an immutable internal event reaches Kafka through the transactional outbox, merchant-specific delivery
work is queued exactly once locally, and a signed external notification is retried and audited without
being confused with money movement. It also gains evidence when relationships around its financial facts
or external work diverge.

That creates a sound basis for Phase 5 to choose selectively among rule-based risk decisions, narrowly
scoped API keys or rate limits, richer observability/dashboarding, provider simulation, or other features
only when a concrete use case justifies their complexity.
