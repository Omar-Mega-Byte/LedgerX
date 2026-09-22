# Architecture notes

## Current state

LedgerX is a Java 21 modular monolith with PostgreSQL as its financial source of truth. It implements active PERSON/MERCHANT wallet ownership, exact USD money, immutable balanced ledger journals, idempotent wallet transfers, payer-authorized merchant payments, merchant-authorized refunds, and a transactional outbox. PostgreSQL constraints/triggers and focused Testcontainers tests protect financial facts and derived wallet balances.

Kafka propagates committed payment/refund events asynchronously; it is never the authority for money. The outbox publisher uses short leased claims and retries, so a crash may duplicate delivery but cannot silently lose a committed event. Consumers deduplicate durable local effects by event ID.

## Domain boundaries

The current modules are:

| Area | Responsibility |
|---|---|
| Access and wallet | Keycloak-signed production caller context, local/test development seam, and active PERSON/MERCHANT wallet ownership |
| Wallet and ledger | Accounts, derived balances, immutable ledger transactions, and entries |
| Transfers | Idempotent USD wallet-to-wallet transfers |
| Payments | PERSON-to-MERCHANT payments, compensating refunds, and derived refund state |
| Reliability | Payment/refund idempotency records, outbox events, publisher leases, and consumer deduplication |
| Webhooks | Signed event delivery, retries, and delivery audit history |

These are organization boundaries, not separate deployables at this stage.

## Financial invariants

Financial operations must make the following rules explicit in code, database constraints where applicable, and tests:

1. Monetary amounts use `BigDecimal` or a dedicated money type; never `float` or `double`.
2. Currency is explicit for every material amount.
3. A posted ledger transaction has entries that sum to zero per currency.
4. Posted ledger entries are append-only. Corrections use compensating entries rather than mutation.
5. A command retry with the same idempotency scope, key, and payload has one financial effect and can replay its original result.
6. Concurrent debits cannot create a negative available balance unless an explicitly designed credit facility permits it.

## Reliability model

The desired flow for a state-changing command is:

```text
HTTP command + idempotency key
          |
          v
validate ownership, state, and business rules
          |
          v
single database transaction
  ├─ persist domain state and balanced ledger entries
  ├─ save idempotency response/metadata
  └─ insert an outbox event
          |
          v
background publisher sends outbox event to Kafka
          |
          v
idempotent consumers process downstream effects
```

The database is the source of truth for committed financial state. Kafka is used for asynchronous propagation, not as the sole record of money movement.

## Authentication boundary

The `prod` profile runs LedgerX as an OAuth2 resource server. Caddy terminates TLS, Keycloak issues
signed JWTs, and LedgerX validates the issuer and `ledgerx-api` audience before a controller runs.
The signed `ledgerx_owner_id` claim maps the authenticated Keycloak user to a pre-existing
`wallet_owners.id`; the claim is resolved into `OwnerContext` before entering financial application
services. This keeps HTTP/OIDC concerns out of transfer and payment rules.

The `X-LedgerX-Owner-Id` header remains for local and test profiles only. It is forgeable and must
never be treated as authentication. In production it is ignored whenever a JWT is present, and an
unauthenticated request is rejected by the resource-server filter chain.

## Decisions to defer

The following need evidence from real use cases before being fixed:

- module/service extraction boundaries;
- Redis usage and cache authority;
- end-user application clients and onboarding flows (Keycloak is the deployed identity provider).
- payment-provider authorization, capture, fees, settlement, chargebacks, and FX;
- webhook delivery/replay operations and reconciliation policy;
- event retention and dead-letter operations policy.

Architecture changes that alter these assumptions should update this document and include tests for the affected failure mode.
