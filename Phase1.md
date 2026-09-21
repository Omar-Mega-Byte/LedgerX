# Phase 1 — Ledger and Wallet Core

**Status: implemented and verified on 2026-09-21.**

## 1. Goal

Build the financial core that later money-movement features can trust: precise money, owned wallets, immutable journal history, balanced double-entry postings, and balances derived from that history.

This is a portfolio system, not a bank. The design deliberately demonstrates production-grade integrity without adding services, caches, messaging, or authentication before they have a concrete use.

## 2. Current Starting Point

Phase 0 provides a Java 21 Spring Boot modular-monolith baseline with PostgreSQL, Flyway, JPA validation, local/test/prod profiles, Docker Compose, and PostgreSQL Testcontainers integration tests. Phase 1 adds USD money types, wallet owners, ledger accounts, immutable journal entities, V2/V3 Flyway migrations, transactional posting services, and PostgreSQL-backed invariant tests. V1 remains infrastructure metadata.

The project uses package-by-feature, thin future HTTP boundaries, constructor injection, UTC-oriented time types, Flyway migrations, and Maven verify (including Failsafe integration tests). JPA entities must not become API contracts.

### Planning discrepancy resolved

The Phase 0 foundation describes an eventual first vertical slice that includes transfers, idempotency, and authorization, while the roadmap places the transfer flow in Phase 2. The roadmap and this explicit Phase 1 brief take precedence for phase scope:

- Phase 1 establishes the reusable ledger and wallet core, including the internal posting capability needed to prove its invariants.
- Phase 2 adds the externally triggered, idempotent wallet-to-wallet transfer flow and its authorization boundary.

That keeps Phase 1 focused while ensuring Phase 2 does not need to redesign financial history.

## 3. Scope

### Included

- USD money and currency value objects with no floating-point arithmetic.
- Stable financial owner records, without credentials or authentication.
- Ledger accounts that represent wallets and future system accounts.
- Immutable journal transactions and balanced debit/credit entries.
- Derived ledger and available balances; no stored balance cache.
- Internal wallet creation, account state, journal posting, and balance-query application capabilities. These are not HTTP APIs.
- PostgreSQL/Flyway integrity rules, transaction handling, and tests including concurrent posting.

### Not Included

- REST controllers, transfer/payment/refund commands, or public wallet APIs.
- Authentication, authorization, users, merchants, credentials, or identity-provider integration.
- Idempotency keys, request fingerprints, outbox events, Kafka, Redis, notifications, or webhooks.
- Exchange rates, FX conversion, multi-currency transactions, overdrafts, credit limits, reservations, or pending/available-balance differences.
- A persisted balance projection/cache, reconciliation job, and correction/reversal command.

## 4. Key Engineering Decisions

| Decision | Choice | Why |
|---|---|---|
| Deployment shape | Modular monolith | One PostgreSQL transaction is the correct consistency boundary for the core. |
| Supported currency | USD only, represented explicitly everywhere | It avoids premature FX and varying minor-unit rules while preventing a hidden “currency-less amount” design. |
| Amount storage | BigDecimal and PostgreSQL NUMERIC(19,2) | Exact decimal arithmetic is understandable and safe for the initial two-decimal currency. |
| Wallet/account relationship | A wallet is an owned LIABILITY ledger account of kind WALLET; no duplicate wallets table | There are no wallet-only fields yet, so a second table would duplicate identity, owner, currency, status, and balance semantics. |
| Ledger representation | One immutable journal header with two or more positive debit/credit lines | It supports ordinary two-party movements and later fee splits without special cases. |
| Balance authority | Sum immutable entries; no balance column | The ledger remains the sole financial source of truth and cannot drift from a projection. |
| Posting concurrency | Pessimistically lock affected accounts in stable ID order | Derived balances require a read-before-posting check; database row locks serialize competing debits without global SERIALIZABLE retries. |
| Domain/persistence modeling | JPA entities are internal domain persistence models; Money is a pure value object | This gives a small project meaningful behavior and database mapping without a second, duplicate object graph. |

## 5. Money Model

Money is a value: an amount plus a currency. It is not a floating-point number and has no sign used to express movement direction.

- Introduce Money and CurrencyCode in com.ledgerx.money. CurrencyCode initially permits only USD.
- Money canonicalizes its BigDecimal amount to scale 2 using RoundingMode.UNNECESSARY. An input such as 10.00 or 10 is representable; 10.001 is rejected rather than silently rounded.
- Money permits zero for values such as a derived balance, but a ledger posting amount must be strictly positive. Debit or credit expresses direction; negative posting amounts are invalid.
- Persist a posting amount as NUMERIC(19,2), giving up to 17 whole-number digits. Every monetary column has an amount > 0 check where zero is not meaningful.
- Equality compares both canonical amount and currency. Canonical scale avoids BigDecimal.equals treating 10.0 and 10.00 as unequal.

An integer-minor-unit model is also valid, but BigDecimal maps naturally to PostgreSQL NUMERIC and clearly teaches Java monetary correctness here. A general ISO-currency model is deferred because fraction digits and rounding policy would need explicit per-currency rules. Adding supported static currencies later does not change the ledger shape; FX remains a separate business feature.

## 6. Wallet and Ownership Model

A wallet is the customer-facing meaning of one LedgerAccount with:

- account kind WALLET;
- accounting type LIABILITY;
- a non-null WalletOwner;
- one immutable USD currency; and
- an ACTIVE, SUSPENDED, or CLOSED account state.

The identity of the wallet is the ledger-account UUID. Its balance is the normal balance of that liability account, not a mutable wallet.balance field. A credit increases what the platform owes the owner; a debit decreases it. This terminology can initially feel backwards for “money in/out,” which is exactly why debit/credit must be defined by account type rather than by everyday language.

WalletOwner is a stable financial identity with an ID, owner type (PERSON or MERCHANT), lifecycle state, and timestamps. It deliberately contains no email, password, role, token, or authentication implementation. A later identity module will associate an authenticated principal with one WalletOwner; it must not rewrite historical owner IDs.

An owner may have exactly one wallet per supported currency in Phase 1. This makes wallet selection unambiguous and is enforced by a unique constraint. A future product need for purpose-specific wallets can add a wallet-purpose concept and revise that rule deliberately.

Owner, currency, account kind, and accounting type are immutable after creation. The account state can change. SUSPENDED and CLOSED accounts cannot receive a new posting; a wallet can close only at a zero derived balance. This is the wallet lifecycle; a separate wallet-status field would create needless state drift.

System accounts use the same LedgerAccount table with kind SYSTEM, no owner, and a unique system code. This gives later fees, clearing, and funding a compatible accounting home without creating those features now.

## 7. Ledger Model

The ledger has three concepts:

| Concept | Responsibility |
|---|---|
| LedgerAccount | A named accounting bucket with currency, kind, type, owner/system identity, and lifecycle state. |
| LedgerTransaction | One posted financial fact, identified by UUID and containing a human-readable immutable description and UTC posting instant. |
| LedgerEntry | One line of a transaction: account, positive amount, currency, debit/credit side, and line number. |

Account types are ASSET, LIABILITY, REVENUE, EXPENSE, and EQUITY. Their normal side is derived in code, not stored: ASSET and EXPENSE are debit-normal; LIABILITY, REVENUE, and EQUITY are credit-normal. This preserves conventional accounting semantics and lets the same table represent future internal accounts.

Phase 1 stores one currency per LedgerTransaction. Each entry repeats that currency only so composite foreign keys can prove it matches both its transaction and account. A future FX business operation would use balanced postings per currency and explicit rate/clearing accounts; it must not treat an exchange rate as an arithmetic shortcut inside one USD transaction.

## 8. Double-Entry Accounting Model

A journal transaction is the only way money moves in the ledger. It has at least two entries, each with a positive amount and a side:

- debit records an increase to a debit-normal account or a decrease to a credit-normal account;
- credit records an increase to a credit-normal account or a decrease to a debit-normal account.

For every posted transaction:

    sum(debit amounts) = sum(credit amounts)

For a wallet-to-wallet transfer in the later phase, the source wallet LIABILITY account is debited and the destination wallet LIABILITY account is credited. The transaction balances even though the owner-facing source balance decreases and destination balance increases.

The model permits more than two lines. For example, a later payment could debit one wallet and credit a merchant wallet plus a platform revenue account. Phase 1 permits one line per account per journal transaction, avoiding meaningless debit/credit self-cancellation and accidental duplicate lines.

The application validates a complete candidate posting before persistence. PostgreSQL then uses a deferred constraint trigger at transaction commit to verify at least two entries and equal debit/credit totals. A normal CHECK constraint cannot enforce a sum across rows, so relying on one would be incorrect.

## 9. Balance Model

The ledger is authoritative. An account balance is calculated from posted entries:

    debit-normal balance  = total debits - total credits
    credit-normal balance = total credits - total debits

For a wallet (a credit-normal LIABILITY account), owner-facing ledger balance is credits minus debits. In Phase 1, available balance equals ledger balance because there are no pending transactions, holds, or credit facilities.

No balance is stored on LedgerAccount or Wallet. A later projection may be introduced only as derived data, updated atomically with postings, treated as non-authoritative, and reconciled against this equation.

Wallet balances may not become negative. System accounts have no Phase 1 product-level overdraft rule; they exist for accounting extensibility, not as customer wallets. The posting service checks prospective wallet balances while holding the affected account locks.

## 10. Domain Invariants

| Rule | Why | Enforcement |
|---|---|---|
| Every posting has at least two distinct account entries and total debits equal total credits. | Value cannot appear or disappear. | Domain factory/service, deferred PostgreSQL constraint trigger, integration tests. |
| Each entry amount is positive, precise to two decimals, and USD. | A side, not a negative sign, represents direction; precision is consistent. | Money value object, column checks, integration/unit tests. |
| Entry, account, and transaction currency match. | A balance cannot hide a currency mismatch. | Composite foreign keys, application validation, tests. |
| A wallet is one active/suspended/closed USD liability account owned by one active owner. | Ownership and accounting responsibility remain explicit. | Foreign keys, account-kind/type checks, unique owner/currency index, application rules, tests. |
| One owner has at most one wallet per currency. | Wallet selection is deterministic in the first product slice. | Partial unique index and tests. |
| Owner, currency, kind, account type, journal header, and journal entries are immutable. | Historical financial facts must remain auditable. | Encapsulated domain API, database immutability triggers, restrictive foreign keys, integration tests. |
| Suspended or closed accounts and inactive owners cannot participate in a new posting. | Lifecycle state must have financial effect. | Posting service inside the transaction, tests. |
| A wallet never has a negative derived balance. | Phase 1 has no credit facility. | Ordered pessimistic locks, prospective-balance validation, concurrent integration test. |
| A journal line number and participating account are unique within its journal. | Prevents accidental duplicate or cancelling lines. | Unique constraints and tests. |
| A failed posting leaves no journal header or entry committed. | Financial state is atomic. | Spring transaction boundary, database transaction, rollback integration test. |

External command duplication is intentionally not an invariant yet. Phase 2 will define idempotency scope and request fingerprints; a journal UUID alone cannot make retried HTTP commands safe.

## 11. Immutability Rules

Once a LedgerTransaction is inserted, its header, entries, amount, currency, sides, account IDs, description, and timestamp cannot change or be deleted. Entry correction will eventually mean a new, balanced compensating journal transaction that refers to the original fact; it never means editing history.

Database triggers will reject UPDATE and DELETE for journal headers and entries. Foreign keys use RESTRICT rather than cascaded deletion. JPA exposes no public setters for posted fields and avoids CascadeType.ALL.

Ledger account master data is different from history: status and closed timestamp can change through explicit methods, while owner, currency, account kind/type, and system code cannot. A closed wallet stays queryable and retains all historic entries.

## 12. Transaction and Concurrency Boundaries

Two application operations need deliberate transactions:

1. Create a WalletOwner and/or wallet account: validate ownership state and write the account atomically.
2. Post a journal transaction: load all accounts, lock them by ascending UUID with PESSIMISTIC_WRITE, validate status/currency/balance and balanced lines, insert the header and all entries, then commit.

The second operation is one Spring @Transactional application-service method. Any failed validation, constraint failure, or persistence exception rolls back the whole unit. A deterministic lock order prevents the usual two-account deadlock pattern. After locking, the service calculates each affected wallet's current ledger balance and rejects a debit that would make it negative.

The default PostgreSQL READ COMMITTED isolation with explicit account-row locks is selected over global SERIALIZABLE isolation. It is easier to reason about for a small system and avoids implementing general serialization-failure retries. Every future writer of financial entries must use the same posting service and lock rule. The database's deferred balance trigger protects aggregate double-entry correctness; application locks protect the concurrent non-negative-wallet rule.

## 13. Database Design

Phase 1 adds V2 for wallet ownership/accounts and V3 for immutable journal history and database triggers.

| Table | Purpose and important columns | Integrity and indexes |
|---|---|---|
| wallet_owners | id UUID PK, owner_type, status, created_at, updated_at. It is a financial identity, not an authentication user. | Checks for supported owner/status values; owner data is never cascaded away. |
| ledger_accounts | id UUID PK, account_kind, account_type, owner_id nullable FK, system_code nullable, currency, status, created_at, closed_at. A WALLET row is the wallet. | WALLET requires LIABILITY + owner and no system code; SYSTEM requires system code and no owner. Unique system_code. Partial unique (owner_id, currency) for WALLET. Checks currency USD and valid lifecycle values. Index owner_id for owner queries. |
| ledger_transactions | id UUID PK, currency, description, posted_at. This row is created only when the complete journal is posted. | Currency check; composite unique (id, currency) supports entry currency FK; posted fields are immutable. Index posted_at for history ordering. |
| ledger_entries | id UUID PK, ledger_transaction_id, line_number, ledger_account_id, side, amount, currency. | amount > 0, line_number > 0, unique (transaction, line_number), unique (transaction, account). Composite FKs (transaction, currency) and (account, currency). Index (ledger_account_id, ledger_transaction_id) supports balance/history queries. |

The account-type normal side is derived in Java rather than a mutable database column. UUIDs are generated by the application, and all timestamps use TIMESTAMP WITH TIME ZONE / Instant in UTC.

PostgreSQL triggers are intentionally narrow and financial:

- a DEFERRABLE INITIALLY DEFERRED constraint trigger checks each newly posted journal at commit for two-or-more lines and equal debit/credit totals;
- immutability triggers reject journal UPDATE/DELETE and forbidden account identity changes.

Column checks, foreign keys, unique constraints, and triggers complement rather than replace domain validation. The migration will make trigger failures clear enough to translate into a domain persistence exception without exposing raw database details later.

## 14. Java/Spring Component Design

Use package-by-feature. The implemented structure is:

| Area | Probable components | Responsibility |
|---|---|---|
| com.ledgerx.money | Money, CurrencyCode | Immutable monetary representation and validation. |
| com.ledgerx.wallet.domain | WalletOwner, OwnerType, OwnerStatus | Stable financial ownership only. |
| com.ledgerx.wallet.application | WalletAccountService | Creates an owner/wallet account and changes permitted lifecycle state. |
| com.ledgerx.wallet.persistence | WalletOwnerRepository | Persistence access for owners. |
| com.ledgerx.ledger.domain | LedgerAccount, AccountKind, AccountType, AccountStatus, LedgerTransaction, LedgerEntry, EntrySide, LedgerPostingFactory, financial exceptions | Models accounts and creates only valid, complete journal candidates. |
| com.ledgerx.ledger.application | LedgerPostingService, LedgerBalanceQueryService | Owns transaction boundaries, ordered account locking, persistence coordination, and derived balance reads. |
| com.ledgerx.ledger.persistence | LedgerAccountRepository, LedgerTransactionRepository, LedgerBalanceRepository | Focused JPA and aggregate-query access, including a lock query and balance aggregation. |

JPA entities are internal models in this phase. They may carry persistence annotations and narrowly scoped behavior, but are never returned from controllers. Constructors/factories enforce required state; JPA-only constructors remain non-public where practical; associations are unidirectional and lazy. Money is an immutable value object embedded or converted at persistence boundaries. There is no generic mapper layer because there are no external DTOs yet.

Full domain/persistence duplication would add mapping ceremony before the model has enough complexity to justify it. If later API, event, or integration models begin forcing persistence compromises, introduce explicit boundary DTOs first and consider a separated domain model with evidence rather than by default.

## 15. Testing Strategy

| Test level | Proves |
|---|---|
| Fast unit tests | Money scale/currency/equality rules; normal-side arithmetic; posting factory rejects empty, unbalanced, mixed-currency, duplicate-account, and non-positive lines; lifecycle transitions. |
| Application/domain tests | Posting service accepts a balanced candidate, rejects inactive accounts and insufficient wallet funds, and derives the expected wallet balance. |
| PostgreSQL Testcontainers integration tests | Flyway schema, checks/FKs/unique rules, deferred balance trigger, immutable journal trigger, derived SQL balance query, and full rollback on a failed posting. |
| Concurrent PostgreSQL integration test | Two synchronized postings attempt to debit the same funded wallet. Exactly one succeeds when their combined amount exceeds its balance; history remains balanced and balance stays non-negative. |

Integration tests must flush/commit where needed so deferred constraints are actually evaluated. Tests assert financial outcomes and failure modes, not implementation details or coverage totals. Existing health and V1 migration verification remain intact.

### Completed and tested Phase 1 features

Phase 1 is complete. Its tested capabilities are:

- **Exact USD money:** canonical two-decimal `BigDecimal` values; rounding-required and negative amounts are rejected, while zero remains valid for derived balances.
- **Balanced journal construction:** valid debit/credit candidates are immutable; unbalanced, duplicate-account, and zero-amount postings are rejected before persistence.
- **Owned USD wallets:** a PERSON or MERCHANT owner receives at most one USD wallet; wallet and system-account shapes follow the ledger-account constraints.
- **Derived balances and internal funding:** balances are calculated from immutable entry history rather than a mutable balance field.
- **Financial lifecycle controls:** suspended and closed wallets reject new postings; closing requires a zero derived balance.
- **Database-enforced integrity:** PostgreSQL rejects unbalanced journal headers at commit, prevents history and account-identity mutation, and preserves currency/foreign-key constraints.
- **Atomic failure behavior:** a rejected posting leaves no partial journal fact committed.
- **Concurrent debit safety:** competing debits from one funded wallet are serialized with ordered pessimistic locks; only affordable work commits and the resulting balance remains non-negative.

The Phase 1 completion verification ran 18 tests with zero failures or errors: 7 fast unit tests plus 11 PostgreSQL/Testcontainers and application integration tests. Later phases add their own coverage without weakening these core invariants.

## 16. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Floating-point or silent rounding changes money. | Canonical BigDecimal scale, USD-only policy, reject rounding, NUMERIC(19,2), focused tests. |
| A mutable balance drifts from history. | Do not store one; query the immutable ledger. |
| One-sided or mixed-currency entries persist. | Domain validation, composite FKs, deferred database balance trigger, integration tests. |
| Debit/credit labels are implemented as universal plus/minus signs. | Derive normal side from account type and test wallet LIABILITY examples. |
| A retried caller creates two postings. | Do not claim to solve it in Phase 1; implement scoped idempotency in Phase 2. |
| Competing debits overspend a wallet. | Ordered PESSIMISTIC_WRITE account locks and a repeatable concurrent PostgreSQL test. |
| JPA accidentally mutates historical rows. | No setters, explicit repositories, immutable database triggers, and no cascade removal. |
| Ownership becomes coupled to an auth provider. | Persist WalletOwner as a provider-independent financial identity. |
| Database constraints are bypassed by application-only checks. | Use checks, FKs, unique indexes, and the one necessary aggregate constraint trigger. |

## 17. Implementation Sequence

| Milestone | Delivered implementation | Verification evidence |
|---|---|---|
| 1.1 | Added Money/CurrencyCode and financial validation. | Unit tests prove precision, equality, and invalid amounts. |
| 1.2 | Added owner/account model and V2 ownership/account schema. | PostgreSQL tests prove ownership, wallet shape, lifecycle, and uniqueness constraints. |
| 1.3 | Added immutable journal model/factory and V3 journal schema, balance trigger, and immutability triggers. | Unit and integration tests prove balanced, immutable, currency-consistent history. |
| 1.4 | Added transactional posting and derived-balance services with ordered locks. | Integration tests prove rollback, no negative wallet balance, and correct balance derivation. |
| 1.5 | Added repeatable concurrent-debit and database-integrity tests. | Maven verify passes with unit and PostgreSQL Testcontainers coverage. |

Each milestone is represented by focused commits. No transfer controller or idempotency work entered the implementation.

## 18. Definition of Done

Phase 1 is complete only when all of the following are true:

- Money is USD-aware, exact, scale-validated, and never represented by float/double.
- Wallet ownership is represented without authentication coupling; one owner has at most one USD wallet.
- A wallet is an owned LIABILITY ledger account with a defined lifecycle and no mutable balance field.
- Posted journals contain two or more positive entries, match account/transaction currency, and balance debit-to-credit at database commit.
- Posted journals and entries cannot be updated or deleted; account identity fields cannot be changed.
- Wallet balances are derived correctly from ledger history and cannot become negative under competing postings.
- Financial writes are atomic; failure leaves no partial journal data.
- Flyway migrations, PostgreSQL Testcontainers tests, unit tests, and the concurrent test pass through Maven verify.
- Schema and design documentation explain the balance authority, locking choice, and deferred constraint-trigger trade-off.
- No transfer, API, authentication, idempotency, messaging, or other out-of-scope functionality has been added.

## 19. What Phase 1 Enables Next

Phase 2 can add an authenticated, idempotent transfer command that debits one existing wallet account and credits another inside the proven posting boundary. Later payments, fees, and reversals can reuse the same immutable multi-line journal model.
