# Architecture notes

## Current state

LedgerX has not selected a final service topology or implemented a domain model. It will begin as a modular monolith unless a concrete boundary justifies extraction. This preserves simple transactions and deployment while keeping modules organized around the domain.

## Intended domain boundaries

The initial modules are expected to be:

| Area | Responsibility |
|---|---|
| Identity | Authentication, authorization, and merchant/user ownership |
| Wallet and ledger | Accounts, balances, immutable ledger transactions, and entries |
| Payments and transfers | Payment state transitions, transfers, fees, and refunds |
| Reliability | Idempotency records, outbox events, and consumer deduplication |
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

## Decisions to defer

The following need evidence from real use cases before being fixed:

- calculated versus derived wallet balances;
- optimistic locking, pessimistic locking, or atomic SQL updates for each race condition;
- module/service extraction boundaries;
- Redis usage and cache authority;
- Kafka topology, retry policy, and schema versioning;
- authentication mechanism and external identity integration.

Architecture changes that alter these assumptions should update this document and include tests for the affected failure mode.
