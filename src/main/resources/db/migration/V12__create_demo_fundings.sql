CREATE TABLE demo_fundings (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    wallet_id UUID NOT NULL REFERENCES ledger_accounts (id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0 AND amount <= 10000.00),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
    ledger_transaction_id UUID REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT demo_fundings_owner_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT demo_fundings_completion_check
        CHECK ((ledger_transaction_id IS NULL) = (completed_at IS NULL))
);

CREATE INDEX demo_fundings_owner_completed_idx
    ON demo_fundings (owner_id, completed_at DESC);
