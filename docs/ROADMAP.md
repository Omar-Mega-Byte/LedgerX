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

- User/merchant ownership model and first authorization boundary
- Money and currency value model
- Ledger accounts, transactions, immutable entries, and balance invariants
- Wallet/account model with documented balance authority
- PostgreSQL-backed domain and concurrency tests

**Exit criteria:** posted transactions are auditable and balanced; the chosen balance strategy is documented and protected by tests.

## Phase 2 — Idempotent transfers

**Goal:** complete one end-to-end money movement flow correctly.

- Wallet-to-wallet transfer API
- Explicit business validation and insufficient-funds handling
- Idempotency key, request fingerprint, response replay, and conflict behavior
- Deliberate concurrency control for competing debits
- Transfer audit trail and domain events

**Exit criteria:** repeated and concurrent requests cannot create duplicate transfers or violate the selected balance invariant.

## Phase 3 — Payment reliability

**Goal:** extend the core into resilient payment processing.

- Payment state machine and merchant-scoped commands
- Transactional outbox and reliable event publishing
- Kafka integration and idempotent consumers
- Refunds using compensating ledger transactions

**Exit criteria:** a committed financial operation and its event cannot silently diverge; duplicate events remain safe.

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
