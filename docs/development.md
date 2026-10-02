# Development guide

## Prerequisites

Install a Java 21 JDK, Git, and Docker Desktop. Node.js 24 is needed for UI and browser checks. Docker is required for the PostgreSQL/Kafka integration and browser tests. Maven is invoked through the checked-in Maven Wrapper, so a global Maven installation is not required.

## Run a disposable local workbench

From a fresh clone, start the loopback-only local stack:

```powershell
docker compose -p ledgerx-dev up -d --build --wait
docker compose -p ledgerx-dev ps
```

Open `http://127.0.0.1:8080/` or `http://127.0.0.1:8080/swagger-ui/index.html`. The local profile deliberately has no public owner-provisioning endpoint. To create **disposable local test owners and empty wallets** in the local database, run this once and copy the printed PERSON and MERCHANT owner UUIDs:

```powershell
$seedSql = @'
WITH created_owners AS (
  INSERT INTO ledgerx.wallet_owners (id, owner_type, status, created_at, updated_at)
  SELECT gen_random_uuid(), kind, 'ACTIVE', now(), now()
  FROM (VALUES ('PERSON'), ('MERCHANT')) AS kinds(kind)
  RETURNING id, owner_type
), created_wallets AS (
  INSERT INTO ledgerx.ledger_accounts
    (id, account_kind, account_type, owner_id, system_code, currency, status, created_at)
  SELECT gen_random_uuid(), 'WALLET', 'LIABILITY', id, NULL, 'USD', 'ACTIVE', now()
  FROM created_owners
  RETURNING id, owner_id
)
SELECT o.owner_type, o.id AS owner_id, w.id AS wallet_id
FROM created_owners o JOIN created_wallets w ON w.owner_id = o.id;
'@
$seedSql | docker compose -p ledgerx-dev exec -T postgres psql -X -v ON_ERROR_STOP=1 -U ledgerx -d ledgerx
```

Enter the PERSON owner UUID in the local workbench. Its **Add demo money** action posts a balanced test-money journal; use the printed merchant wallet UUID to try a payment. Sign out and enter the MERCHANT owner UUID to inspect and refund it. This fixture uses the local development owner header, which is forgeable and must never be used as production authentication. A repository `.env` can override the default local database user, password, or port; adjust the `psql` user above if you have changed it. Stop the local stack with `docker compose -p ledgerx-dev down` to retain its volume.

## Repository hygiene

- Do not commit local environment files, credentials, private keys, logs, build output, or IDE metadata.
- Put every required configuration key in `.env.example` or documented application configuration, with safe placeholder values only.
- Keep text files normalized to LF. Windows launcher scripts retain CRLF through `.gitattributes`.
- Do not commit generated JAR/WAR files or Maven/Gradle caches.

## Implementation conventions

- Prefer package-by-feature and small, focused services.
- Keep controllers thin; map request/response DTOs at the application boundary.
- Use constructor injection and typed configuration properties.
- Define transaction boundaries deliberately around a business operation.
- Use UTC-oriented time types such as `Instant` or `OffsetDateTime`.
- Never log passwords, access tokens, refresh tokens, raw API keys, webhook secrets, or private keys.
- Treat every externally triggered state-changing operation as a candidate for idempotency, retry, and concurrent execution.

## Validation expectations

Each meaningful change should include verification appropriate to its risk:

| Change | Expected verification |
|---|---|
| Domain rule | Focused unit tests, including invalid and boundary cases |
| Persistence or transaction behavior | PostgreSQL-backed `*IntegrationTest`, run by Maven Failsafe with Testcontainers |
| API behavior | Controller/application test for status, validation, and authorization |
| Concurrent money movement | Repeatable concurrent test proving invariant preservation |
| Documentation/configuration | Review rendered Markdown and confirm no secrets are tracked |
| Production identity and browser flow | Run the isolated real-Keycloak Chromium suite |

The standard local verification command is:

```powershell
.\mvnw.cmd verify
```

Use `*Test` for fast unit tests and `*IntegrationTest` for Docker-backed integration tests. The latter run during Maven's `verify` phase, not its unit-test phase.
Spring integration tests with class-scoped Testcontainers close their cached application context
after the class. Otherwise scheduled workers can keep using a datasource after JUnit stops its
PostgreSQL container, creating connection errors and delaying test JVM shutdown.

## Real identity and browser verification

Install Node.js 24, then run from the repository root:

```powershell
npm ci
npx playwright install chromium
npm run test:e2e
```

The runner creates a unique Compose project with ephemeral credentials and free loopback ports.
It starts the production Spring profile, the imported Keycloak realm, PostgreSQL, Kafka, and a
local-CA Caddy HTTPS edge; Playwright ignores that disposable certificate. It creates test users
through Keycloak administration, then signs them in through the browser's Authorization Code and
S256 PKCE flow. The scenario checks unauthenticated access, operator-only provisioning, signed
owner claims, cross-owner isolation, browser payment/refund, and Kafka audit consumption. A
balanced SQL journal supplies the initial test-only balance because production has no public
funding action. The runner removes only its uniquely named Compose project and data when done.
CI installs Chromium and its Linux dependencies before running this suite.

The README's API gallery captures the local Swagger UI's documented request and response contracts. These examples use generated OpenAPI placeholders and do not execute financial requests. Capture them only from the isolated local profile after changing the API reference; production disables `/swagger-ui/index.html` and `/api-docs`.

This suite does not verify a public certificate, external merchant webhook delivery, or a live
deployment's Keycloak configuration. Continue those checks during deployment acceptance.

## Local demo wallet funding

In the local profile, open a wallet in the workbench and choose **Add demo money**. Each top-up accepts
$0.01–$10,000.00 USD, requires an idempotency key, and writes a balanced clearing debit and wallet
credit. The updated balance and top-up appear in the wallet and Activity views. The endpoint is
`POST /api/v1/demo/wallets/{walletId}/fundings`; it requires the local owner header and is absent in
the production profile. These are test funds in the local database, not a payment or deposit.

## Commit guidance

Use small, coherent commits with a conventional prefix where practical, for example `docs:`, `chore:`, `build:`, `test:`, or `feat:`. Avoid mixing formatting-only changes with behavioral changes.

## Documentation maintenance
