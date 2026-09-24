# Delivery backlog

> **Non-final planning document.** This backlog is a living prioritization aid, not a committed specification. Newer requirements, implementation evidence, and discovered risks may change its scope, order, or acceptance criteria.

## Priority guide

| Priority | Meaning |
|---|---|
| P0 | Required to demonstrate a correct end-to-end financial vertical slice |
| P1 | High-value reliability or delivery capability after the core slice |
| P2 | Valuable enhancement once the foundation is proven |
| P3 | Stretch work; only pursue when it solves a demonstrated need |

## Backlog

| ID | Priority | Outcome | Acceptance signals |
|---|---|---|---|
| FND-01 | P0 | **Complete** — bootstrap a Spring Boot/Maven application with a health endpoint | Maven Wrapper runs `verify`; context-load test passes; `/actuator/health` returns `UP` |
| FND-02 | P0 | **Complete** — add local PostgreSQL and versioned Flyway migrations | Local database starts predictably; Flyway records the V1 infrastructure migration |
| FND-03 | P0 | **Complete** — enforce Java 21 and formatting checks | `verify` rejects unsupported Java versions and fails on unformatted Java sources |
| TST-01 | P0 | **Complete** — establish database integration-test layers | A PostgreSQL Testcontainers `*IntegrationTest` verifies context startup and the Flyway migration |
| CFG-01 | P0 | **Complete** — establish local/test/prod profile and secret conventions | `local` is the default; tracked configuration has no secrets; `.env.example` documents safe variables |
| LGR-01 | P0 | **Complete** — model exact money, currencies, ledger accounts, transactions, and entries | No floating-point money; a posted transaction balances per currency |
| WAL-01 | P0 | **Complete** — add wallet/account ownership and a safe derived-balance strategy | Ledger is authoritative; derived balances, integrity, and concurrency controls are documented and tested |
| XFR-01 | P0 | **Complete** — deliver idempotent wallet-to-wallet transfers | Duplicate same-key requests have one effect; insufficient funds and concurrent debits preserve invariants |
| SEC-01 | P0 | **Complete** — Keycloak JWT owner context and object-level authorization for financial APIs | Users cannot access or move another user's funds; production uses signed JWTs instead of the development owner header |
| REL-01 | P1 | **Complete** — persist transactional outbox events with payment/refund state changes | Financial facts and outbox events commit atomically; leased retry behavior is tested |
| REL-02 | P1 | **Complete** — add Kafka publication and idempotent consumer handling | Duplicate delivery produces one durable consumer receipt and no duplicate financial effect |
| PAY-01 | P1 | **Complete** — add derived payment refund lifecycle and merchant-scoped idempotency | Valid payment/refund transitions are explicit; invalid commands are rejected |
| RFD-01 | P1 | **Complete** — support full and partial refunds with compensating ledger entries | Refund total cannot exceed the original payment, including concurrent commands |
| WHK-01 | P1 | **Complete** — deliver signed webhooks with retry tracking | Signatures, retry schedule, failures, and manual replay are auditable |
| OPS-01 | P1 | **Complete** — containerize local dependencies/application and add CI verification | Docker-based startup passes locally; GitHub Actions runs `verify` on pushes and pull requests; `main` requires its green check for pull requests |
| COL-01 | P1 | **Complete** — establish contribution triage metadata | Type, priority, area, and triage labels exist; structured bug and feature issue forms are available |
| RSK-01 | P2 | **In progress for Phase 5** — versioned payment risk rules and an operator review queue | ALLOW, REVIEW, and BLOCK are durable, owner-scoped, and replayable; review/approval alone moves no money; an approved exact-key payer retry completes at most once |
| REC-01 | P2 | **Complete** — read-only reconciliation job | Unbalanced journals and missing payment/refund outbox facts are reported without repair; broader checks remain future work |
| OBS-01 | P2 | **Partial** — bounded operational metrics and health are present; broader tracing/dashboard work is deferred | Outbox, webhook, and reconciliation signals are exposed without making merchant endpoint failure an application outage |
| EXT-01 | P3 | Evaluate external-provider simulation, Kubernetes, or multi-currency conversion | Added only with a specific scenario and documented trade-off |

## Working rules

- A backlog item becomes ready when its business rule, ownership boundary, failure modes, and test approach are understood.
- Financial state changes require explicit idempotency, transaction, and concurrency considerations before implementation.
- Database changes require a Flyway migration and PostgreSQL-backed verification.
- Completed work must update the README and architecture notes when it changes project claims or material decisions.
