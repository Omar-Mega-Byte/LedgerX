CREATE TABLE payments (
    id UUID PRIMARY KEY,
    payer_wallet_account_id UUID NOT NULL,
    merchant_wallet_account_id UUID NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    ledger_transaction_id UUID NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payments_payer_wallet_currency_fkey
        FOREIGN KEY (payer_wallet_account_id, currency)
        REFERENCES ledger_accounts (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT payments_merchant_wallet_currency_fkey
        FOREIGN KEY (merchant_wallet_account_id, currency)
        REFERENCES ledger_accounts (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT payments_ledger_transaction_currency_fkey
        FOREIGN KEY (ledger_transaction_id, currency)
        REFERENCES ledger_transactions (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT payments_distinct_wallets_check
        CHECK (payer_wallet_account_id <> merchant_wallet_account_id),
    CONSTRAINT payments_amount_check CHECK (amount > 0),
    CONSTRAINT payments_currency_check CHECK (currency = 'USD'),
    CONSTRAINT payments_ledger_transaction_id_key UNIQUE (ledger_transaction_id)
);

CREATE INDEX payments_payer_completed_at_index
    ON payments (payer_wallet_account_id, completed_at DESC);

CREATE INDEX payments_merchant_completed_at_index
    ON payments (merchant_wallet_account_id, completed_at DESC);

CREATE TABLE payment_idempotency (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    payment_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT payment_idempotency_owner_fkey
        FOREIGN KEY (owner_id) REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    CONSTRAINT payment_idempotency_payment_fkey
        FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE RESTRICT,
    CONSTRAINT payment_idempotency_owner_key_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT payment_idempotency_payment_id_key UNIQUE (payment_id),
    CONSTRAINT payment_idempotency_key_check CHECK (BTRIM(idempotency_key) <> ''),
    CONSTRAINT payment_idempotency_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payment_idempotency_state_check CHECK (state IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT payment_idempotency_completion_check CHECK (
        (state = 'PROCESSING' AND payment_id IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND payment_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    merchant_wallet_account_id UUID NOT NULL,
    payer_wallet_account_id UUID NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    ledger_transaction_id UUID NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT refunds_payment_fkey
        FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE RESTRICT,
    CONSTRAINT refunds_merchant_wallet_currency_fkey
        FOREIGN KEY (merchant_wallet_account_id, currency)
        REFERENCES ledger_accounts (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT refunds_payer_wallet_currency_fkey
        FOREIGN KEY (payer_wallet_account_id, currency)
        REFERENCES ledger_accounts (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT refunds_ledger_transaction_currency_fkey
        FOREIGN KEY (ledger_transaction_id, currency)
        REFERENCES ledger_transactions (id, currency)
        ON DELETE RESTRICT,
    CONSTRAINT refunds_distinct_wallets_check
        CHECK (merchant_wallet_account_id <> payer_wallet_account_id),
    CONSTRAINT refunds_amount_check CHECK (amount > 0),
    CONSTRAINT refunds_currency_check CHECK (currency = 'USD'),
    CONSTRAINT refunds_ledger_transaction_id_key UNIQUE (ledger_transaction_id)
);

CREATE INDEX refunds_payment_completed_at_index ON refunds (payment_id, completed_at DESC);

CREATE TABLE refund_idempotency (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    refund_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT refund_idempotency_owner_fkey
        FOREIGN KEY (owner_id) REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    CONSTRAINT refund_idempotency_refund_fkey
        FOREIGN KEY (refund_id) REFERENCES refunds (id) ON DELETE RESTRICT,
    CONSTRAINT refund_idempotency_owner_key_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT refund_idempotency_refund_id_key UNIQUE (refund_id),
    CONSTRAINT refund_idempotency_key_check CHECK (BTRIM(idempotency_key) <> ''),
    CONSTRAINT refund_idempotency_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refund_idempotency_state_check CHECK (state IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT refund_idempotency_completion_check CHECK (
        (state = 'PROCESSING' AND refund_id IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND refund_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE FUNCTION prevent_payment_or_refund_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% records are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER payments_immutable
BEFORE UPDATE OR DELETE ON payments
FOR EACH ROW
EXECUTE FUNCTION prevent_payment_or_refund_mutation();

CREATE TRIGGER refunds_immutable
BEFORE UPDATE OR DELETE ON refunds
FOR EACH ROW
EXECUTE FUNCTION prevent_payment_or_refund_mutation();

CREATE FUNCTION enforce_payment_or_refund_idempotency_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    result_column TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% records are immutable', TG_TABLE_NAME
            USING ERRCODE = '55000';
    END IF;

    IF NEW.owner_id IS DISTINCT FROM OLD.owner_id
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION '% identity fields are immutable', TG_TABLE_NAME
            USING ERRCODE = '55000';
    END IF;

    result_column := CASE TG_TABLE_NAME
        WHEN 'payment_idempotency' THEN 'payment_id'
        ELSE 'refund_id'
    END;

    IF OLD.state <> 'PROCESSING'
        OR NEW.state <> 'COMPLETED'
        OR OLD.completed_at IS NOT NULL
        OR NEW.completed_at IS NULL
        OR (to_jsonb(OLD) ->> result_column) IS NOT NULL
        OR (to_jsonb(NEW) ->> result_column) IS NULL THEN
        RAISE EXCEPTION '% may only transition from PROCESSING to COMPLETED', TG_TABLE_NAME
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_idempotency_mutation_guard
BEFORE UPDATE OR DELETE ON payment_idempotency
FOR EACH ROW
EXECUTE FUNCTION enforce_payment_or_refund_idempotency_mutation();

CREATE TRIGGER refund_idempotency_mutation_guard
BEFORE UPDATE OR DELETE ON refund_idempotency
FOR EACH ROW
EXECUTE FUNCTION enforce_payment_or_refund_idempotency_mutation();
