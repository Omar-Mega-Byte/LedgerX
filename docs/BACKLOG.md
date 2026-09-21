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
| FND-02 | P0 | Add local PostgreSQL and versioned Flyway migrations | Local database starts predictably; migration history is recorded |
| FND-03 | P0 | **Complete** — enforce Java 21 and formatting checks | `verify` rejects unsupported Java versions and fails on unformatted Java sources |
| TST-01 | P0 | Establish database integration-test layers | PostgreSQL Testcontainers tests run for persistence and transactional behavior |
| CFG-01 | P0 | **Complete** — establish local/test/prod profile and secret conventions | `local` is the default; tracked configuration has no secrets; `.env.example` documents safe variables |
| LGR-01 | P0 | Model monetary values, currencies, ledger accounts, transactions, and entries | No floating-point money; a posted transaction balances per currency |
| WAL-01 | P0 | Add wallet/account ownership and a safe balance strategy | Authority of ledger versus any derived balance is documented and tested |
| XFR-01 | P0 | Deliver an idempotent wallet-to-wallet transfer | Duplicate same-key requests have one effect; insufficient funds and concurrent debits preserve invariants |
| SEC-01 | P0 | Add authentication and object-level authorization for the first vertical slice | Users cannot access or move another user's funds |
| REL-01 | P1 | Persist transactional outbox events with financial state changes | State and outbox event commit atomically; publisher retry behavior is tested |
| REL-02 | P1 | Add Kafka publication and idempotent consumer handling | Duplicate delivery does not duplicate downstream business effects |
| PAY-01 | P1 | Add a payment state machine and merchant-scoped idempotency | Valid transitions are explicit; invalid transitions are rejected |
| RFD-01 | P1 | Support full and partial refunds with compensating ledger entries | Refund total cannot exceed the captured amount |
| WHK-01 | P1 | Deliver signed webhooks with retry tracking | Signatures, retry schedule, failures, and manual replay are auditable |
| OPS-01 | P1 | Containerize local dependencies/application and add CI verification | Docker-based startup and GitHub Actions `verify` succeed |
| RSK-01 | P2 | Implement a configurable rule-based risk decision | ALLOW, REVIEW, and BLOCK outcomes are traceable and tested |
| REC-01 | P2 | Add a reconciliation job | Ledger, derived balance, and payment-state mismatches are reported |
| OBS-01 | P2 | Add actionable metrics and tracing | Payment, outbox, webhook, and reconciliation metrics are exposed |
| EXT-01 | P3 | Evaluate external-provider simulation, Kubernetes, or multi-currency conversion | Added only with a specific scenario and documented trade-off |

## Working rules

- A backlog item becomes ready when its business rule, ownership boundary, failure modes, and test approach are understood.
- Financial state changes require explicit idempotency, transaction, and concurrency considerations before implementation.
- Database changes require a Flyway migration and PostgreSQL-backed verification.
- Completed work must update the README and architecture notes when it changes project claims or material decisions.
