# Development guide

## Prerequisites

Install a Java 21 JDK, Git, and Docker Desktop. Docker is required for the PostgreSQL Testcontainers integration tests. Maven is invoked through the checked-in Maven Wrapper, so a global Maven installation is not required.

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

The standard local verification command is:

```powershell
.\mvnw.cmd verify
```

Use `*Test` for fast unit tests and `*IntegrationTest` for Docker-backed integration tests. The latter run during Maven's `verify` phase, not its unit-test phase.

## Commit guidance

Use small, coherent commits with a conventional prefix where practical, for example `docs:`, `chore:`, `build:`, `test:`, or `feat:`. Avoid mixing formatting-only changes with behavioral changes.

## Documentation maintenance

Update the README when setup, runnable capabilities, or major project claims change. Update [architecture.md](architecture.md) when a material design decision is made. `PROJECT_CONTEXT.txt` remains the broader living product context; newer explicit requirements take precedence.
