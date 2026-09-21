# Phase 2 — Idempotent Wallet Transfers API

**Status: design confirmed for later implementation.**

## 1. Goal

Deliver LedgerX's first externally triggered money movement: a synchronous USD wallet-to-wallet transfer API that is atomic, auditable, safe under concurrent requests, and idempotent.

Phase 2 is intentionally one complete vertical slice. It turns the Phase 1 ledger into a useful business operation without adding payments, messaging, authentication infrastructure, or a second accounting model.

## 2. Starting Point from Phase 1

Phase 1 is the implementation truth:

- USD-only Money uses BigDecimal with scale 2 and rejects rounding.
- A wallet is an owned, credit-normal LIABILITY LedgerAccount; its balance is derived from immutable entries.
- LedgerPostingService locks all affected accounts in ascending UUID order, validates active account/owner state and non-negative wallet balances, and writes a balanced journal in one transaction.
- V2/V3 enforce USD, account ownership shape, entry currency consistency, balanced journal totals at commit, and ledger/account immutability in PostgreSQL.
- WalletAccountService creates internal owner, wallet, and system accounts but exposes no HTTP surface.
- PostgreSQL Testcontainers tests prove balance, rollback, immutability, lifecycle, and competing-debit behavior.

### Documentation discrepancies

Several older documents still describe Phase 1 as future work: README says financial features are not implemented, architecture.md says no domain model exists, and the roadmap does not mark Phase 1 complete. Phase1.md and source code correctly record Phase 1 as implemented. The Phase 1 component table also names LedgerBalanceRepository, while the implementation uses LedgerEntryRepository for balance aggregation. This session changes only Phase2.md; reconcile those documents when implementation work next updates project claims.

The Phase 0 foundation says authentication precedes exposing transfers, while the roadmap makes idempotent transfers the Phase 2 objective and does not schedule an authentication mechanism. The explicit Phase 2 decision below is a temporary, clearly non-secure owner header boundary rather than prematurely adding JWT or Spring Security.

## 3. Scope

### Included

- Synchronous wallet-to-wallet USD transfers between two existing active wallets.
- PERSON and MERCHANT owners may both send and receive: PERSON-to-PERSON, PERSON-to-MERCHANT, MERCHANT-to-PERSON, and MERCHANT-to-MERCHANT are equally valid.
- A completed-transfer business record linked one-to-one to its immutable ledger transaction.
- POST transfer creation and GET transfer lookup REST endpoints.
- PostgreSQL-backed idempotency, request fingerprinting, replay, and conflict behavior.
- Request validation, consistent error responses, temporary ownership checks, and REST DTOs.
- Reuse of Phase 1's LedgerPostingService and its ordered pessimistic lock protocol.
- Flyway migrations plus focused unit, MockMvc, and PostgreSQL Testcontainers tests.

### Explicitly Deferred

- JWT/session authentication, roles, RBAC, actual identity-provider integration, and wallet onboarding APIs.
- Wallet-balance and wallet-history HTTP endpoints; the existing internal balance query remains sufficient for Phase 2.
- Payments, deposits, withdrawals, merchant settlement, fees, scheduled transfers, reversals, refunds, and chargebacks.
- Multi-currency, FX, credit limits, holds, and available-versus-ledger balance separation.
- Outbox events, Kafka, notifications, webhooks, Redis, rate limiting, reconciliation, and advanced observability.

Funding remains internal to development setup, WalletAccountService, and test fixtures. Phase 2 does not add a public deposit, funding, or wallet-onboarding endpoint.

The roadmap mentions transfer domain events in Phase 2. They are deliberately deferred with the outbox to Phase 3: emitting an unreliable in-process event after a financial commit would teach the wrong reliability model.

## 4. Business Flow

The only financial write flow is:

    POST /api/v1/transfers
      -> validate header and DTO shape
      -> canonicalize Money and calculate request fingerprint
      -> begin one database transaction
      -> claim idempotency key or replay its completed transfer
      -> load source/destination account metadata and validate ownership/kind/currency
      -> call Phase 1 LedgerPostingService
           -> lock both accounts in ascending UUID order
           -> validate state/owners/funds
           -> insert balanced debit/credit journal
      -> insert immutable transfer record linked to that journal
      -> mark idempotency record COMPLETED
      -> commit
      -> return 201 for the first result or 200 for a replay

The source wallet is debited and the destination wallet is credited because both are LIABILITY accounts. LedgerPostingService remains responsible for accounting and balance invariants; TransferApplicationService owns the business meaning, idempotency, and API result.

The ledger, transfer, and completed idempotency record live in the same transaction. A constraint failure, insufficient funds, or transfer persistence failure rolls back all three. There can be no successful transfer without a journal or a committed journal without its transfer record.

## 5. Key Engineering Decisions

| Decision | Choice | Why |
|---|---|---|
| Phase name | Idempotent Wallet Transfers API | It is the roadmap's next coherent vertical slice. |
| Execution | Synchronous and immediately final | There is no external provider or asynchronous workflow yet. |
| Transfer lifecycle | Persist only completed transfers; no PENDING/FAILED transfer states | A transfer either commits atomically with the ledger or does not exist. Extra states would be unused ceremony. |
| Financial authority | Ledger remains authoritative; transfer is an immutable business/audit record | The transfer makes queries and API responses meaningful without duplicating accounting truth. |
| Idempotency store | PostgreSQL table in the same database | It is durable and transactional with the financial write; Redis adds no Phase 2 value. |
| Idempotency response | Reconstruct a response from the stored completed transfer | Avoids a fragile JSON-response blob while replaying the authoritative result. |
| Account concurrency | Reuse LedgerPostingService locks; TransferApplicationService takes no second account-lock strategy | One ordered locking protocol prevents conflicting rules and opposite-direction deadlocks. |
| Temporary caller boundary | Required X-LedgerX-Owner-Id header | It exercises ownership structure now but is explicitly not authentication. |
| API surface | POST /transfers and GET /transfers/{id} only | These prove the complete transfer workflow without exposing unsecured wallet browsing. |

## 6. Transfer Model

A Transfer is the business record of one completed wallet movement. It is not the ledger itself.

| Field | Meaning |
|---|---|
| id | Server-generated UUID; public transfer reference. |
| sourceWalletAccountId | The debited wallet account. |
| destinationWalletAccountId | The credited wallet account. |
| amount and currency | A queryable copy of the requested USD Money. It must match the journal created by this operation. |
| ledgerTransactionId | The one immutable ledger transaction that accounts for this transfer. |
| completedAt | UTC Instant when the atomic operation completed. |

The transfer row has no mutable status column. Its existence means COMPLETED, which is the status exposed in the response. A failed operation persists no transfer row. Reversals will later be new business records with compensating ledger transactions, never a mutation of this row.

Source/destination account IDs and amount are copied because transfer history needs a concise business query model. The entry lines remain the only accounting source of truth. The application constructs both from one command and tests their correspondence; a normal SQL CHECK cannot compare a transfer row to the aggregate of journal entries.

## 7. Transfer Lifecycle

The lifecycle is deliberately small:

    requested -> COMPLETED

Requested is in-memory work inside the database transaction, not a stored state. COMPLETED is terminal and immutable. Validation, ownership, funds, lock, or persistence failures roll back and produce no transfer.

PENDING belongs to future asynchronous/external-payment work. FAILED belongs only in a future workflow that has a durable business reason to retain failures. Neither adds value to an all-or-nothing internal transfer.

## 8. Ledger Integration

TransferApplicationService creates a transfer UUID before posting and gives LedgerPostingService two PostingLines:

| Wallet | Ledger side | Effect on owner-facing balance |
|---|---|---|
| Source LIABILITY wallet | DEBIT | Decreases |
| Destination LIABILITY wallet | CREDIT | Increases |

The journal description includes the transfer UUID for operator readability. LedgerPostingService returns the ledger transaction UUID; the transfer row links to it with a unique foreign key. TransferApplicationService must never insert LedgerEntry records directly, alter balances, or catch-and-ignore a ledger failure.

## 9. Idempotency Model

The client must send a non-blank Idempotency-Key header, at most 255 characters. Its scope is the temporary caller financial owner, not the source wallet: the unique key is (owner_id, idempotency_key).

The request fingerprint is SHA-256 of a canonical string containing source wallet UUID, destination wallet UUID, canonical two-decimal amount, and currency. Owner is the scope column rather than fingerprint content. Canonicalization happens after DTO parsing, so 10 and 10.00 have the same fingerprint.

Within the transfer transaction, a custom PostgreSQL repository performs INSERT ... ON CONFLICT DO NOTHING for a PROCESSING record:

| Situation | Behavior |
|---|---|
| Key is newly claimed | Continue with validation, ledger posting, transfer persistence, then change record to COMPLETED. |
| Same owner/key/fingerprint is COMPLETED | Load its transfer and return the original response without another ledger write. |
| Same owner/key but fingerprint differs | Reject with 409 IDEMPOTENCY_KEY_REUSED. |
| Existing PROCESSING record | Return 409 IDEMPOTENCY_REQUEST_IN_PROGRESS. This should occur only after an abnormal/extended transaction because normal duplicate inserts wait for the first transaction to commit. |

The claim, journal, transfer, and transition to COMPLETED are one transaction. If the first attempt fails, its idempotency claim rolls back too; a later attempt is revalidated and may reuse that key. This is an intentional Phase 2 trade-off: successful financial results are replayed exactly, while failure-response caching and stale PROCESSING recovery are deferred until a concrete product need exists. It prevents duplicate money movement without introducing a separate failure workflow.

There is no expiry or cleanup in Phase 2. An explicit retention policy is safer than silently allowing an old key to gain a new meaning.

## 10. Concurrency and Transaction Boundaries

TransferApplicationService uses one REQUIRED Spring transaction. It claims/resolves idempotency before requesting the Phase 1 journal write. A replay returns before account lookup, so an already completed transfer remains replayable even if an account later closes.

For a new command, TransferApplicationService reads immutable account metadata to confirm both accounts are WALLET accounts, source and destination differ, source owner matches X-LedgerX-Owner-Id, and currencies match the request. LedgerPostingService then re-reads and locks both accounts in ascending UUID order and validates mutable active/owner/funds state immediately before posting.

This split is intentional: ownership, kind, and currency are immutable; lifecycle and balance must be checked under the existing lock. Opposite-direction transfers lock the same two accounts in the same order. Competing outgoing transfers serialize at the source lock, and only postings with sufficient balance commit.

PostgreSQL READ COMMITTED plus these explicit locks remains the isolation model. A rare PostgreSQL deadlock or serialization-style conflict maps to a retryable 409 CONCURRENT_TRANSFER_CONFLICT; the client retries with the same idempotency key. Phase 2 does not introduce optimistic locking, distributed locks, or automatic blind retries.

## 11. API Design

All endpoints are under /api/v1. IDs are UUID strings; timestamps are UTC ISO-8601 Instants; money is an object with amount as a decimal string and currency as USD. Entities never cross the HTTP boundary.

| Endpoint | Purpose | Request | Success |
|---|---|---|---|
| POST /api/v1/transfers | Create or replay a transfer. Requires X-LedgerX-Owner-Id and Idempotency-Key. | sourceWalletId, destinationWalletId, money { amount, currency } | 201 Created initially; 200 OK for a replay. Body is TransferResponse. |
| GET /api/v1/transfers/{transferId} | Read a completed transfer that involves the supplied temporary owner. Requires X-LedgerX-Owner-Id. | Path ID and owner header | 200 OK with TransferResponse. |

TransferRequest uses dedicated DTOs and Bean Validation. Money parsing delegates to the existing Money value object, so scale, USD, and positivity are not reimplemented in the controller. TransferResponse contains transferId, sourceWalletId, destinationWalletId, money, status COMPLETED, ledgerTransactionId, and completedAt. A first creation sets Location to the GET resource.

No wallet-balance endpoint is included: without real authentication it would widen an intentionally temporary trust boundary, while transfer responses and tests already demonstrate balance correctness.

## 12. Error Model

The API returns one stable problem shape:

    { timestamp, status, code, message, path, details }

Details is a list of field/message pairs for request validation; it is empty for domain errors. The controller advice maps known application exceptions and does not leak SQL, stack traces, owner data, idempotency fingerprints, or raw keys.

| Situation | HTTP | Code |
|---|---:|---|
| Malformed JSON, UUID, unknown currency, or missing/invalid required header | 400 | MALFORMED_REQUEST |
| Invalid amount, same wallet, currency mismatch, inactive wallet, inactive owner, or insufficient funds | 422 | TRANSFER_NOT_PROCESSABLE or a specific documented subcode |
| Transfer or wallet not found | 404 | TRANSFER_NOT_FOUND or WALLET_NOT_FOUND |
| Caller does not own the source wallet | 403 | TRANSFER_NOT_AUTHORIZED |
| Same scoped key with a different fingerprint | 409 | IDEMPOTENCY_KEY_REUSED |
| Existing PROCESSING key or database concurrency conflict | 409 | IDEMPOTENCY_REQUEST_IN_PROGRESS or CONCURRENT_TRANSFER_CONFLICT |
| Unexpected persistence or server failure | 500 | INTERNAL_ERROR |

GET returns 404 rather than disclosing a transfer outside the supplied owner's relationship. This becomes meaningful security behavior once the temporary header is replaced with an authenticated principal.

## 13. Ownership / Security Boundary

Financial ownership is already stored on each wallet account as owner_id. Phase 2 requires the client to supply X-LedgerX-Owner-Id:

- POST permits a transfer only if that owner owns the source wallet.
- GET permits a result only if that owner owns either participating wallet.
- Destination ownership is not an authorization requirement for receipt.
- Unrelated owners receive 404 rather than confirmation that a transfer exists.

This header is a development seam, not authentication or authorization. Anyone can forge it, so this API is not safe to deploy publicly. A later security adapter will obtain the same owner UUID from SecurityContext and remove trust in the client header; TransferApplicationService should accept an OwnerContext/owner UUID rather than depend on HTTP directly.

No owner ID is accepted in the JSON transfer body. This avoids normalizing an unsafe client-controlled ownership field into the business command.

## 14. Database Design

Phase 2 adds versioned Flyway migrations after V3.

| Table | Purpose and columns | Integrity and indexes |
|---|---|---|
| transfers | id UUID PK; source_wallet_account_id; destination_wallet_account_id; amount NUMERIC(19,2); currency; ledger_transaction_id; completed_at. | source != destination, amount > 0, currency USD; composite FKs from each wallet/currency to ledger_accounts and from ledger transaction/currency to ledger_transactions; unique ledger_transaction_id; source/destination history indexes ordered by completed_at; immutable UPDATE/DELETE trigger. |
| transfer_idempotency | id UUID PK; owner_id FK; idempotency_key; request_fingerprint CHAR(64); state PROCESSING/COMPLETED; transfer_id nullable until completion; created_at; completed_at nullable. | unique (owner_id, idempotency_key), unique transfer_id, non-blank key, hexadecimal fingerprint, valid state/transfer timestamp combination; trigger makes scope/key/fingerprint immutable and permits only PROCESSING -> COMPLETED transition. |

No separate client-reference column is needed: transfer.id is the stable public reference, while Idempotency-Key is transport deduplication metadata rather than a business identifier.

The database cannot declaratively prove that transfer source/destination/amount match the two ledger entries. TransferApplicationService is the sole writer, uses the returned journal ID, and integration tests verify the correspondence. The existing ledger balance trigger still protects double-entry totals independently.

## 15. Java/Spring Component Design

| Area | Components | Owns / must not own |
|---|---|---|
| com.ledgerx.transfer.domain | Transfer, TransferStatus response enum if useful, TransferCommand, IdempotencyFingerprint, transfer exceptions | Valid completed business record and deterministic fingerprint; does not create journal entries. |
| com.ledgerx.transfer.persistence | TransferRepository, TransferIdempotencyRepository with native claim-or-resolve query | Transfer/idempotency storage and conflict-safe PostgreSQL interaction; does not contain HTTP mapping. |
| com.ledgerx.transfer.application | TransferApplicationService, TransferQueryService, IdempotencyService/result | One transaction, ownership/business orchestration, calling LedgerPostingService, replay decision; does not calculate account balance or bypass ledger locks. |
| com.ledgerx.transfer.api | TransferController, TransferRequest, MoneyRequest, TransferResponse, ApiError, GlobalExceptionHandler | HTTP parsing/validation and response mapping; does not expose entities or hold business logic. |
| existing ledger/wallet modules | LedgerPostingService, LedgerBalanceQueryService, account/owner repositories | Retain accounting and account lifecycle responsibilities. Phase 2 adds only focused repository methods required for immutable account metadata lookup. |

Add the Spring validation starter during implementation. Keep constructors injected, JPA entities internal, controllers thin, and repositories concrete Spring Data interfaces/classes. Do not add generic service interfaces, a mapper framework, or a new shared module.

## 16. Phase 2 Invariants

| Rule | Enforcement |
|---|---|
| Source and destination are distinct existing WALLET accounts. | DTO/application validation, account metadata lookup, database source != destination check, tests. |
| Source owner equals the temporary caller owner. | TransferApplicationService; 403 API mapping; tests. |
| Both wallets and the request are USD, active, and owned by active owners. | Money/DTO parsing, immutable metadata check, Phase 1 ledger validation under locks, composite FKs, tests. |
| Amount is strictly positive with scale 2. | Money value object, transfer column check, tests. |
| Source has sufficient derived funds. | Phase 1 LedgerPostingService while ordered account locks are held; concurrency tests. |
| One completed transfer maps to exactly one balanced immutable ledger transaction. | One transaction, unique transfer ledger FK, existing ledger trigger, transfer immutability trigger, tests. |
| Same owner/key/same fingerprint has one financial effect and replays the same transfer. | PostgreSQL uniqueness plus ON CONFLICT claim logic, application replay, concurrent integration/API tests. |
| Same owner/key/different fingerprint never creates another transfer. | Fingerprint comparison and 409 conflict, tests. |
| Failure leaves no transfer, journal, entries, or completed idempotency record. | Spring transaction rollback and PostgreSQL integration tests. |
| Completed transfer facts and idempotency scope/fingerprint cannot be mutated. | Encapsulated entities, restrictive FKs, database triggers, tests. |
| Phase 1 ledger writes are never bypassed. | Package responsibilities, code review, and integration tests that assert journal entries/balances. |

## 17. Testing Strategy

| Level | Required evidence |
|---|---|
| Unit | Transfer command/model validation, canonical fingerprint stability, same-wallet rejection, and response mapping. |
| Application | Source-owner authorization, wallet kind/currency checks, successful orchestration, replay versus fingerprint conflict, and no direct ledger-entry writes. |
| PostgreSQL Testcontainers | V4/V5 schema checks, unique/FK/check/immutability triggers, transfer-to-journal linkage, rollback if transfer or ledger persistence fails, repeated commands, concurrent duplicate keys, competing debits, and opposite-direction transfers. |
| MockMvc | 201 creation, 200 replay, GET ownership behavior, Location header, JSON money/timestamp contract, 400/403/404/409/422 problem shapes, and no entity serialization. |

Critical scenarios:

- A successful transfer decreases source and increases destination by the same USD amount; its journal is balanced and linked.
- A duplicate identical request, including two concurrent requests, returns one transfer ID and creates one journal.
- Same key/different body returns 409 and does not move money.
- Separate simultaneous debits that exceed a source balance allow only valid postings.
- Opposite-direction transfers do not deadlock because Phase 1 lock ordering is reused.
- Insufficient funds, inactive/closed wallet, bad owner, same wallet, malformed money, and missing records produce the documented error and no partial state.
- A forced transfer persistence failure rolls back the Phase 1 journal; a forced ledger failure leaves no transfer/idempotency completion.

## 18. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| A transfer service bypasses ledger logic or mutates a balance. | Delegate all posting to LedgerPostingService; no balance column or entry repository use from transfer code. |
| Check-then-act races overspend a source wallet. | Reuse ordered pessimistic locks and under-lock balance validation. |
| Concurrent duplicate requests double-post. | Claim scoped PostgreSQL key before ledger posting with unique constraint and ON CONFLICT behavior. |
| Replaying a current request sees a changed wallet lifecycle. | Resolve completed idempotency before account lookup and replay stored transfer result. |
| The temporary owner header looks like real security. | Name/document it as development-only and keep it at the API adapter boundary. |
| Transfer and ledger facts diverge. | One transaction, unique ledger link, immutable records, and rollback tests. |
| Deadlock handling masks an unknown failure. | Stable lock order first; map only recognized retryable database conflicts, log safely, and do not auto-retry blindly. |
| Idempotency table grows forever. | Retain keys in Phase 2; introduce an explicit retention/replay policy later rather than silent expiry. |
| Stale project documentation misstates the implementation. | Record discrepancies here; update README/roadmap/architecture in a scoped documentation change during implementation. |

## 19. Implementation Sequence

| Milestone | Goal and implementation | Dependencies / acceptance result |
|---|---|---|
| 2.1 | Add transfer/idempotency domain types, repository contracts, validation dependency, and V4/V5 schemas with integrity triggers. | Phase 1 migrations. Unit tests and Testcontainers prove schema constraints/immutability. |
| 2.2 | Implement transfer/idempotency application services that call LedgerPostingService in one transaction. | 2.1. Integration tests prove transfer/journal atomicity, funds behavior, and rollback. |
| 2.3 | Add native PostgreSQL claim/replay logic and concurrent idempotency tests. | 2.2. Identical sequential/concurrent commands create one transfer and one ledger effect; conflict is rejected. |
| 2.4 | Add REST DTOs, controller, validation, error advice, and GET authorization seam. | 2.2–2.3. MockMvc proves public contract/status/error behavior. |
| 2.5 | Add opposite-direction/concurrent transfer tests, update stale project claims, review migration/locking trade-offs, and run clean verify. | 2.1–2.4. Full suite passes; documentation and Git diff are accurate. |

## 20. Definition of Done

Phase 2 is complete when:

- POST and GET transfer endpoints follow the documented DTO, response, and problem contracts.
- The temporary owner boundary structurally protects source ownership and is clearly marked non-production.
- A valid transfer creates one immutable completed transfer and one linked balanced immutable journal in a single transaction.
- USD, active-wallet, distinct-wallet, ownership, and sufficient-funds rules hold under concurrent execution.
- Same scoped idempotency key and identical canonical request replay one completed transfer; a different request conflicts without a financial effect.
- Failed operations leave no partial transfer, ledger, or completed idempotency result.
- Database constraints/triggers, repositories, services, and controllers are covered by focused unit, MockMvc, and PostgreSQL Testcontainers tests.
- Maven clean verify passes; migrations are forward-only; documentation accurately describes Phase 1 and Phase 2 boundaries.
- No deferred security, messaging, FX, payment, reversal, or unrelated infrastructure has entered the change set.

## 21. What Phase 2 Enables

LedgerX will have a complete, auditable internal transfer slice. Phase 3 can add payments and an outbox around the same transfer/ledger boundaries; a later security phase can replace the temporary owner header without redesigning financial ownership or transfer orchestration.
