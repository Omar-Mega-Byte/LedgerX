# Configuration conventions

## Profiles

LedgerX starts with the `local` Spring profile by default. Shared settings belong in `application.yml`; profile-specific overrides belong in `application-<profile>.yml` and activate only for their named profile.

Use `SPRING_PROFILES_ACTIVE` to choose a profile explicitly:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd spring-boot:run
```

The initial profiles are intentionally minimal:

| Profile | Purpose |
|---|---|
| `local` | Default developer workstation configuration |
| `test` | Reserved for automated-test overrides when they are needed |
| `prod` | Reserved for deployment-specific configuration; it must be activated explicitly |

## Environment variables and secrets

- Use uppercase, underscore-separated names. Application-owned settings use the `LEDGERX_` prefix, for example `LEDGERX_AUTH_JWT_SECRET`.
- Keep safe, non-secret examples in `.env.example`. The real `.env` file is ignored and must never be committed.
- Spring Boot does not load `.env` by itself. Supply variables through the shell, IDE run configuration, Docker Compose, or a deployment secret manager.
- Never provide real credentials, API keys, JWT signing material, private keys, or production endpoints as defaults in tracked configuration.
- Log configuration decisions without logging secret values.

## Health endpoint

Actuator exposes the liveness-friendly health endpoint at `GET /actuator/health`. Only `health` and `info` are exposed over HTTP at this stage; additional actuator endpoints require an explicit security and operational decision.
