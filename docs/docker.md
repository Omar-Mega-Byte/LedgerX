# Local PostgreSQL and containers

## Local database

Start PostgreSQL for host-based development:

```powershell
docker compose up -d postgres
docker compose ps
```

The database becomes healthy when the `postgres` service health check reports `healthy`. Its host connection defaults to `jdbc:postgresql://localhost:5432/ledgerx` with the development-only credentials documented in `.env.example`.

The named `postgres-data` volume preserves local data. To stop the service without deleting it, run `docker compose down`.

## Application container

Build and start the full local stack:

```powershell
.\mvnw.cmd verify
docker compose up --build
```

The multi-stage Dockerfile uses a pinned Maven/Java 21 builder to create the executable JAR inside
Docker, so a host-built JAR is not required. The application waits for PostgreSQL health, runs
Flyway before accepting traffic, and exposes `http://localhost:8080/actuator/health`. The container
runs as a non-root `ledgerx` user.

To remove the local database volume as well, run `docker compose down --volumes`. This permanently removes local development data.

## Image policy

Compose and Testcontainers use the same pinned PostgreSQL image, `postgres:17.11-alpine3.24`. Docker uses a locally cached image when available and downloads it only when it is absent. The application image uses a Java 21 build stage and an `eclipse-temurin:21-jre-alpine` runtime stage.

For the public self-hosted Compose stack with Caddy and Keycloak, see [the production deployment guide](production-deployment.md).
