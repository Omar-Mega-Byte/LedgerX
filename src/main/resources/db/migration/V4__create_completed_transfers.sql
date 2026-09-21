CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    source_wallet_account_id UUID NOT NULL,
    destination_wallet_account_id UUID NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    ledger_transaction_id UUID NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT transfers_source_wallet_currency_fkey
        FOREIGN KEY (source_wallet_account_id, currency)
        REFERENCES ledger_accounts (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT transfers_destination_wallet_currency_fkey
        FOREIGN KEY (destination_wallet_account_id, currency)
        REFERENCES ledger_accounts (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT transfers_ledger_transaction_currency_fkey
        FOREIGN KEY (ledger_transaction_id, currency)
        REFERENCES ledger_transactions (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT transfers_distinct_wallets_check
        CHECK (source_wallet_account_id <> destination_wallet_account_id),
    CONSTRAINT transfers_amount_check CHECK (amount > 0),
    CONSTRAINT transfers_currency_check CHECK (currency = 'USD'),
    CONSTRAINT transfers_ledger_transaction_id_key UNIQUE (ledger_transaction_id)
);

CREATE INDEX transfers_source_completed_at_index
    ON transfers (source_wallet_account_id, completed_at DESC);

CREATE INDEX transfers_destination_completed_at_index
    ON transfers (destination_wallet_account_id, completed_at DESC);

CREATE FUNCTION prevent_transfer_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'transfers are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER transfers_immutable
BEFORE UPDATE OR DELETE ON transfers
FOR EACH ROW
EXECUTE FUNCTION prevent_transfer_mutation();
