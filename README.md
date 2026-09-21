# LedgerX

LedgerX is a production-inspired Java backend for payment and digital-wallet workflows. It is being built as a portfolio project that emphasizes financial correctness: auditable money movement, duplicate prevention, safe concurrency, and reliable event processing.

> **Project status:** the Spring Boot foundation, health endpoint, Java 21 build checks, and local configuration conventions are in place. Financial domain features and infrastructure integrations are not implemented yet.

## Why LedgerX

Payment systems have failure modes that ordinary CRUD applications can hide: a request can be retried, a message can be delivered twice, a process can fail between database and broker writes, and concurrent requests can attempt to spend the same funds. LedgerX is intended to make those concerns explicit and testable.

The primary design goals are:

- immutable, balanced double-entry ledger records for material money movement;
- idempotent commands so retries do not create duplicate financial effects;
- transaction and concurrency controls that protect balances;
- reliable asynchronous processing through a transactional outbox and idempotent consumers;
- auditability, security, and operational visibility suitable for a production-inspired system.

## Planned stack

- Java 21+, Spring Boot, Maven
- PostgreSQL, Flyway, Spring Data JPA
- Spring Security, REST APIs, validation, Actuator, OpenAPI
- Redis and Apache Kafka where they solve concrete reliability or performance needs
- Docker Compose for local dependencies
- JUnit 5, Mockito, Spring Boot Test, and Testcontainers
- GitHub Actions for verification; pull requests to `main` require the green `Verify` check

These choices are directional, not commitments. See [the architecture notes](docs/architecture.md) for the intended shape and [the development guide](docs/development.md) for conventions.

## Planned first vertical slice

The first meaningful slice is an idempotent wallet-to-wallet transfer backed by an immutable double-entry ledger. It will demonstrate explicit money types, balanced entries, atomic persistence, insufficient-funds handling, and idempotent requests before broader payment features are added. The [foundation record](docs/FOUNDATION.md) explains the implementation order and guardrails.

## Repository layout

```text
.
├── docs/                 # Architecture and development documentation
├── compose.yaml          # Local PostgreSQL and containerized application
├── src/                  # Spring Boot application and tests
├── pom.xml               # Maven build and verification configuration
├── mvnw.cmd              # Pinned Maven Wrapper for Windows
├── PROJECT_CONTEXT.txt   # Living product and engineering context
└── TASKS.md              # Sequenced setup and delivery tasks
```

The Maven application structure and Phase 0 development foundation are in place. Phases 1–3 add the ledger/wallet core, idempotent transfers, and reliable merchant payments/refunds.

## Getting started

Requires a Java 21 JDK. Maven does not need to be installed globally; the checked-in wrapper downloads its pinned Maven distribution on first use.

```powershell
docker compose up -d postgres
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

Then check the application health at <http://localhost:8080/actuator/health>.

See [configuration conventions](docs/configuration.md) for profiles, environment variables, and secret handling. [Docker instructions](docs/docker.md) cover the local database and full containerized stack.

For current direction and delivery sequencing, read:

- [Architecture notes](docs/architecture.md)
- [Development guide](docs/development.md)
- [Delivery backlog](docs/BACKLOG.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Configuration conventions](docs/configuration.md)
- [Docker instructions](docs/docker.md)
- [Development foundation record](docs/FOUNDATION.md)
- [Living project context](PROJECT_CONTEXT.txt)
- [Task list](TASKS.md)

## Non-goals

LedgerX is not intended to store real card credentials, claim PCI compliance, or simulate a full bank. It favors a small, coherent, explainable system over a collection of disconnected technologies.

## License

No license has been selected yet.
