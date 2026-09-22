# Self-hosted production deployment

This deployment uses one server, Docker Compose, Caddy, Keycloak, PostgreSQL, and Kafka. It does
not require a paid cloud service. The production Compose file publishes only Caddy's TCP ports 80
and 443; LedgerX, both PostgreSQL databases, Kafka, and Keycloak remain on the private Docker
network.

## Before starting

1. Copy `.env.example` to the ignored `.env` file. Replace every `CHANGE_ME` value with a long,
   unique password or email address. Do not commit `.env`.
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

## Keycloak owner mapping

The first startup imports the `ledgerx` realm and the `ledgerx-api` audience client. Sign in to the
Keycloak administration console at `https://ledgerx-auth-elrfaay.duckdns.org/admin` with the
bootstrap credentials from `.env`, then create users through the admin console. Public registration
is disabled.

For each user allowed to call LedgerX, set the user attribute `ledgerx_owner_id` to the UUID of an
existing active `wallet_owners` record. The imported `ledgerx-owner` client scope includes this UUID
and the `ledgerx-api` audience in signed access tokens. LedgerX rejects a token missing either.

Do not create a password-grant client merely to test the API. A browser, mobile application, or BFF
should use an Authorization Code flow with PKCE and a deliberately chosen redirect URI when a client
is added. That client is outside this backend deployment scope.

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
