# Delivery roadmap

> **Non-final planning document.** This roadmap communicates the intended sequence, not fixed dates or immutable scope. Phases can be reordered, narrowed, or replaced as the project evolves.

## Phase 0 — Repository foundation

**Goal:** make the project easy to understand, clone, and develop safely.

- **Complete:** repository hygiene, README, architecture notes, backlog, and roadmap
- **Complete:** Spring Boot/Maven bootstrap, context-load test, and health endpoint
- **Complete:** Java 21 quality baseline, local/test/prod configuration conventions, and secret handling
- **Complete:** Docker Compose PostgreSQL, Flyway V1 migration, Testcontainers database test, and container build
- **Complete:** GitHub Actions verification workflow runs `verify` on pushes and pull requests; `main` requires its green `Verify` check before a pull request can merge
- **Complete:** issue triage labels and structured bug/feature issue forms

**Exit criteria:** a clean checkout can run documented verification and local infrastructure without tracking credentials or generated artifacts.

## Phase 1 — Ledger and wallet core

**Goal:** establish a trustworthy financial source of truth.

- **Complete:** active PERSON/MERCHANT ownership model and development-only ownership seam
- **Complete:** exact USD money value model
- **Complete:** ledger accounts, immutable balanced journals/entries, and derived-balance invariants
- **Complete:** wallet/account model with database integrity and ordered locking
- **Complete:** PostgreSQL-backed domain, integrity, and concurrency tests

**Exit criteria:** complete — posted transactions are auditable and balanced; the derived-balance strategy is documented and protected by tests.

## Phase 2 — Idempotent transfers

**Goal:** complete one end-to-end money movement flow correctly.

- **Complete:** wallet-to-wallet transfer API for PERSON and MERCHANT wallets
- **Complete:** explicit business validation and insufficient-funds handling
- **Complete:** idempotency key, canonical fingerprint, replay, and conflict behavior
- **Complete:** ordered account locking for competing debits
- **Complete:** transfer audit trail and immutable ledger records

**Exit criteria:** complete — repeated and concurrent requests cannot create duplicate transfers or violate the balance invariant.

## Phase 3 — Payment reliability

**Goal:** extend the core into resilient payment processing.

- **Complete:** payer-authorized PERSON-to-MERCHANT payments and merchant-scoped refunds
- **Complete:** derived payment refund lifecycle and successful-result idempotency
- **Complete:** transactional outbox, leased retry publisher, and per-payment event ordering
- **Complete:** Kafka integration and a durable idempotent audit consumer
- **Complete:** full/partial refunds using compensating immutable ledger journals

**Exit criteria:** complete — a committed payment/refund and its event cannot silently diverge; duplicate events remain safe.

## Phase 4 — External effects and operations

**Goal:** make asynchronous boundary behavior visible and supportable.

- **Complete:** merchant webhook registration, HMAC signing, delivery records, retries, and manual replay
- **Complete:** bounded outbox/webhook metrics and delivery health details; full tracing and dashboards remain deferred
- **Complete:** read-only reconciliation of journal balance and payment/refund outbox completeness
- **Complete:** production Compose/configuration refinements for webhook and reconciliation settings

**Exit criteria:** complete — failed external delivery is observable and recoverable; important integrity mismatches can be detected without automatic financial repair. See [Phase 4](../phase4.md).

## Phase 5 — Payment risk decisions and review

**Goal:** make payer-authorized merchant payments subject to explainable, auditable risk decisions without letting review or operator actions move money.

- **Complete:** versioned operator-managed USD rules for a hard amount limit and rolling payer velocity checks
- **Complete:** durable `ALLOW`, `REVIEW`, and `BLOCK` outcomes integrated with payment idempotency
- **Complete:** operator review queue and payer-only retry of an approved, exact matching request
- **Complete:** owner/operator workbench views, audit history, metrics, and reconciliation evidence

**Exit criteria:** complete — a review or block posts no money, an approval alone posts no money, and an approved payer retry can create at most one payment and one completed-payment event. The full CI run passed 34 unit and 63 integration tests on 2026-09-25. See [Phase 5](../Phase5.md).

Merchant API keys, Redis rate limits, provider simulation, payouts, and multi-currency remain candidates for later phases when a concrete use case justifies them.

## Phase 6 — Public and outbound security

**Implemented in code:** webhook URL and connection-time DNS guards, HTTPS port 443 enforcement,
non-redirecting outbound client, encryption key-ring rotation with durable operator evidence,
JWT mutation rate limiting, browser security headers, and hardened production container/network
defaults. Unit and PostgreSQL-backed authorization/rotation tests cover these changes. Production
egress rules, secret-store integration, image/dependency scans, and a live Keycloak/Caddy security
check remain deployment acceptance work.

## Phase 7 — Integrity evidence and event recovery

**Implemented in code:** reconciliation check version 3 adds ledger, payment/refund, event-stream,
consumer-receipt, and webhook consistency checks; finding queries are paged, findings API is
bounded, and failed runs persist a failed status. Kafka consumer failures go to a dead-letter topic
after bounded retries. Operator outbox replay is idempotent, audited, and republishes the existing
immutable event; Kafka and PostgreSQL integration tests cover DLT and duplicate safety. The
[event operations runbook](event-operations.md) documents retention and recovery. Broker retention
for pre-existing topics and a live recovery drill remain deployment acceptance work.

## Change policy

When priorities change, update this roadmap and the backlog together. Do not represent a roadmap item as implemented until the corresponding code, tests, and documentation support the claim.
