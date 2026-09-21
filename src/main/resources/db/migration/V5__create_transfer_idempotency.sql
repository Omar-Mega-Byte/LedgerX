CREATE TABLE transfer_idempotency (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    transfer_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT transfer_idempotency_owner_fkey
        FOREIGN KEY (owner_id)
        REFERENCES wallet_owners (id)
        ON DELETE RESTRICT,
    CONSTRAINT transfer_idempotency_transfer_fkey
        FOREIGN KEY (transfer_id)
        REFERENCES transfers (id)
        ON DELETE RESTRICT,
    CONSTRAINT transfer_idempotency_owner_key_key
        UNIQUE (owner_id, idempotency_key),
    CONSTRAINT transfer_idempotency_transfer_id_key UNIQUE (transfer_id),
    CONSTRAINT transfer_idempotency_key_check CHECK (BTRIM(idempotency_key) <> ''),
    CONSTRAINT transfer_idempotency_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transfer_idempotency_state_check
        CHECK (state IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT transfer_idempotency_completion_check CHECK (
        (state = 'PROCESSING' AND transfer_id IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND transfer_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE FUNCTION enforce_transfer_idempotency_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'transfer idempotency records are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.owner_id IS DISTINCT FROM OLD.owner_id
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'transfer idempotency identity fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.state <> 'PROCESSING'
        OR NEW.state <> 'COMPLETED'
        OR OLD.transfer_id IS NOT NULL
        OR OLD.completed_at IS NOT NULL
        OR NEW.transfer_id IS NULL
        OR NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'transfer idempotency may only transition from PROCESSING to COMPLETED'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER transfer_idempotency_mutation_guard
BEFORE UPDATE OR DELETE ON transfer_idempotency
FOR EACH ROW
EXECUTE FUNCTION enforce_transfer_idempotency_mutation();
