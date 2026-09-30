# Development foundation record

**Status: complete as of 2026-09-21.** This document records the repository baseline actually present before the first domain slice. It is a decision record, not a claim that financial workflows are implemented.

## Delivered baseline

| Decision | Current implementation | Verification boundary |
|---|---|---|
| Build and runtime | Java 21 Spring Boot application, Maven Wrapper, and Actuator health endpoint | `./mvnw verify`; `GET /actuator/health` |
| Code quality | Java version enforcement and formatting checks in Maven verification | Maven `verify` |
| Configuration | `local`, `test`, and `prod` conventions; tracked configuration contains no real secrets | [configuration guide](../configuration.md) and [template](../../.env.example) |
| Persistence | PostgreSQL, Flyway V1 infrastructure migration, and JPA foundation | Docker Compose and PostgreSQL-backed integration test |
| Test layers | Fast unit-test naming plus Testcontainers `*IntegrationTest` execution through Failsafe | Maven `verify` with Docker available |
| Local delivery | Compose services, containerized application, non-root runtime container | [Development guide](../development.md) |
| Continuous integration | GitHub Actions `Verify` workflow runs Maven `verify` for pushes and pull requests | Required `Verify` check on pull requests to `main` |
| Collaboration hygiene | Triage labels, structured bug/feature forms, and a pull-request checklist | `.github/` metadata |

## Chosen first business vertical slice

The first vertical slice is an **idempotent wallet-to-wallet transfer backed by an immutable double-entry ledger**. It is deliberately narrower than merchant payments: it establishes the financial source of truth and duplicate/concurrency behavior before payment state machines, Kafka, or webhooks add external effects.

The slice will deliver, in this order:

1. A money and currency model without floating-point amounts.
2. Wallet ownership plus ledger accounts, transactions, and immutable balanced entries.
3. A transfer command with explicit insufficient-funds behavior and one database transaction for all financial state.
4. Idempotency-key handling: same key and payload replay one result; the same key with a different payload is rejected.
5. Concurrency protection that prevents competing debits from violating the selected available-balance invariant.
6. PostgreSQL-backed tests for balancing, atomicity, idempotency, and concurrent debit attempts.

The ledger is the authority for posted financial history. Any balance projection introduced for the slice is derived data, must be updated safely, and must be reconcilable with that ledger. Authentication and object-level authorization are included before exposing the transfer flow beyond a controlled development boundary.

## Delivery guardrails

- Changes to `main` arrive through pull requests and must have a green `Verify` check.
- Issues use type, priority, area, and triage labels to keep active work traceable.
- A change is not complete until tests and documentation substantiate its claim; financial changes also need explicit idempotency, transaction, and concurrency reasoning.
- At the time of this foundation record, `LGR-01`, `WAL-01`, `XFR-01`, and `SEC-01` were the next planned work. Those slices were subsequently implemented; see the current [architecture](../architecture.md).
