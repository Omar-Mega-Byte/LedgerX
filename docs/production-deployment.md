# Self-hosted production deployment

This deployment uses one server, Docker Compose, Caddy, Keycloak, PostgreSQL, and Kafka. It does
not require a paid cloud service. The production Compose file publishes only Caddy's TCP ports 80
and 443 externally; monitoring UIs bind to loopback. LedgerX, both PostgreSQL databases, Kafka,
and Keycloak remain on private Docker networks.

## Before starting

1. Copy `.env.example` to the ignored `.env` file. Replace every `CHANGE_ME` value with the
   appropriate deployment credential, public domain, or email address. Do not commit `.env`.
   Supply `LEDGERX_WEBHOOK_ENCRYPTION_KEY` or `LEDGERX_WEBHOOK_ENCRYPTION_KEYS` with a
   base64-encoded 32-byte key before enabling webhook workers. The production Compose service
   passes the webhook and reconciliation settings from this file into LedgerX. Restrict access to
   the environment file and use the deployment secret store where available. Create a private
   Grafana admin password file at `GRAFANA_ADMIN_PASSWORD_FILE` before validating Compose.
   Grafana reads it as a Docker secret; keep it off version control and preserve it with the
   other deployment secrets. For the default path, run `./scripts/ensure-grafana-secret.ps1`
   before starting Compose. The script creates a random password only when the file is absent
   and never prints its value. If `GRAFANA_ADMIN_PASSWORD_FILE` specifies another path, pass
   that path to the script with `-Path`. If Grafana already initialized its data volume before
   the secret existed, creating the file does not change its persisted admin password; reset
   that account from the mounted secret before using the UI.
2. Register separate public DNS names for the LedgerX API and Keycloak. Set
   `LEDGERX_PUBLIC_DOMAIN` and `KEYCLOAK_PUBLIC_DOMAIN`; both names must resolve to the server's
   public IP address.
3. Keep the hostname values and `LEDGERX_OIDC_ISSUER_URI` consistent. The issuer must be
   `https://<KEYCLOAK_PUBLIC_DOMAIN>/realms/ledgerx`.
4. Reserve the server's LAN address in the router's DHCP settings so port forwards remain stable.
5. In the router, create TCP forward rules for external 80 to the reserved LAN
   address port 80 and external 443 to the reserved LAN address port 443. Do not forward PostgreSQL,
   Kafka, LedgerX port 8080, or Keycloak port 8080.
6. Allow incoming TCP 80 and 443 through Windows Firewall. Run PowerShell as Administrator, using
   the DHCP-reserved address and active network interface. These rules are limited to the selected
   interface and address:

   ```powershell
   $ledgerxLanIp = 'YOUR_RESERVED_LAN_IP'
   $networkInterface = 'YOUR_ACTIVE_INTERFACE'
   New-NetFirewallRule -DisplayName 'LedgerX Caddy HTTP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80 -LocalAddress $ledgerxLanIp -InterfaceAlias $networkInterface -Profile Public
   New-NetFirewallRule -DisplayName 'LedgerX Caddy HTTPS' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 443 -LocalAddress $ledgerxLanIp -InterfaceAlias $networkInterface -Profile Public
   ```

Some consumer ISPs block inbound ports or use carrier-grade NAT. If Caddy cannot obtain a certificate
after the DNS and forwarding checks, confirm that the server has a public IPv4 address and that its
ISP permits inbound 80/443 traffic.

## Start and verify

From the repository root, validate the resolved production configuration, build the LedgerX image,
and start the stack:

```powershell
./scripts/ensure-grafana-secret.ps1
docker compose --env-file .env -f compose.production.yaml config --quiet
docker compose --env-file .env -f compose.production.yaml up -d --build
docker compose --env-file .env -f compose.production.yaml ps
```

Use `docker compose -p ledgerx-dev -f compose.yaml` for a separate local stack. Do not use
`--remove-orphans` across the two Compose files under one project name: that can stop the other
stack. Only the local app publishes port 8080; the public stack serves health through Caddy HTTPS.
If Caddy cannot obtain a certificate, inspect its logs and verify that both public DNS names
point to the router's current public IPv4 address and inbound TCP 80/443 actually reach this host
through the router and firewall. Refresh stale dynamic-DNS records; if the router's WAN address
does not match its public-facing address, investigate carrier-grade NAT before changing host rules.

The `caddy` service is the only container with externally bound host ports. Caddy automatically obtains and renews
HTTPS certificates and proxies `LEDGERX_PUBLIC_DOMAIN` to `ledgerx:8080` and
`KEYCLOAK_PUBLIC_DOMAIN` to `keycloak:8080`. Caddy certificate data is persisted in the
`caddy-data` Docker volume; do not delete that volume casually.

Confirm the public health endpoint with:

```powershell
Invoke-WebRequest https://YOUR_LEDGERX_PUBLIC_DOMAIN/actuator/health
```

Swagger is intentionally disabled in production. Use a local profile for interactive API exercises.
The LedgerX container runs with a read-only filesystem, dropped Linux capabilities, and a private
database/event network. The application enforces HTTPS port 443 and validates public DNS results
for outbound merchant delivery. Apply host egress controls for the public HTTPS and DNS access the
service actually needs; test a merchant endpoint before enabling the dispatcher. The Caddy
configuration adds HSTS, a workbench content security policy, and browser security headers.

Open `https://YOUR_LEDGERX_PUBLIC_DOMAIN/` for the LedgerX workbench. The realm import creates the
public `ledgerx-web` client with an exact redirect URI and web origin from `LEDGERX_PUBLIC_DOMAIN`.
It uses Authorization Code with PKCE; no browser client secret is required. The workbench and API
share an origin, and access tokens stay in browser memory. A page refresh requires signing in
again; an existing Keycloak session normally makes this quick.

Realm import does not overwrite an existing realm during normal startup. If this realm predates
the workbench, add or update the `ledgerx-web` public client in Keycloak with Standard Flow and
S256 PKCE, `https://YOUR_LEDGERX_PUBLIC_DOMAIN/` as its exact valid redirect URI, and
`https://YOUR_LEDGERX_PUBLIC_DOMAIN` as its web origin. Add
`https://YOUR_LEDGERX_PUBLIC_DOMAIN/?signed-out=1` as a valid post-logout redirect URI. Keep the
`ledgerx-api` audience mapper on the access token.

## Keycloak owner mapping

The first startup imports the `ledgerx` realm and the `ledgerx-api` audience client. Sign in to the
Keycloak administration console at `https://YOUR_KEYCLOAK_PUBLIC_DOMAIN/admin` with the
bootstrap credentials from `.env`, then create users through the admin console. Public registration
is disabled.

For each user allowed to call LedgerX, set the user attribute `ledgerx_owner_id` to the UUID of an
existing active `wallet_owners` record. The imported realm defines this as an administrator-only
managed user-profile attribute. Its default `ledgerx-owner` client scope includes the UUID,
`ledgerx-api` audience, and realm roles in signed access tokens. LedgerX rejects a token missing
the owner or audience for owner-scoped routes.

Realm import does not update an existing realm. If the realm was imported before the managed
`ledgerx_owner_id` attribute and realm-role mapper were added, configure those in Keycloak before
creating or updating users. Give only administrators permission to edit the owner attribute, map
it to the `ledgerx_owner_id` access-token claim, and ensure the web client emits the
`ledgerx-api` audience and `ledgerx-operator` realm role. Test a fresh browser login after the
change; updating an attribute does not rewrite an already-issued token.

For operator users, assign the `ledgerx-operator` realm role in Keycloak. Spring Security checks
that role on `/api/v1/operations/**`; a normal wallet owner token cannot provision owners, suspend
wallets, or read global reconciliation evidence. An operator without a wallet owner claim can use
Operations but cannot enter the owner-scoped wallet workbench. Provisioning creates an empty USD
wallet and returns its owner ID; link that ID to a Keycloak user separately. There is no public
deposit or funding action. Use the existing controlled setup process for initial balances.

The workbench handles unknown outcomes of idempotent financial and provisioning requests by
retrying the same payload with the same key. Retain `operator_provisioning_requests` with the rest
of the financial database when migrating or restoring data.

## Event recovery and integrity

The Kafka broker persists to the `kafka-data` volume. New event and dead-letter topics use the
configured 14-day and 30-day defaults. Existing topic settings need independent verification.
Consumer failures retry and then move to the dead-letter topic; inspect and triage them before
requesting an audited outbox replay. Webhook key rotation and merchant delivery replay have
separate procedures. See [event operations](event-operations.md) for commands, operator routes,
retention, and reconciliation evidence.

Treat failed reconciliation runs, stale outbox events, missing consumer receipts, and dead webhook
deliveries as incident signals. The reconciliation job is read-only and leaves immutable findings;
it does not repair a financial discrepancy.
Prometheus, Alertmanager, and Grafana run on a private monitoring network and bind their UIs to
host loopback only. Prometheus and Grafana also attach to a separate bridge network so Docker can
publish their loopback ports; this grants those two containers outbound network access, so maintain
normal host egress controls. The public proxy blocks `/actuator/prometheus`. Configure an alert receiver and
exercise it before relying on notifications. See [disaster recovery](disaster-recovery.md) for
backup, isolated restore, and Kafka-loss procedures.

## Payment risk rollout

The V13 migration installs an inactive policy. Payments retain their previous behavior until an
operator activates a new version from the Operations workbench or
`POST /api/v1/operations/risk/policies`. The request requires the `ledgerx-operator` role, an
`Idempotency-Key`, a change reason, exact USD thresholds, and the expected current version.
Start with permissive thresholds and inspect the review queue before tightening them. A new
policy version is immutable; to revert, activate another version with the prior thresholds.

An open review expires after seven days. Approval lasts 24 hours and does not reserve funds or
move money; the original payer must retry the same payment payload and idempotency key. Blocks,
declines, and expirations leave the ledger and outbox unchanged. Keep `risk_policy_versions`,
`risk_assessments`, `risk_review_cases`, and `risk_review_actions` with the financial database
when backing up or restoring. `/actuator/metrics` is available only to operators in production;
`/actuator/health` reports when the active risk policy is unavailable.

Do not create a password-grant client merely to test the API. The included browser client uses
Authorization Code with PKCE and the exact LedgerX redirect URI.

## Operational checks

```powershell
netstat -ano | findstr ':80 '
netstat -ano | findstr ':443 '
docker compose --env-file .env -f compose.production.yaml logs --tail=100 caddy
docker compose --env-file .env -f compose.production.yaml logs --tail=100 keycloak
docker compose --env-file .env -f compose.production.yaml logs --tail=100 ledgerx
```

Stop the production stack without deleting its data:

```powershell
docker compose --env-file .env -f compose.production.yaml down
```

Removing named volumes permanently deletes the ledger, identity, and TLS state. Do that only as an
intentional fresh installation.
