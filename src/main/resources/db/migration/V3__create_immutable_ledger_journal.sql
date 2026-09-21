ALTER TABLE ledger_accounts
    ADD CONSTRAINT ledger_accounts_id_currency_key UNIQUE (id, currency);

ALTER TABLE ledger_accounts
    ADD CONSTRAINT ledger_accounts_account_type_check
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE', 'EQUITY'));

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(250) NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_transactions_currency_check CHECK (currency = 'USD'),
    CONSTRAINT ledger_transactions_description_check CHECK (BTRIM(description) <> ''),
    CONSTRAINT ledger_transactions_id_currency_key UNIQUE (id, currency)
);

CREATE INDEX ledger_transactions_posted_at_index ON ledger_transactions (posted_at);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    ledger_transaction_id UUID NOT NULL,
    line_number SMALLINT NOT NULL,
    ledger_account_id UUID NOT NULL,
    side VARCHAR(6) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    CONSTRAINT ledger_entries_transaction_currency_fkey
        FOREIGN KEY (ledger_transaction_id, currency)
        REFERENCES ledger_transactions (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT ledger_entries_account_currency_fkey
        FOREIGN KEY (ledger_account_id, currency)
        REFERENCES ledger_accounts (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT ledger_entries_amount_check CHECK (amount > 0),
    CONSTRAINT ledger_entries_line_number_check CHECK (line_number > 0),
    CONSTRAINT ledger_entries_side_check CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_transaction_line_number_key
        UNIQUE (ledger_transaction_id, line_number),
    CONSTRAINT ledger_entries_transaction_account_key
        UNIQUE (ledger_transaction_id, ledger_account_id)
);

CREATE INDEX ledger_entries_account_transaction_index
    ON ledger_entries (ledger_account_id, ledger_transaction_id);

CREATE FUNCTION validate_ledger_transaction_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transaction_id_to_validate UUID;
    entry_count INTEGER;
    debit_total NUMERIC(19, 2);
    credit_total NUMERIC(19, 2);
BEGIN
    IF TG_TABLE_NAME = 'ledger_transactions' THEN
        transaction_id_to_validate := NEW.id;
    ELSE
        transaction_id_to_validate := COALESCE(NEW.ledger_transaction_id, OLD.ledger_transaction_id);
    END IF;

    SELECT
        COUNT(*),
        COALESCE(SUM(amount) FILTER (WHERE side = 'DEBIT'), 0),
        COALESCE(SUM(amount) FILTER (WHERE side = 'CREDIT'), 0)
    INTO entry_count, debit_total, credit_total
    FROM ledger_entries
    WHERE ledger_transaction_id = transaction_id_to_validate;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'ledger transaction % must contain at least two entries', transaction_id_to_validate
            USING ERRCODE = '23514';
    END IF;

    IF debit_total <> credit_total THEN
        RAISE EXCEPTION 'ledger transaction % must balance debits and credits', transaction_id_to_validate
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_transactions_balance_check
AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_ledger_transaction_balance();

CREATE CONSTRAINT TRIGGER ledger_entries_balance_check
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_ledger_transaction_balance();

CREATE FUNCTION prevent_ledger_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% records are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER ledger_transactions_immutable
BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_history_mutation();

CREATE TRIGGER ledger_entries_immutable
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_history_mutation();

CREATE FUNCTION prevent_ledger_account_identity_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_kind IS DISTINCT FROM OLD.account_kind
        OR NEW.account_type IS DISTINCT FROM OLD.account_type
        OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
        OR NEW.system_code IS DISTINCT FROM OLD.system_code
        OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'ledger account identity fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_accounts_identity_immutable
BEFORE UPDATE ON ledger_accounts
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_account_identity_mutation();
