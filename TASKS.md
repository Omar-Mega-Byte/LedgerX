## Foundation task history

The foundational tasks below are already implemented in this repository. GitHub branch protection, remote labels, and executed CI runs are external settings; their status is intentionally recorded separately from versioned repository evidence.

| # | Status | Task | Verify | Recommended commit |
|---|---|---|---|---|
| 2 | Complete | Add repository hygiene: `.gitignore`, `.gitattributes`, README, and initial docs | `git status`, review rendered Markdown | `docs: add project foundation documentation` |
| 3 | Confirmed locally | Create GitHub repository, add `origin`, push `main` | `git remote -v`, `git ls-remote origin` | No extra commit; push tasks 1–2 |
| 4 | Complete | Create prioritized backlog, roadmap, Definition of Done, and issue workflow | Review `docs/BACKLOG.md` and `docs/ROADMAP.md` | `docs: add initial delivery backlog` |
| 5 | Complete | Bootstrap Spring Boot + Maven Wrapper, health endpoint, and one context-load test | `.\mvnw.cmd verify` | `chore: bootstrap Spring Boot application` |
| 6 | Complete | Add Maven quality baseline: Java 21 enforcement and formatter checks | `.\mvnw.cmd verify` | `build: add quality verification` |
| 7 | Complete | Add configuration profiles and secret/environment-variable conventions | Start with `local` profile; confirm no secrets are tracked | `chore: configure local application profiles` |
| 8 | Complete | Add Docker Compose PostgreSQL development environment | `docker compose up -d`; database becomes healthy | `chore: add local PostgreSQL environment` |
| 9 | Complete | Add JPA, PostgreSQL driver, Flyway, and the initial infrastructure migration | Application starts; Flyway records migration | `chore: configure database migrations` |
| 10 | Complete | Establish test layers: unit-test naming and a PostgreSQL Testcontainers integration test | `.\mvnw.cmd verify` with Docker running | `test: establish database integration testing` |
| 11 | Implemented; Docker Compose validation pending | Add Dockerfile and verify the application can run in a container | `docker compose up --build` and health check succeeds | `build: containerize application` |
| 12 | Workflow committed; remote execution pending | Add GitHub Actions CI: JDK 21, Maven cache, `verify` on pushes and pull requests | Green Actions run | `ci: add Maven verification workflow` |
| 13 | Templates complete; external settings pending | Configure GitHub branch protection and issue labels/templates | PR requires green CI | No code commit |
| 14 | Complete | Document the completed foundation and choose the first business vertical slice | Docs accurately match the repository | `docs: record development foundation decisions` |
