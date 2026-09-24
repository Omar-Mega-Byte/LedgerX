# Self-hosted production deployment

This deployment uses one server, Docker Compose, Caddy, Keycloak, PostgreSQL, and Kafka. It does
not require a paid cloud service. The production Compose file publishes only Caddy's TCP ports 80
and 443; LedgerX, both PostgreSQL databases, Kafka, and Keycloak remain on the private Docker
network.

## Before starting

1. Copy `.env.example` to the ignored `.env` file. Replace every `CHANGE_ME` value with a long,
   unique password or email address. Do not commit `.env`.
   Replace `LEDGERX_WEBHOOK_ENCRYPTION_KEY` with a base64-encoded 32-byte key before enabling
   webhook workers. The production Compose service passes the webhook and reconciliation settings
   from this file into LedgerX.
2. Register a second public DNS name for the LedgerX API and place it in `LEDGERX_PUBLIC_DOMAIN`.
   `KEYCLOAK_PUBLIC_DOMAIN` is already set to `ledgerx-auth-elrfaay.duckdns.org`; both names must
   resolve to this server's public IP address.
3. Keep the hostname values and `LEDGERX_OIDC_ISSUER_URI` consistent. For the supplied Keycloak
   hostname, the issuer is `https://ledgerx-auth-elrfaay.duckdns.org/realms/ledgerx`.
4. Reserve this server's LAN address in the router's DHCP settings. It was `192.168.100.79` when
   checked, but a reservation—not this observed value—is what makes a port-forward stable.
5. In the router at `192.168.100.1`, create TCP forward rules for external 80 to the reserved LAN
   address port 80 and external 443 to the reserved LAN address port 443. Do not forward PostgreSQL,
   Kafka, LedgerX port 8080, or Keycloak port 8080.
6. Allow incoming TCP 80 and 443 through Windows Firewall. Run PowerShell as Administrator, using
   the DHCP-reserved address and active network interface. These rules are deliberately limited to
   the server's current Wi-Fi interface and address rather than every interface:

   ```powershell
   $ledgerxLanIp = '192.168.100.79'
   New-NetFirewallRule -DisplayName 'LedgerX Caddy HTTP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80 -LocalAddress $ledgerxLanIp -InterfaceAlias 'Wi-Fi' -Profile Public
   New-NetFirewallRule -DisplayName 'LedgerX Caddy HTTPS' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 443 -LocalAddress $ledgerxLanIp -InterfaceAlias 'Wi-Fi' -Profile Public
   ```

Some consumer ISPs block inbound ports or use carrier-grade NAT. If Caddy cannot obtain a certificate
after the DNS and forwarding checks, confirm that the server has a public IPv4 address and that its
ISP permits inbound 80/443 traffic.

## Start and verify

From the repository root, validate the resolved production configuration, build the LedgerX image,
and start the stack:

```powershell
docker compose --env-file .env -f compose.production.yaml config --quiet
docker compose --env-file .env -f compose.production.yaml up -d --build
docker compose --env-file .env -f compose.production.yaml ps
```

The `caddy` service is the only container with host ports. Caddy automatically obtains and renews
HTTPS certificates and proxies `LEDGERX_PUBLIC_DOMAIN` to `ledgerx:8080` and
`KEYCLOAK_PUBLIC_DOMAIN` to `keycloak:8080`. Caddy certificate data is persisted in the
`caddy-data` Docker volume; do not delete that volume casually.

Confirm the public health endpoint with:

```powershell
Invoke-WebRequest https://YOUR_LEDGERX_PUBLIC_DOMAIN/actuator/health
```

Swagger is intentionally disabled in production. Use a local profile for interactive API exercises.

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
Keycloak administration console at `https://ledgerx-auth-elrfaay.duckdns.org/admin` with the
bootstrap credentials from `.env`, then create users through the admin console. Public registration
is disabled.

For each user allowed to call LedgerX, set the user attribute `ledgerx_owner_id` to the UUID of an
existing active `wallet_owners` record. The imported `ledgerx-owner` client scope includes this UUID
and the `ledgerx-api` audience in signed access tokens. LedgerX rejects a token missing either.

For operator users, assign the `ledgerx-operator` realm role in Keycloak. Spring Security checks
that role on `/api/v1/operations/**`; a normal wallet owner token cannot provision owners, suspend
wallets, or read global reconciliation evidence. An operator without a wallet owner claim can use
Operations but cannot enter the owner-scoped wallet workbench. Provisioning creates an empty USD
wallet and returns its owner ID; link that ID to a Keycloak user separately. There is no public
deposit or funding action. Use the existing controlled setup process for initial balances.

The workbench handles unknown outcomes of idempotent financial and provisioning requests by
retrying the same payload with the same key. Retain `operator_provisioning_requests` with the rest
of the financial database when migrating or restoring data.

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
