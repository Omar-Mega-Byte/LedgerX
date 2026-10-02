# LedgerX

[![Verify](https://github.com/Omar-Mega-Byte/LedgerX/actions/workflows/verify.yml/badge.svg)](https://github.com/Omar-Mega-Byte/LedgerX/actions/workflows/verify.yml)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

**An auditable USD ledger for transfers, merchant payments, and refunds.** LedgerX is a Java 21 / Spring Boot modular monolith built to demonstrate the engineering behind financial state: immutable double-entry journals, retry-safe commands, database-enforced invariants, and traceable asynchronous effects.

> **Project scope:** LedgerX is a learning and portfolio project. It does not process real deposits or card credentials. The included single-host deployment and live acceptance record describe the tested scope; unattended recovery, full cutover, and measured RPO/RTO still need operational work.

## At a glance

| | |
| --- | --- |
| **Financial core** | USD wallets, immutable balanced journals, derived balances, transfers, merchant payments, partial/full refunds |
| **Reliability** | Idempotency, ordered row locks, PostgreSQL constraints, transactional outbox, Kafka consumers, webhook retries and replay |
| **Security** | Keycloak OIDC/JWT in production; explicit owner and operator boundaries; forgeable owner header limited to local/test |
| **Operations** | Reconciliation evidence, Actuator metrics, Prometheus, Grafana, Alertmanager, Docker Compose, backup and restore tooling |
| **Verification** | JUnit, Spring integration tests, PostgreSQL/Kafka Testcontainers, real-Keycloak Playwright flows, GitHub Actions |

## Contents

- [Architecture](#architecture)
- [API surface](#api-surface)
- [Swagger gallery](#swagger-gallery)
- [Engineering choices](#engineering-choices)
- [Technology](#technology)
- [Run locally](#run-locally)
- [Deployment and verified status](#deployment-and-verified-status)
- [Repository map](#repository-map)

## Architecture

The browser workbench and REST API ship in the same Spring Boot JAR. PostgreSQL stores financial truth. Kafka carries committed events from the transactional outbox to idempotent consumers and webhook delivery.

```mermaid
flowchart LR
  Client[Workbench or API client] -->|HTTPS| Edge[Caddy]
  Client -->|OIDC PKCE| IdP[Keycloak]
  IdP -->|signed JWT| Client
  Edge --> API[LedgerX Spring Boot API]
  subgraph LedgerX[LedgerX modular monolith]
    API -->|journals, domain state, outbox| DB[(PostgreSQL + Flyway)]
    Publisher[Outbox publisher] -->|polls committed events| DB
    Publisher --> Broker[(Kafka)]
    Broker --> Consumer[Idempotent consumers]
    Consumer --> DB
    Dispatcher[Webhook dispatcher] -->|signed HTTPS| Receiver[Merchant receiver]
    DB --> Dispatcher
  end
  Prom[Prometheus] -->|Actuator scrape| API
  Grafana[Grafana] --> Prom
  Prom --> Alerts[Alertmanager]
```

In production, Spring Security validates the Keycloak issuer and `ledgerx-api` audience. The signed `ledgerx_owner_id` claim scopes owner requests; the `ledgerx-operator` role protects operator routes. `X-LedgerX-Owner-Id` is available only in local/test profiles and is not authentication. See [architecture](docs/architecture.md), [configuration and security](docs/configuration.md), and [deployment](docs/production-deployment.md).

## API surface

The API is rooted at `/api/v1`. Financial commands use an `Idempotency-Key`; a matching retry returns the durable result, while reusing a key for a different request conflicts.

| Capability | Representative routes | Access |
| --- | --- | --- |
| Workspace and activity | `GET /me`, `GET /activity` | Authenticated owner |
| Transfers and journal | `POST /transfers`, `GET /transfers/{id}`, `GET /ledger-transactions/{id}` | Participating owner |
| Merchant payments and refunds | `POST /payments`, `GET /payments/{id}`, `POST /payments/{id}/refunds`, refund reads | Payer or merchant according to action |
| Webhook administration | Register endpoint, list deliveries and attempts, disable, rotate secret, replay | Merchant owner |
| Risk review | Policy versions, review cases, approve/decline actions | Operator; payer can read own case |
| Operations | Owner provisioning, wallet controls, reconciliation findings, outbox replay, key rotation | Operator |
| Local demo funding | `POST /demo/wallets/{id}/fundings` | Local/test only |

Production clients obtain a JWT through Keycloak Authorization Code with PKCE and send `Authorization: Bearer <JWT_TOKEN>`. Local Swagger UI is available at [`http://127.0.0.1:8080/swagger-ui/index.html`](http://127.0.0.1:8080/swagger-ui/index.html); its OpenAPI JSON is `/api-docs`. Both endpoints are disabled in the `prod` profile. The [API guide](docs/api.md) contains concrete requests, responses, status codes, and the full endpoint groups.

## Swagger gallery

These captures come from the generated OpenAPI reference. They show implemented routes, access and idempotency headers, and example request shapes using placeholders. They do not execute commands; use the local Swagger UI and [API guide](docs/api.md) for complete response schemas.

![LedgerX OpenAPI overview with authentication guidance and route groups](docs/images/api/swagger-overview.png)

<details>
<summary><strong>Wallets, transfers, payments, refunds, and activity</strong> · 6 captures</summary>

**Owner activity and history**

![GET activity endpoint and paging parameters](docs/images/api/owner-activity.png)

**Retry-safe transfer creation**

![POST transfers route with owner context, idempotency key, and example request](docs/images/api/transfer-create.png)

**Transfer retrieval**

![GET transfer route and lookup parameters](docs/images/api/transfer-read.png)

**Merchant payment with risk outcomes**

![POST payments route, payer and merchant wallet request, and risk behavior](docs/images/api/payment-create.png)

**Payment and refund status**

![GET payment route for reading payment and derived refund status](docs/images/api/payment-read.png)

**Partial and full refund command**

![POST refund route with merchant access and partial refund request example](docs/images/api/refund-create.png)

</details>

<details>
<summary><strong>Merchant webhook delivery</strong> · 2 captures</summary>

**Register a signed webhook endpoint**

![POST webhook endpoint route and registration request fields](docs/images/api/webhook-register.png)

**Replay a failed delivery**

![POST webhook delivery replay route](docs/images/api/webhook-replay.png)

</details>

<details>
<summary><strong>Operator workflows and recovery controls</strong> · 5 captures</summary>

**Provision an owner and wallet**

![POST operator owner provisioning route and request shape](docs/images/api/operator-provision-owner.png)

**Version a payment risk policy**

![POST risk policy route with expected version and policy request fields](docs/images/api/operator-risk-policy.png)

**Approve a payer retry**

![POST risk case approval route, which does not move money](docs/images/api/operator-approve-review.png)

**Inspect reconciliation findings**

![GET reconciliation run findings route](docs/images/api/operator-reconciliation.png)

**Replay a committed outbox event**

![POST outbox event replay route](docs/images/api/operator-outbox-replay.png)

</details>

<details>
<summary><strong>Local learning workflow</strong> · 1 capture</summary>

**Fund a local demo wallet with balanced test money**

![Local-only demo funding route and test funding request](docs/images/api/local-demo-funding.png)

</details>

## Engineering choices

| Choice | Reason |
| --- | --- |
| Immutable double-entry journal | Balances derive from posted debit/credit entries, so financial history stays explicit and corrections are additive. |
| Database-enforced invariants | Constraints and transactional posting protect balanced journals and prevent cumulative refunds from exceeding a payment. |
| Idempotency plus ordered locks | Client retries and concurrent commands resolve to one durable financial outcome without overdrawing a wallet. |
| Transactional outbox | Payment state and publishable events commit together; broker delivery can retry after a process or broker interruption. |
| Signed, retried webhooks | Receivers can verify origin and deduplicate stable event IDs while LedgerX retains attempts and supports replay. |
| Forward-only Flyway migrations | Database changes are versioned and applied history remains auditable. |

Risk review and approval do not move funds; only the original payer can retry an approved request. The workbench has no manual journal-edit action. More operational behavior is documented in [event operations](docs/event-operations.md) and [disaster recovery](docs/disaster-recovery.md).

## Technology

| Layer | Stack |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring Security, Spring Data JPA / Hibernate, Maven |
| Data | PostgreSQL 17, Flyway |
| Events | Apache Kafka, transactional outbox, idempotent consumers |
| Identity and edge | Keycloak OIDC/JWT, Caddy, HMAC-SHA-256 webhook signatures |
| Operations | Docker Compose, Actuator, Prometheus, Grafana, Alertmanager, PowerShell, rclone |
| Verification | JUnit 5, Mockito, Testcontainers, Playwright Chromium, GitHub Actions |

The clean local Maven run on 2026-10-01 passed **46 unit tests and 77 integration tests**. The real-Keycloak browser suite passed **2 scenarios**. CI also checks workbench types, local documentation links, production Compose configuration, backup scripts, and the production-profile browser flow.

## Run locally

Install Git, Docker Desktop, Java 21, and Node.js 24. The Maven Wrapper is included.

```powershell
git clone https://github.com/Omar-Mega-Byte/LedgerX.git
cd LedgerX
docker compose -p ledgerx-dev up -d --build --wait
```

Open the workbench at `http://127.0.0.1:8080/`, health at `http://127.0.0.1:8080/actuator/health`, or Swagger at `http://127.0.0.1:8080/swagger-ui/index.html`. The local profile uses a development owner UUID header; it does not provide public registration. Follow the [development guide](docs/development.md) to create disposable local data.

```powershell
.\mvnw.cmd verify
npm ci
npm run check:ui
npm run test:e2e
.\scripts\tests\backup-offhost.Tests.ps1
```

Maven and browser suites need Docker. Stop the local stack with `docker compose -p ledgerx-dev down`; omit `--volumes` to keep its data. See the [configuration template](.env.example) before changing local settings.

## Deployment and verified status

The production Compose configuration places PostgreSQL, Kafka, Keycloak, and LedgerX on private networks; Caddy is the public entry point. Monitoring UIs bind to loopback. The [dated acceptance record](docs/acceptance-2026-09-29.md) records the behavior observed on one deployment and lists remaining restore, cutover, alerting, and RPO/RTO work. Repository tests verify configuration and code paths; they do not establish multi-host availability or capacity.

## Repository map

```text
src/main/       Spring Boot API, Flyway migrations, static workbench
src/test/       Unit and Testcontainers integration tests
tests/e2e/      Keycloak-backed browser flows
docs/           Architecture, API, deployment, operations, recovery
docs/images/api Swagger route and schema captures
keycloak/       Realm import and reconciliation
monitoring/     Prometheus, Grafana, and Alertmanager configuration
scripts/        Deployment, backup, restore, and maintenance tools
```

**CV summary:** Built a Java 21/Spring Boot USD ledger with immutable double-entry posting, idempotent transfers and merchant payments, PostgreSQL refund invariants, Kafka outbox delivery, Keycloak owner/operator authorization, signed webhooks, and containerized deployment backed by integration and browser verification.

No license has been selected yet.
