CREATE TABLE wallet_owners (
    id UUID PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT wallet_owners_owner_type_check
        CHECK (owner_type IN ('PERSON', 'MERCHANT')),
    CONSTRAINT wallet_owners_status_check
        CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    account_kind VARCHAR(20) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    owner_id UUID,
    system_code VARCHAR(100),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ledger_accounts_owner_id_fkey
        FOREIGN KEY (owner_id) REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    CONSTRAINT ledger_accounts_currency_check
        CHECK (currency = 'USD'),
    CONSTRAINT ledger_accounts_status_check
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ledger_accounts_wallet_or_system_check
        CHECK (
            (
                account_kind = 'WALLET'
                AND account_type = 'LIABILITY'
                AND owner_id IS NOT NULL
                AND system_code IS NULL
            )
            OR (
                account_kind = 'SYSTEM'
                AND owner_id IS NULL
                AND system_code IS NOT NULL
            )
        )
);

CREATE UNIQUE INDEX ledger_accounts_wallet_owner_currency_key
    ON ledger_accounts (owner_id, currency)
    WHERE account_kind = 'WALLET';

CREATE UNIQUE INDEX ledger_accounts_system_code_key
    ON ledger_accounts (system_code)
    WHERE account_kind = 'SYSTEM';

CREATE INDEX ledger_accounts_owner_id_index ON ledger_accounts (owner_id);
