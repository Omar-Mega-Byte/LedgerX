# LedgerX

LedgerX is a production-inspired Java backend for payment and digital-wallet workflows. It emphasizes financial correctness: auditable money movement, duplicate prevention, safe concurrency, and reliable event processing.

The application includes USD wallet ownership, immutable double-entry journals, idempotent
transfers, merchant payments and refunds, transactional outbox and Kafka delivery, signed merchant
webhooks, reconciliation evidence, and versioned payment risk decisions with operator review.
Production deployment uses Keycloak-signed JWTs and Caddy HTTPS.

## Workbench

LedgerX includes a same-origin, responsive browser workbench at `/`. Its contextual wallet,
activity, payment, webhook, and operator views use the REST API. The UI ships as static assets
inside the Spring Boot jar; it needs no separate frontend server. In local mode, enter a prepared
owner UUID using the development-only identity seam. In production, the workbench signs in through
Keycloak Authorization Code with PKCE.

The API includes owner-scoped wallet balances, paged activity, refund lookup, and immutable journal
evidence. Privileged provisioning and reconciliation views require the `ledgerx-operator` realm
role. The workbench includes payer review status and an operator risk queue. Local development
offers an idempotent demo top-up backed by a balanced journal entry;
production does not expose that endpoint. The workbench cannot repair ledger entries. See
[production deployment](docs/production-deployment.md) for browser client setup.

Event and webhook recovery, key rotation, retention, and reconciliation procedures are in
[event operations](docs/event-operations.md).
Operational alerts, verified database backups, restore, and broker-loss procedures are in
[disaster recovery](docs/disaster-recovery.md).

Payment risk starts with a disabled policy. An operator can activate versioned USD amount and
24-hour velocity rules. `ALLOW` completes through the existing ledger/outbox flow; `REVIEW` and
`BLOCK` create durable decisions without moving money. An operator approval only permits the
original payer to retry the exact request and idempotency key. See [the architecture notes](docs/architecture.md) for the current flow; the phase documents preserve design and verification history.

The UI uses dependency-free browser modules with JSDoc type checks. Run `npm ci` and
`npm run check:ui` to check browser code; Maven packages the static files directly.

## Why LedgerX

Payment systems have failure modes that ordinary CRUD applications can hide: a request can be retried, a message can be delivered twice, a process can fail between database and broker writes, and concurrent requests can attempt to spend the same funds. LedgerX is intended to make those concerns explicit and testable.

The primary design goals are:

- immutable, balanced double-entry ledger records for material money movement;
- idempotent commands so retries do not create duplicate financial effects;
- transaction and concurrency controls that protect balances;
- reliable asynchronous processing through a transactional outbox and idempotent consumers;
- auditability, security, and operational visibility suitable for a production-inspired system.

## Stack

- Java 21, Spring Boot, Maven
- PostgreSQL, Flyway, Spring Data JPA
- Spring Security resource server, Keycloak/OIDC, REST APIs, validation, Actuator, OpenAPI
- Apache Kafka for committed payment and refund events
- Docker Compose for local dependencies
- JUnit 5, Mockito, Spring Boot Test, and Testcontainers
- GitHub Actions for verification; pull requests to `main` require the green `Verify` check

See [the architecture notes](docs/architecture.md) and [the development guide](docs/development.md) for the implemented shape and conventions. Redis remains outside the current scope.

## Financial core

Transfers, payments, refunds, and local demo funding use the same immutable double-entry ledger. Payment and refund transactions also write outbox events; asynchronous consumers handle audit receipts and signed merchant webhooks. The [foundation record](docs/FOUNDATION.md) preserves the initial design decisions.

## Repository layout

```text
.
├── docs/                 # Architecture and development documentation
├── compose.yaml          # Local PostgreSQL, Kafka, and containerized application
├── compose.production.yaml # Private dependencies behind Caddy HTTPS
├── src/                  # Spring Boot application and tests
├── pom.xml               # Maven build and verification configuration
└── mvnw.cmd              # Pinned Maven Wrapper for Windows
```

The project runs as a modular monolith. PostgreSQL is the financial source of truth and Kafka carries committed payment/refund events.

## Getting started

Requires a Java 21 JDK. Maven does not need to be installed globally; the checked-in wrapper downloads its pinned Maven distribution on first use.

```powershell
docker compose -p ledgerx-dev up -d --build
docker compose -p ledgerx-dev ps
.\mvnw.cmd verify
```

Then check the local application health at <http://127.0.0.1:8080/actuator/health>.
Only the local Compose stack publishes port 8080; the production stack is served through
Caddy HTTPS. Keep the project names separate when running both on one machine.
The complete verification uses Docker-backed PostgreSQL and Kafka Testcontainers. It runs unit,
API, database, messaging, security, and concurrency tests without a separate Keycloak process.

See [configuration conventions](docs/configuration.md) for profiles, environment variables, and secret handling. [Docker instructions](docs/docker.md) cover the local database and full containerized stack.
For the self-hosted public stack, use [the production deployment guide](docs/production-deployment.md).

For current direction and delivery sequencing, read:

- [Architecture notes](docs/architecture.md)
- [Development guide](docs/development.md)
- [Delivery backlog](docs/BACKLOG.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Configuration conventions](docs/configuration.md)
- [Docker instructions](docs/docker.md)
- [Development foundation record](docs/FOUNDATION.md)

## Non-goals

LedgerX is not intended to store real card credentials, claim PCI compliance, or simulate a full bank. It favors a small, coherent, explainable system over a collection of disconnected technologies.

## License

No license has been selected yet.
