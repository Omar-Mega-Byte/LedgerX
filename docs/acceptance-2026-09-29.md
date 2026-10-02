# Live deployment acceptance record — 2026-09-29

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
After controlled Caddy and Keycloak restarts, new independent probes returned HTTP 200 from all
three selected nodes for each public route:
[LedgerX post-restart report](https://check-host.net/check-report/4e243177kb62) and
[Keycloak post-restart report](https://check-host.net/check-report/4e24317ck241).

## 3. Keycloak acceptance

An existing realm lacked `ledgerx-web`, the operator role, the realm-role token mapper, and the
admin-only `ledgerx_owner_id` profile attribute. Realm import does not update an existing realm.
`scripts/reconcile-production-realm.ps1` added the missing definitions, checked the exact public
issuer, redirect, S256 PKCE, scope, role, and owner attribute, and passed a second idempotent run.
Real browser Authorization Code/PKCE sessions then reached LedgerX. The owner and operator paths
worked, owner-scoped resources were isolated, and an owner received 403 on an operator endpoint.
A malformed bearer token, forged owner header, and a real token issued by the wrong realm were
rejected with 401. A real access token remained accepted just after `exp` and was rejected with
401 at `exp + 65 seconds`. This matches Spring Security's documented
[60-second clock-skew allowance](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).

## 4. Business flow acceptance

Controlled browser sessions created synthetic personal and merchant owners, wallets, funding
journals, a merchant payment, refund, five concurrent payments, and idempotent payment replays.
The browser retrieved the created records. The ledger outbox published the resulting events;
Kafka audit processing and webhook delivery were observed. Later live checks found 27 payments,
7 refunds, 41 journals, zero unbalanced journals, zero negative wallet balances, zero
over-refunded payments, and zero pending outbox events. Reconciliation on the latest restored
database completed with zero findings. The next live scheduled reconciliation completed with zero
findings at 21:14:59 UTC.
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
receiver has not been provided, so ongoing operational alert delivery is **pending**. After an
application restart gap, the same stale-reconciliation alert fired again; the hourly schedule and
90-minute threshold are consistent. The next scheduled run completed at 21:14:59 UTC with zero
findings; the metric age reset and the alert cleared in Prometheus and Alertmanager. The gap itself
was real, so this alert was not treated as a false integrity finding.

## 7. Backup results

Before deployment, generation `20260929T155658Z-5d3df5af` was created, locally restored, uploaded
off-host, byte-compared, downloaded, and independently restored. After the synthetic business
flow, generation `20260929T162522Z-4a9e7a09` passed the same checks. Independent
`rclone check --download` reported four matching files and zero differences for the latter
generation. Script tests passed for corrupt-archive rejection and failed-upload nonzero exit.
Remote retention behavior is covered by script tests; no live pruning was requested. No unattended
Scheduled Task or service account was configured, so backup execution is presently manual.

A third generation, `20260929T210129Z-71ce68a8`, captured the later token-expiry test's completed
financial writes. Its local and downloaded off-host restores contained 27 payments, 7 refunds,
and 41 journals with no invariant violation. An independent `rclone check --download` again found
four matching files and zero differences.

## 8. Restore drill

The second and third generations were downloaded from the off-host remote and both PostgreSQL
archives were restored into disposable containers. `verify-restore.ps1` checked archive SHA-256
hashes, 15 successful Flyway migrations, immutable-ledger and refund-bound triggers, balanced journals,
nonnegative wallets, refund totals, and Keycloak realm table presence. It found 15 payments, 5
refunds, and 25 journals in the second restored LedgerX database; the third contained 27 payments,
7 refunds, and 41 journals. `verify-application-restore.ps1` then
started the current production-profile application on a separate restored database with workers
disabled and no host ports. It became healthy on schema v15 and completed a new reconciliation
run with zero findings for each tested generation. The disposable containers and network were
removed. Restored Keycloak login and public traffic cutover were not exercised; RPO/RTO were not
measured.

## 9. Failure and recovery

The application was recreated on the current image and returned healthy. A controlled Kafka stop
occurred while a browser payment committed; the outbox retained a PENDING event, then published it
after broker restart. An external receiver 503 caused retries and eventual delivery after recovery;
terminal replay was also proven. Alertmanager restarted and returned healthy. Caddy was restarted;
the public LedgerX and Keycloak routes returned HTTPS 200 afterward and HTTP still redirected 308.
Keycloak was restarted; its public issuer recovered with HTTP 200, and an authenticated admin API
realm check passed. A live PostgreSQL stop was rejected by automatic approval review, so no
database outage was performed. The reconciliation scheduler resumed at its next hourly slot after
the application restart and cleared the stale alert.

## 10. Browser E2E

The browser suite now covers real Keycloak PKCE login, owner and operator journeys, payment,
refund, event processing, owner privilege boundary, public navigation, forged owner header, and
malformed token denial. Guarded live mode requires explicit acknowledgement and HTTPS origins.
Optional live modes add the external webhook/replay exercise, Kafka outage, and real token expiry.
The two normal isolated Chromium scenarios and the live scenarios passed. One repeated receiver run was
interrupted while its max-attempt setting was eight; the temporary endpoint was manually disabled
and its delivery later completed. Subsequent final checks found no active test endpoints.

## 11. Load and concurrency smoke

Five distinct $0.10 payments were submitted concurrently, followed by five parallel replays of
one idempotency key. Five payment IDs were created; all replays returned the original ID and no
extra payment rows. In the last run, median creation latency was 177 ms and maximum was 279 ms.
This is a low-volume correctness smoke, not a capacity benchmark.

Observed operating signals: Prometheus reported LedgerX scrape, database, and Kafka availability
as 1; outbox stalled, dead webhooks, and critical reconciliation findings as 0. Both Kafka consumer
groups had zero lag at offset 34 after the last live run. PostgreSQL showed 11 LedgerX database
connections, the app used about 477 MiB in a single Docker stats sample, and no ERROR-level
application logs appeared in a 10-minute sample. These are point-in-time signals, not load
capacity results.

## 12. Problems discovered

- Existing Keycloak realm import had not supplied new browser/operator settings.
- The first live E2E rerun reused a unique system-code fixture and failed; the fixture now uses
  a UUID-specific code.
- Webhook workers were disabled despite an existing encryption key; they were enabled and tested.
- Alertmanager had no permanent outbound receiver, and backup had no unattended task.
- The temporary webhook receiver had a client-side timeout during an interrupted rerun, requiring
  manual endpoint disable; the endpoint is now disabled and the delivery completed.
- A restart gap left reconciliation stale until the next hourly run. Monitoring fired as designed,
  and the next run completed with zero findings and cleared the alert.
- The first expiry test expected immediate rejection at `exp + 1.5 seconds` and received 200.
  Spring Security's documented 60-second skew made that expectation incorrect; the revised live
  test proved rejection after the allowance.

## 13. Fixes applied

Added an idempotent Keycloak realm reconciliation script, a guarded production E2E path, external
webhook signature/retry/replay checks, a Kafka outage branch, a small concurrency smoke, stronger
restore integrity checks, and a disposable production-profile application restore drill. Updated
deployment and recovery runbooks. The original alert configuration was restored after the test;
production webhook workers remain enabled.

## 14. Remaining risks

- Supply and test a durable external alert receiver; local Alertmanager alone does not notify.
- Choose a Docker/rclone-capable account and create/monitor an unattended backup schedule.
- Exercise restored Keycloak login.
- Rehearse a full isolated cutover and measure actual RPO/RTO.
- Exercise a live PostgreSQL outage in a window where approval permits it. The attempted stop was
  blocked by automatic approval review.
- Perform larger load and multi-region reachability tests if those are service objectives.

## 15. Acceptance status

| Area | Status | Evidence boundary |
| --- | --- | --- |
| Repository verification | Verified | Maven, UI, browser, script, and Compose checks |
| Deployment verification | Verified with limits | Healthy production stack and public-path probes; regional timeouts |
| Security verification | Verified with limits | Real tokens, owner/operator denial, and post-skew expiry; restored login pending |
| Backup verification | Verified manually | Three remote generations and byte comparison; schedule pending |
| Restore verification | Verified with limits | Both DB archives and restored app/reconciliation; no cutover/login |
| Operational monitoring | Pending | External firing/resolved proof with temporary receiver; no durable receiver |
| End-to-end verification | Verified with limits | Real browser, Kafka, webhook, financial invariants; outage gaps above |

The deployment has substantial live acceptance evidence, but acceptance remains open while
durable alerting, unattended backups, and the listed recovery exercises remain outstanding.

## Verification commands and results

| Command | Result |
| --- | --- |
| Maven Wrapper `clean verify` with a private writable cache override | BUILD SUCCESS; 46 unit and 77 integration tests |
| `npm run check:ui` | Passed |
| `npm run test:e2e` | Two isolated Chromium scenarios passed; stack removed |
| `.\scripts\tests\backup-offhost.Tests.ps1` | Passed |
| `docker compose --env-file .env -f compose.production.yaml config --quiet` | Passed |
| `backup-production.ps1 -Destination <private-backup-directory> -EnvFile .env -DownloadVerify` | Three generations passed local, off-host, and downloaded restore checks |
| `rclone check --download <off-host-generation> <local-generation> -v` | Four matching files, zero differences |
| `verify-application-restore.ps1 -BackupDirectory <local-generation> -EnvFile .env` | Restored application healthy; new reconciliation `COMPLETED:0` |
| `node node_modules/@playwright/test/cli.js test --config playwright.config.mjs tests/e2e/identity-workbench.spec.mjs` with guarded live environment | Two scenarios passed, including real expiry rejection after clock skew |
| `.\scripts\reconcile-production-realm.ps1 -EnvFile .env` | Passed initial reconciliation, idempotent repeat, and post-restart check |
