# Configuration conventions

## Profiles

LedgerX starts with the `local` Spring profile by default. Shared settings belong in `application.yml`; profile-specific overrides belong in `application-<profile>.yml` and activate only for their named profile.

Use `SPRING_PROFILES_ACTIVE` to choose a profile explicitly:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd spring-boot:run
```

The profiles are:

| Profile | Purpose |
|---|---|
| `local` | Default developer workstation configuration |
| `test` | Testcontainers-backed automated-test configuration |
| `prod` | Deployment-specific configuration; it must be activated explicitly and has no database credential defaults |

## Environment variables and secrets

- Use uppercase, underscore-separated names. Application-owned settings use the `LEDGERX_` prefix, for example `LEDGERX_OIDC_ISSUER_URI`.
- Keep safe, non-secret examples in `.env.example`. The real `.env` file is ignored and must never be committed.
- Spring Boot does not load `.env` by itself. Supply variables through the shell, IDE run configuration, Docker Compose, or a deployment secret manager.
- Never provide real credentials, API keys, JWT signing material, private keys, or production endpoints as defaults in tracked configuration.
- Log configuration decisions without logging secret values.

## Production identity

The `prod` profile requires `LEDGERX_OIDC_ISSUER_URI` and validates Keycloak JWTs against that
issuer's published keys. It also requires the `ledgerx-api` audience by default. Each authenticated
user who may call LedgerX must have a Keycloak `ledgerx_owner_id` user attribute whose value is the
UUID of an existing LedgerX wallet owner. The imported realm maps that attribute into a signed access
token claim of the same name.

This is an administrator-provisioned identity-to-owner mapping, not a public registration or wallet
funding mechanism. Keep the bootstrap administrator and database passwords only in the ignored
`.env` file or a deployment secret store.

## Signed webhooks

Webhook workers are disabled by default. Enable both `LEDGERX_WEBHOOK_CONSUMER_ENABLED` and
`LEDGERX_WEBHOOK_DISPATCHER_ENABLED` only after supplying a base64-encoded 32-byte AES key through
`LEDGERX_WEBHOOK_ENCRYPTION_KEY` or a versioned `LEDGERX_WEBHOOK_ENCRYPTION_KEYS` ring. The active
version is `LEDGERX_WEBHOOK_ENCRYPTION_KEY_VERSION`. Store keys in deployment secret management;
never commit, log, or use production material as a sample. Keep previous versions configured while
stored endpoints still use them. See [event operations](event-operations.md) for rotation.

Production accepts HTTPS endpoints on port 443 only and does not follow redirects. The outbound
client validates every DNS address when it opens a connection and rejects private, local, and
reserved destinations; it also rechecks a stored URL before delivery. Local/test configuration may
explicitly allow HTTP and local mock targets for development. A receiver validates the documented
`LedgerX-Timestamp` and `LedgerX-Signature` headers against the exact raw body, then deduplicates on
`LedgerX-Event-Id`; external delivery is intentionally at least once.

`LEDGERX_RECONCILIATION_ENABLED` enables the scheduled read-only integrity checks. These checks record
findings for operators but never repair, compensate, or mutate money movement.
The production Compose service forwards these webhook and reconciliation variables from the private
deployment environment file. `LEDGERX_SECURITY_MUTATION_LIMIT_PER_MINUTE` sets the per-JWT-subject
POST limit in the single application instance (default 120). For multiple application replicas,
add an edge or shared rate limiter. Keep host-level egress rules limited to the required public
HTTPS and DNS destinations before exposing merchant webhook registration.

## Health endpoint

Actuator exposes `health`, `info`, `metrics`, and `prometheus` over HTTP. The production filter
chain permits health probes without authentication, limits `/actuator/metrics` to operators, and
permits the private Prometheus scrape. Production Caddy returns 404 for the public
`/actuator/prometheus` route; the scrape reaches LedgerX only on the private monitoring network.
Owner APIs require authentication and operator APIs require the operator role.
