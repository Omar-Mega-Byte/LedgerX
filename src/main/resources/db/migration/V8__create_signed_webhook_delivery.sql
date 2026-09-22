CREATE TABLE webhook_endpoints (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    event_types VARCHAR(300) NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    secret_key_version SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    disabled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT webhook_endpoints_owner_fkey
        FOREIGN KEY (owner_id) REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    CONSTRAINT webhook_endpoints_target_url_check CHECK (BTRIM(target_url) <> ''),
    CONSTRAINT webhook_endpoints_event_types_check CHECK (event_types ~ '^\\|(payment\\.completed\\.v1\\|)?(refund\\.completed\\.v1\\|)?$'),
    CONSTRAINT webhook_endpoints_secret_ciphertext_check CHECK (OCTET_LENGTH(secret_ciphertext) > 28),
    CONSTRAINT webhook_endpoints_secret_key_version_check CHECK (secret_key_version > 0),
    CONSTRAINT webhook_endpoints_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT webhook_endpoints_status_shape_check CHECK (
        (status = 'ACTIVE' AND disabled_at IS NULL)
        OR (status = 'DISABLED' AND disabled_at IS NOT NULL)
    )
);

CREATE INDEX webhook_endpoints_active_owner_index
    ON webhook_endpoints (owner_id, created_at DESC)
    WHERE status = 'ACTIVE';

CREATE TABLE webhook_endpoint_idempotency (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    webhook_endpoint_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT webhook_endpoint_idempotency_owner_fkey
        FOREIGN KEY (owner_id) REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    CONSTRAINT webhook_endpoint_idempotency_endpoint_fkey
        FOREIGN KEY (webhook_endpoint_id) REFERENCES webhook_endpoints (id) ON DELETE RESTRICT,
    CONSTRAINT webhook_endpoint_idempotency_owner_key_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT webhook_endpoint_idempotency_endpoint_key UNIQUE (webhook_endpoint_id),
    CONSTRAINT webhook_endpoint_idempotency_key_check CHECK (BTRIM(idempotency_key) <> ''),
    CONSTRAINT webhook_endpoint_idempotency_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT webhook_endpoint_idempotency_state_check CHECK (state IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT webhook_endpoint_idempotency_completion_check CHECK (
        (state = 'PROCESSING' AND webhook_endpoint_id IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND webhook_endpoint_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    webhook_endpoint_id UUID NOT NULL,
    event_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version SMALLINT NOT NULL,
    payload TEXT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    queued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    replay_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_http_status SMALLINT,
    last_error VARCHAR(500),
    CONSTRAINT webhook_deliveries_endpoint_fkey
        FOREIGN KEY (webhook_endpoint_id) REFERENCES webhook_endpoints (id) ON DELETE RESTRICT,
    CONSTRAINT webhook_deliveries_endpoint_event_key UNIQUE (webhook_endpoint_id, event_id),
    CONSTRAINT webhook_deliveries_sequence_check CHECK (aggregate_sequence > 0),
    CONSTRAINT webhook_deliveries_event_type_check
        CHECK (event_type IN ('payment.completed.v1', 'refund.completed.v1')),
    CONSTRAINT webhook_deliveries_schema_version_check CHECK (schema_version = 1),
    CONSTRAINT webhook_deliveries_payload_check CHECK (BTRIM(payload) <> ''),
    CONSTRAINT webhook_deliveries_payload_sha256_check CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT webhook_deliveries_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT webhook_deliveries_replay_count_check CHECK (replay_count >= 0),
    CONSTRAINT webhook_deliveries_http_status_check
        CHECK (last_http_status IS NULL OR (last_http_status >= 100 AND last_http_status <= 599)),
    CONSTRAINT webhook_deliveries_status_check
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'DEAD', 'CANCELLED')),
    CONSTRAINT webhook_deliveries_status_shape_check CHECK (
        (status = 'PENDING' AND lease_token IS NULL AND lease_until IS NULL AND delivered_at IS NULL)
        OR (status = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND delivered_at IS NULL)
        OR (status = 'DELIVERED' AND lease_token IS NULL AND lease_until IS NULL AND delivered_at IS NOT NULL)
        OR (status IN ('DEAD', 'CANCELLED') AND lease_token IS NULL AND lease_until IS NULL AND delivered_at IS NULL)
    )
);

CREATE INDEX webhook_deliveries_eligible_index
    ON webhook_deliveries (status, next_attempt_at, queued_at);

CREATE INDEX webhook_deliveries_endpoint_aggregate_index
    ON webhook_deliveries (webhook_endpoint_id, aggregate_id, aggregate_sequence);

CREATE INDEX webhook_deliveries_endpoint_queued_index
    ON webhook_deliveries (webhook_endpoint_id, queued_at DESC);

CREATE TABLE webhook_delivery_attempts (
    id UUID PRIMARY KEY,
    webhook_delivery_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    http_status SMALLINT,
    duration_millis BIGINT NOT NULL,
    error_category VARCHAR(80),
    CONSTRAINT webhook_delivery_attempts_delivery_fkey
        FOREIGN KEY (webhook_delivery_id) REFERENCES webhook_deliveries (id) ON DELETE RESTRICT,
    CONSTRAINT webhook_delivery_attempts_delivery_number_key
        UNIQUE (webhook_delivery_id, attempt_number),
    CONSTRAINT webhook_delivery_attempts_number_check CHECK (attempt_number > 0),
    CONSTRAINT webhook_delivery_attempts_outcome_check
        CHECK (outcome IN ('DELIVERED', 'RETRYABLE_FAILURE', 'TERMINAL_FAILURE')),
    CONSTRAINT webhook_delivery_attempts_http_status_check
        CHECK (http_status IS NULL OR (http_status >= 100 AND http_status <= 599)),
    CONSTRAINT webhook_delivery_attempts_duration_check CHECK (duration_millis >= 0),
    CONSTRAINT webhook_delivery_attempts_error_category_check
        CHECK (error_category IS NULL OR BTRIM(error_category) <> '')
);

CREATE INDEX webhook_delivery_attempts_delivery_started_index
    ON webhook_delivery_attempts (webhook_delivery_id, started_at DESC);

CREATE FUNCTION enforce_webhook_endpoint_idempotency_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'webhook endpoint idempotency records are retained'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.owner_id IS DISTINCT FROM OLD.owner_id
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'webhook endpoint idempotency identity fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.state <> 'PROCESSING'
        OR NEW.state <> 'COMPLETED'
        OR OLD.completed_at IS NOT NULL
        OR NEW.completed_at IS NULL
        OR OLD.webhook_endpoint_id IS NOT NULL
        OR NEW.webhook_endpoint_id IS NULL THEN
        RAISE EXCEPTION 'webhook endpoint idempotency may only transition from PROCESSING to COMPLETED'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER webhook_endpoint_idempotency_mutation_guard
BEFORE UPDATE OR DELETE ON webhook_endpoint_idempotency
FOR EACH ROW
EXECUTE FUNCTION enforce_webhook_endpoint_idempotency_mutation();

CREATE FUNCTION enforce_webhook_delivery_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'webhook deliveries are retained and cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.webhook_endpoint_id IS DISTINCT FROM OLD.webhook_endpoint_id
        OR NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.aggregate_sequence IS DISTINCT FROM OLD.aggregate_sequence
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.payload_sha256 IS DISTINCT FROM OLD.payload_sha256
        OR NEW.queued_at IS DISTINCT FROM OLD.queued_at THEN
        RAISE EXCEPTION 'webhook delivery business fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NOT (
        (OLD.status = 'PENDING' AND NEW.status = 'IN_FLIGHT'
            AND NEW.attempt_count = OLD.attempt_count + 1
            AND NEW.replay_count = OLD.replay_count)
        OR (OLD.status = 'IN_FLIGHT' AND NEW.status IN ('PENDING', 'DELIVERED', 'DEAD')
            AND NEW.attempt_count = OLD.attempt_count
            AND NEW.replay_count = OLD.replay_count)
        OR (OLD.status = 'PENDING' AND NEW.status = 'CANCELLED'
            AND NEW.attempt_count = OLD.attempt_count
            AND NEW.replay_count = OLD.replay_count)
        OR (OLD.status = 'DEAD' AND NEW.status = 'PENDING'
            AND NEW.attempt_count = 0
            AND NEW.replay_count = OLD.replay_count + 1)
    ) THEN
        RAISE EXCEPTION 'invalid webhook delivery state transition'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER webhook_deliveries_mutation_guard
BEFORE UPDATE OR DELETE ON webhook_deliveries
FOR EACH ROW
EXECUTE FUNCTION enforce_webhook_delivery_mutation();

CREATE FUNCTION prevent_webhook_delivery_attempt_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'webhook delivery attempts are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER webhook_delivery_attempts_immutable
BEFORE UPDATE OR DELETE ON webhook_delivery_attempts
FOR EACH ROW
EXECUTE FUNCTION prevent_webhook_delivery_attempt_mutation();
