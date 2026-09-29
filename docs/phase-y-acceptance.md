# Phase Y: live production acceptance record

Date: 2026-09-29. This record concerns the single-host Windows/Docker Desktop deployment and
synthetic `phase-y-*` identities and transactions. It is a record of observed checks, not a claim
of a full disaster cutover or measured service-level objective.

## 1. Production environment tested

The existing nine-service production Compose project was initially stopped. Its data volumes and
network were present. The stack was started without deleting volumes, and all nine services became
up/healthy. The current merged LedgerX image was deployed after a verified backup. Flyway applied
the remaining migration and reached schema version 15. PostgreSQL, Kafka, Keycloak, Caddy,
Prometheus, Alertmanager, and Grafana were present. The application used the `prod` profile.
Initially the financial database had no business records and the deployed Keycloak realm had no
users. The local alert configuration had no outbound receiver and no LedgerX backup Scheduled Task
was found. The active credential audit found distinct database, Keycloak admin, and Grafana
credentials, each at least 20 characters; a private Grafana secret file and webhook encryption
key were present. No obvious placeholder credential was found. Only Caddy publishes Internet
ports 80/443; the monitoring UIs bind to loopback and database/event services remain private.

## 2. Public TLS and reverse proxy

Both public DNS names resolved to the deployed address. Local requests via the public hostnames
reached Caddy and returned HTTPS 200; HTTP redirected to HTTPS with 308. LedgerX sent HSTS, CSP,
frame and content-type protections. A TLS 1.3 handshake returned hostname-matching certificates
valid from 2026-09-28 through 2026-12-27 UTC. The public Keycloak issuer matched configuration.
Independent Internet probes confirmed HTTP 200 for LedgerX from the US and Japan (one Israeli
probe timed out), and for Keycloak from Germany, Hungary, Russia, and Ukraine (one Iranian probe
timed out): [LedgerX report](https://check-host.net/check-report/4e1b2ce8kfc8),
[Keycloak report](https://check-host.net/check-report/4e1b2800k708). This establishes reachability
from several external sites, with incomplete regional coverage.

## 3. Keycloak acceptance

An existing realm lacked `ledgerx-web`, the operator role, the realm-role token mapper, and the
admin-only `ledgerx_owner_id` profile attribute. Realm import does not update an existing realm.
`scripts/reconcile-production-realm.ps1` added the missing definitions, checked the exact public
issuer, redirect, S256 PKCE, scope, role, and owner attribute, and passed a second idempotent run.
Real browser Authorization Code/PKCE sessions then reached LedgerX. The owner and operator paths
worked, owner-scoped resources were isolated, and an owner received 403 on an operator endpoint.
A malformed bearer token, forged owner header, and a real token issued by the wrong realm were
rejected with 401. A real expired token was not exercised.

## 4. Business flow acceptance

Controlled browser sessions created synthetic personal and merchant owners, wallets, funding
journals, a merchant payment, refund, five concurrent payments, and idempotent payment replays.
The browser retrieved the created records. The ledger outbox published the resulting events;
Kafka audit processing and webhook delivery were observed. Final live SQL checks found 15
payments, 5 refunds, 25 journals, zero unbalanced journals, zero negative wallet balances, zero
over-refunded payments, and zero pending outbox events. Latest reconciliation: `COMPLETED:0`.
The synthetic test history remains in the immutable production ledger.

## 5. Webhook acceptance

The preexisting encryption key was present, so production consumer and dispatcher workers were
enabled. A controlled HTTPS receiver observed the full outbox → Kafka → consumer → dispatcher
path for payment and refund events. The endpoint retained an encoded path. Its first 503 response
created retry evidence, recovery to 200 delivered the payment, and a refund delivery reached DEAD
at the temporary three-attempt test limit. Replay delivered that terminal event and preserved
attempt history. HMAC-SHA256 signatures were recomputed over captured raw bodies and matched.
The live configuration was returned to eight maximum attempts; both temporary webhook endpoints
are disabled and both temporary external receiver tokens were deleted. Delivery is at least once;
idempotent financial posting prevented duplicate money effects.

## 6. Alerting acceptance

Prometheus evaluated `LedgerXReconciliationMissingOrStale`; Alertmanager sent a firing notification
at 16:11:06 UTC and a resolved notification at 16:16:05 UTC to a temporary external HTTPS
receiver. Both were captured. The temporary receiver was deleted and the original local-only
Alertmanager configuration restored; the live service reports `local-alerts`. A permanent external
receiver has not been provided, so ongoing operational alert delivery is **pending**.

## 7. Backup results

Before deployment, generation `20260929T155658Z-5d3df5af` was created, locally restored, uploaded
off-host, byte-compared, downloaded, and independently restored. After the synthetic business
flow, generation `20260929T162522Z-4a9e7a09` passed the same checks. Independent
`rclone check --download` reported four matching files and zero differences for the latter
generation. Script tests passed for corrupt-archive rejection and failed-upload nonzero exit.
Remote retention behavior is covered by script tests; no live pruning was requested. No unattended
Scheduled Task or service account was configured, so backup execution is presently manual.

## 8. Restore drill

The second generation was downloaded from the off-host remote and both PostgreSQL archives were
restored into disposable containers. `verify-restore.ps1` checked archive SHA-256 hashes, 15
successful Flyway migrations, immutable-ledger and refund-bound triggers, balanced journals,
nonnegative wallets, refund totals, and Keycloak realm table presence. It found 15 payments, 5
refunds, and 25 journals in the restored LedgerX database. `verify-application-restore.ps1` then
started the current production-profile application on a separate restored database with workers
disabled and no host ports. It became healthy on schema v15 and completed a new reconciliation
run with zero findings. The disposable containers and network were removed. Restored Keycloak
login and public traffic cutover were not exercised; RPO/RTO were not measured.

## 9. Failure and recovery

The application was recreated on the current image and returned healthy. A controlled Kafka stop
occurred while a browser payment committed; the outbox retained a PENDING event, then published it
after broker restart. An external receiver 503 caused retries and eventual delivery after recovery;
terminal replay was also proven. Alertmanager restarted and returned healthy. Caddy was restarted;
the public LedgerX and Keycloak routes returned HTTPS 200 afterward and HTTP still redirected 308.
Keycloak was restarted; its public issuer recovered with HTTP 200, and an authenticated admin API
realm check passed. A live PostgreSQL stop was rejected by automatic approval review, so no
database outage was performed.

## 10. Browser E2E

The browser suite now covers real Keycloak PKCE login, owner and operator journeys, payment,
refund, event processing, owner privilege boundary, public navigation, forged owner header, and
malformed token denial. Guarded live mode requires explicit acknowledgement and HTTPS origins.
Optional live modes add the external webhook/replay exercise and Kafka outage. The two normal
isolated Chromium scenarios and the live scenarios passed. One repeated receiver run was
interrupted while its max-attempt setting was eight; the temporary endpoint was manually disabled
and its delivery later completed. Subsequent final checks found no active test endpoints.

## 11. Load and concurrency smoke

Five distinct $0.10 payments were submitted concurrently, followed by five parallel replays of
one idempotency key. Five payment IDs were created; all replays returned the original ID and no
extra payment rows. In the last run, median creation latency was 117 ms and maximum was 156 ms.
This is a low-volume correctness smoke, not a capacity benchmark.

## 12. Problems discovered

- Existing Keycloak realm import had not supplied new browser/operator settings.
- The first live E2E rerun reused a unique system-code fixture and failed; the fixture now uses
  a UUID-specific code.
- Webhook workers were disabled despite an existing encryption key; they were enabled and tested.
- Alertmanager had no permanent outbound receiver, and backup had no unattended task.
- The temporary webhook receiver had a client-side timeout during an interrupted rerun, requiring
  manual endpoint disable; the endpoint is now disabled and the delivery completed.

## 13. Fixes applied

Added an idempotent Keycloak realm reconciliation script, a guarded production E2E path, external
webhook signature/retry/replay checks, a Kafka outage branch, a small concurrency smoke, stronger
restore integrity checks, and a disposable production-profile application restore drill. Updated
deployment and recovery runbooks. The original alert configuration was restored after the test;
production webhook workers remain enabled.

## 14. Remaining risks

- Supply and test a durable external alert receiver; local Alertmanager alone does not notify.
- Choose a Docker/rclone-capable account and create/monitor an unattended backup schedule.
- Exercise a real expired Keycloak token and restored Keycloak login.
- Rehearse a full isolated cutover and measure actual RPO/RTO.
- Exercise a live PostgreSQL outage in a window where approval permits it. The attempted stop was
  blocked by automatic approval review.
- Perform larger load and multi-region reachability tests if those are service objectives.

## 15. Acceptance status

| Area | Status | Evidence boundary |
| --- | --- | --- |
| Repository verification | Verified | Maven, UI, browser, script, and Compose checks |
| Deployment verification | Verified with limits | Healthy production stack and public-path probes; regional timeouts |
| Security verification | Verified with limits | Real tokens and owner/operator denial; expired token pending |
| Backup verification | Verified manually | Two remote generations and byte comparison; schedule pending |
| Restore verification | Verified with limits | Both DB archives and restored app/reconciliation; no cutover/login |
| Operational monitoring | Pending | External firing/resolved proof with temporary receiver; no durable receiver |
| End-to-end verification | Verified with limits | Real browser, Kafka, webhook, financial invariants; outage gaps above |

The deployment has substantial live acceptance evidence, but Phase Y is not fully closed while
durable alerting, unattended backups, and the listed recovery exercises remain outstanding.

## Verification commands and results

| Command | Result |
| --- | --- |
| `.\mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify` | BUILD SUCCESS; 46 unit and 77 integration tests |
| `npm run check:ui` | Passed |
| `npm run test:e2e` | Two isolated Chromium scenarios passed; stack removed |
| `.\scripts\tests\backup-offhost.Tests.ps1` | Passed |
| `docker compose --env-file .env -f compose.production.yaml config --quiet` | Passed |
| `.\scripts\backup-production.ps1 -Destination 'C:\Users\Tolis\Desktop\Java\LedgerX-backups' -EnvFile .env -DownloadVerify` | Both generations passed local, off-host, and downloaded restore checks |
| `rclone check --download 'gdrive:LedgerX-Backups/20260929T162522Z-4a9e7a09' 'C:\Users\Tolis\Desktop\Java\LedgerX-backups\20260929T162522Z-4a9e7a09' -v` | Four matching files, zero differences |
| `.\scripts\verify-application-restore.ps1 -BackupDirectory 'C:\Users\Tolis\Desktop\Java\LedgerX-backups\20260929T162522Z-4a9e7a09' -EnvFile .env` | Restored application healthy; new reconciliation `COMPLETED:0` |
| `.\scripts\reconcile-production-realm.ps1 -EnvFile .env` | Passed initial reconciliation, idempotent repeat, and post-restart check |
| `.\scripts\update-graphify.ps1` | Code graph rebuilt |
