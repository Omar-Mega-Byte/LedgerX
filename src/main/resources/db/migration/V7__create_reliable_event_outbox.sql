CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version SMALLINT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    CONSTRAINT outbox_events_aggregate_type_check CHECK (aggregate_type = 'PAYMENT'),
    CONSTRAINT outbox_events_sequence_check CHECK (aggregate_sequence > 0),
    CONSTRAINT outbox_events_event_type_check
        CHECK (event_type IN ('payment.completed.v1', 'refund.completed.v1')),
    CONSTRAINT outbox_events_schema_version_check CHECK (schema_version = 1),
    CONSTRAINT outbox_events_status_check CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')),
    CONSTRAINT outbox_events_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT outbox_events_aggregate_sequence_key
        UNIQUE (aggregate_type, aggregate_id, aggregate_sequence),
    CONSTRAINT outbox_events_delivery_shape_check CHECK (
        (status = 'PENDING' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL)
        OR (status = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NOT NULL)
    )
);

CREATE INDEX outbox_events_eligible_index
    ON outbox_events (status, next_attempt_at, occurred_at);

CREATE TABLE processed_events (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT processed_events_primary_key PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT processed_events_consumer_name_check CHECK (BTRIM(consumer_name) <> ''),
    CONSTRAINT processed_events_event_type_check
        CHECK (event_type IN ('payment.completed.v1', 'refund.completed.v1')),
    CONSTRAINT processed_events_payload_sha256_check
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION enforce_outbox_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox events are retained and cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.aggregate_sequence IS DISTINCT FROM OLD.aggregate_sequence
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
        RAISE EXCEPTION 'outbox business fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NOT (
        (OLD.status = 'PENDING' AND NEW.status = 'IN_FLIGHT'
            AND NEW.attempt_count = OLD.attempt_count + 1)
        OR (OLD.status = 'IN_FLIGHT' AND NEW.status = 'PUBLISHED'
            AND NEW.attempt_count = OLD.attempt_count)
        OR (OLD.status = 'IN_FLIGHT' AND NEW.status = 'PENDING'
            AND NEW.attempt_count = OLD.attempt_count)
    ) THEN
        RAISE EXCEPTION 'invalid outbox delivery state transition'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_events_mutation_guard
BEFORE UPDATE OR DELETE ON outbox_events
FOR EACH ROW
EXECUTE FUNCTION enforce_outbox_event_mutation();

CREATE FUNCTION prevent_processed_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'processed event receipts are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER processed_events_immutable
BEFORE UPDATE OR DELETE ON processed_events
FOR EACH ROW
EXECUTE FUNCTION prevent_processed_event_mutation();
