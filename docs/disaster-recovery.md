# Backup, restore, and Kafka-loss recovery

PostgreSQL is the source of truth for money, immutable outbox events, consumer receipts, webhook
attempts, and reconciliation evidence. Keycloak has a separate PostgreSQL database. Kafka is a
delivery transport with a persistent volume, but its records can be rebuilt from the financial
outbox only while the corresponding outbox rows remain available. This runbook is for the
single-host Compose deployment; a multi-host failover design is separate work.

## Back up and verify

Keep the local destination outside the repository and restrict access to `.env`, local backups,
the rclone configuration, and the webhook encryption key ring. The `gdrive` rclone remote stores
the second copy in `gdrive:LedgerX-Backups`. Google Drive is an off-host copy; use a separate
rclone crypt remote if client-side encryption is required by your data policy.
Preserve the webhook keys, OIDC configuration, Caddy certificate state, and any Keycloak custom
files separately; the database archives do not include them. A lost webhook key can make restored
merchant signing secrets unusable.

Quiesce financial writes and Keycloak administration before a coordinated backup. Each `pg_dump`
is transactionally consistent within one database, but the two dumps are not one shared snapshot.
From the deployment host, run:

```powershell
New-Item -ItemType Directory -Path C:\LedgerXBackups -Force | Out-Null
.\scripts\backup-production.ps1 -Destination C:\LedgerXBackups -EnvFile .env -ApplyRetention
```

The script first creates local custom-format archives for both PostgreSQL services, checks that
each archive can be listed, writes SHA-256 hashes, and restores both archives into fresh,
network-isolated temporary containers. It checks LedgerX migration history and financial tables
and Keycloak's realm table. Only then does it upload the backup to the configured remote and
download all remote bytes for comparison. A failed local step or failed remote upload/check exits
nonzero. A successful local backup is preserved if the remote fails; investigate and retry with
`replicate-backup.ps1`. The remote defaults to `gdrive:LedgerX-Backups`; override it with
`LEDGERX_BACKUP_REMOTE` in `.env` or the host environment, or pass `-Remote`.

`rclone.exe` must be on PATH for the account running the backup. Its OAuth configuration remains
in that account's external rclone configuration file, never in this repository. Check the scheduled
task's PATH, Docker access, and rclone account before relying on unattended backups. Regularly
verify a retained local copy:

```powershell
.\scripts\verify-restore.ps1 -BackupDirectory C:\LedgerXBackups\BACKUP_ID
```

The optional `-ApplyRetention` prunes only script-marked backup-ID directories beneath the exact
`LedgerX-Backups` destination, after the new upload passes verification. It keeps one backup for
each of the latest 7 UTC days, 4 ISO weeks, and 6 calendar months. It does not prune local backups
or unrelated remote folders. Without the switch, no remote backup is deleted. Review the first
scheduled runs and retain extra local generations according to available disk space.

Schedule the command above daily in Windows Task Scheduler under the account that owns the rclone
configuration and can use Docker Desktop. Run `pwsh.exe` with `-NoProfile -File` followed by the
absolute script path and arguments; set the working directory to the repository root. Monitor the
task's nonzero exit code and newest verified manifest. A daily schedule alone permits almost 24
hours of data loss; choose frequency to meet the recovery point objective. The disposable
restore's elapsed time is only part of recovery time; measure a full application cutover before
declaring an RTO.

## Retrieve and rehearse an off-host backup

List backup generations and inspect the selected generation:

```powershell
rclone lsf --dirs-only gdrive:LedgerX-Backups
rclone ls gdrive:LedgerX-Backups/BACKUP_ID
```

Download into a new private directory outside the repository, then verify hashes and restore both
databases into disposable, network-isolated PostgreSQL containers. This does not touch production:

```powershell
New-Item -ItemType Directory -Path C:\LedgerXRecovery\BACKUP_ID -Force | Out-Null
rclone copy gdrive:LedgerX-Backups/BACKUP_ID C:\LedgerXRecovery\BACKUP_ID
.\scripts\verify-restore.ps1 -BackupDirectory C:\LedgerXRecovery\BACKUP_ID
```

For an already available local backup, the following command performs upload, byte comparison,
remote download, and the same disposable restore drill, then removes its temporary download:

```powershell
.\scripts\replicate-backup.ps1 -BackupDirectory C:\LedgerXBackups\BACKUP_ID -DownloadVerify
```

An isolated remote restore proves the archives can be recovered; it does not prove full service
cutover, login, reconciliation, or measured RPO/RTO. Follow the fresh-deployment procedure below
for a real incident or a full cutover rehearsal.

### Deployment evidence, 2026-09-29

| Check | Result |
| --- | --- |
| Local backup and isolated restore | Completed for both databases |
| Off-host upload to `gdrive:LedgerX-Backups` | Completed for retained backup `20260928T215244Z-117607e2` |
| Remote byte comparison | Completed with `rclone check --download` |
| Remote download and isolated restore | Completed for both databases |
| Full production cutover and measured RPO/RTO | Still pending |

## Restore into a fresh deployment

1. Declare an incident and stop LedgerX writes, event consumers/publisher, and Keycloak before
   restoring. Preserve the original volumes and host for forensics. Select a verified archive and
   compare both files with `manifest.json` using `verify-restore.ps1`.
2. Prepare a **new** Compose project and empty database volumes, with the same database names,
   credentials, webhook key ring, realm hostname, and compatible application version. Start only
   `postgres` and `keycloak-postgres`. Do not restore over the live databases.
3. Copy `ledgerx.dump` and `keycloak.dump` into their corresponding new containers. Run
   `pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"`
   against each archive. The disposal drill uses these same restore options. Restore the
   Keycloak database before starting Keycloak; avoid reimporting a conflicting realm.
4. Check `ledgerx.flyway_schema_history`, payment/ledger/outbox row counts, Keycloak realm and
   user access, and application startup. Run reconciliation and inspect its most recent run and
   findings. Test a known balance, payment, and identity login through the new private endpoint.
5. Only after those checks, switch traffic and enable event workers. Keep the original system
   powered down but intact until the incident is closed. Record the backup timestamp, first
   healthy request, and observed data gap as actual RPO/RTO evidence.

If the restored database is older than the Kafka volume, do not simply attach that volume:
messages may describe facts absent from the restored financial database. Treat it as a broker
loss and rebuild the event stream from the restored outbox.

## Kafka volume loss

1. Stop the publisher and consumers. Preserve the failed `kafka-data` volume, logs, and DLT
   evidence. Inspect the financial database and latest reconciliation findings before replay.
2. Start a fresh broker with the configured event and DLT topics. Confirm retention and the
   intended consumer group names. Keep downstream external integrations disabled while triaging.
3. For each retained `PUBLISHED` outbox event that must be recovered, call the operator outbox
   replay API with a stable incident-scoped idempotency key and reason. Include every event if
   rebuilding a completely empty broker. Pending/in-flight events already retry through the
   publisher. Preserve per-aggregate sequence when scheduling a large batch and monitor outbox
   age, retry counts, and broker acknowledgements.

   For the default database name/user, export the ordered event IDs on the deployment host,
   inspect the count and sample IDs, then submit through the operator API with a short-lived
   operator token in the current private shell. Replace the database identity if customized.

   ```powershell
   docker compose --env-file .env -f compose.production.yaml exec -T postgres psql -U ledgerx -d ledgerx -A -t -c "SELECT id FROM ledgerx.outbox_events WHERE status = 'PUBLISHED' ORDER BY aggregate_id, aggregate_sequence" | Set-Content D:\LedgerXBackups\broker-loss-ids.txt
   $secureToken = Read-Host 'Short-lived operator token' -AsSecureString
   $env:LEDGERX_OPERATOR_TOKEN = [pscredential]::new('token', $secureToken).GetNetworkCredential().Password
   .\scripts\replay-kafka-loss.ps1 -EventIdsPath D:\LedgerXBackups\broker-loss-ids.txt -ApiBaseUrl https://YOUR_LEDGERX_PUBLIC_DOMAIN -IncidentId INC-123 -WhatIf
   .\scripts\replay-kafka-loss.ps1 -EventIdsPath D:\LedgerXBackups\broker-loss-ids.txt -ApiBaseUrl https://YOUR_LEDGERX_PUBLIC_DOMAIN -IncidentId INC-123
   Remove-Item Env:LEDGERX_OPERATOR_TOKEN
   ```

   The script validates the whole list before sending, uses stable idempotency keys, and paces
   requests below the default mutation limit. Keep the ID list private and retain it with the
   incident record. A very large backlog needs an operator-approved rate and RTO plan.
4. Enable consumers. Existing `processed_events` receipts make duplicate application processing
   a no-op; a new consumer group can reconstruct its own view from the republished stream. Check
   the new broker's offsets and sample event IDs/payloads against the immutable outbox. Verify no
   duplicate webhook deliveries and run reconciliation.
5. DLT records without matching outbox events cannot be reconstructed from PostgreSQL. Keep DLT
   exports or a broker-volume backup if those records are required for forensics. Consumer group
   offsets and arbitrary non-outbox messages are likewise not recreated by outbox replay.

`KafkaOutboxIntegrationTest` publishes an event to the original broker, requests an audited
replay, and verifies the identical payload arrives in a new Kafka container. The test demonstrates
the application path for a single event. A full-volume loss drill against the deployed stack,
including all retained events, cutover timing, and external receiver behavior, is still required
before claiming a measured production RPO or RTO.

## Monitoring during an incident

Prometheus, Alertmanager, and Grafana are bound to host loopback ports 9090, 9093, and 3000.
Grafana provisions the private Prometheus source and a LedgerX operations dashboard. Set a
private password file at `GRAFANA_ADMIN_PASSWORD_FILE` before startup. The public Caddy
route returns 404 for `/actuator/prometheus`; Prometheus scrapes LedgerX on a private network.
Alertmanager has a separate outbound network for HTTPS notifications.
Rules in `monitoring/alerts.yml` cover scrape/database/Kafka outages, outbox retries and stalls, DLT
publication, dead/stalled webhooks, stale/failed reconciliation, critical integrity findings, and
repeated HTTP 5xx. The default Alertmanager receiver displays alerts locally at port 9093.
The Kafka availability gauge is `-1` when every Kafka role is intentionally disabled, `0` when
a required broker probe fails, and `1` when it succeeds.

To deliver notifications, set `LEDGERX_ALERT_WEBHOOK_URL` in a private shell environment to an
HTTPS receiver, run `scripts/configure-alertmanager.ps1`, set
`LEDGERX_ALERTMANAGER_CONFIG=./monitoring/alertmanager.local.yml` in `.env`, and restart
Alertmanager. Keep the generated ignored file private. Exercise one alert and confirm receipt
before enabling merchant workers. Adjust thresholds for the deployed polling and reconciliation
schedule. Structured ECS logs include a bounded `X-Request-Id` for each HTTP request; retain
them with the incident.
