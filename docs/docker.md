# Local PostgreSQL and containers

## Local database

Start PostgreSQL for host-based development:

```powershell
docker compose -p ledgerx-dev up -d postgres
docker compose -p ledgerx-dev ps
```

The database becomes healthy when the `postgres` service health check reports `healthy`. Its host
connection defaults to `jdbc:postgresql://localhost:5432/ledgerx`. Without a repository `.env`,
the local password is `ledgerx_local_password`; if `.env` sets `LEDGERX_DB_PASSWORD`, a host-run
application must use that same value. The full containerized stack below supplies the matching
database and Kafka settings to the application automatically.

The named `postgres-data` volume preserves local data. To stop the service without deleting it,
run `docker compose -p ledgerx-dev down`. A separate project name keeps this development database
away from the production database and network.

## Application container

Build and start the full local stack:

```powershell
.\mvnw.cmd verify
docker compose -p ledgerx-dev up -d --build
docker compose -p ledgerx-dev ps
Invoke-WebRequest http://127.0.0.1:8080/actuator/health
```

The multi-stage Dockerfile uses a pinned Maven/Java 21 builder to create the executable JAR inside
Docker, so a host-built JAR is not required. The application waits for PostgreSQL health, runs
Flyway before accepting traffic, and exposes `http://localhost:8080/actuator/health`. The container
runs as a non-root `ledgerx` user. The local database and application ports bind to host loopback,
not the LAN interface.

Do not use `--remove-orphans` when switching between the local and production Compose files under
one project name. The production stack has no host port 8080: its health endpoint is served by
Caddy over HTTPS. To remove the local database volume as well, run
`docker compose -p ledgerx-dev down --volumes`. This permanently removes local development data.

## Image policy

Compose and Testcontainers use the same pinned PostgreSQL image, `postgres:17.11-alpine3.24`. Docker uses a locally cached image when available and downloads it only when it is absent. The application image uses a Java 21 build stage and an `eclipse-temurin:21-jre-alpine` runtime stage.

For the public self-hosted Compose stack with Caddy and Keycloak, see [the production deployment guide](production-deployment.md).
