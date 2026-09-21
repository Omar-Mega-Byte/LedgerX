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

- Webhook registration, HMAC signing, delivery records, and retries
- Metrics, traces, health checks, and operational dashboards where useful
- Reconciliation for ledger/payment/balance mismatches
- Docker and CI refinements based on actual development needs

**Exit criteria:** failed external delivery is observable and recoverable; important integrity mismatches can be detected.

## Phase 5 — Selective enhancements

**Goal:** add only capabilities that reinforce the project’s financial-engineering story.

Candidates include rule-based risk checks, merchant API keys, Redis rate limits, a provider simulator, multi-currency concerns, or deployment work. Each requires a concrete use case and a documented trade-off before it enters active delivery.

## Change policy

When priorities change, update this roadmap and the backlog together. Do not represent a roadmap item as implemented until the corresponding code, tests, and documentation support the claim.
