ALTER TABLE outbox_events
    ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    ADD COLUMN last_replay_request_id UUID;

CREATE TABLE outbox_replay_requests (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES outbox_events (id) ON DELETE RESTRICT,
    operator_subject VARCHAR(255) NOT NULL CHECK (BTRIM(operator_subject) <> ''),
    idempotency_key VARCHAR(255) NOT NULL CHECK (BTRIM(idempotency_key) <> ''),
    reason VARCHAR(500) NOT NULL CHECK (BTRIM(reason) <> ''),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT outbox_replay_request_key UNIQUE (event_id, idempotency_key)
);

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_last_replay_request_fkey
        FOREIGN KEY (last_replay_request_id) REFERENCES outbox_replay_requests (id) ON DELETE RESTRICT;

CREATE TRIGGER outbox_replay_requests_immutable
BEFORE UPDATE OR DELETE ON outbox_replay_requests
FOR EACH ROW EXECUTE FUNCTION prevent_processed_event_mutation();

CREATE TABLE webhook_secret_reencryptions (
    id UUID PRIMARY KEY,
    webhook_endpoint_id UUID NOT NULL REFERENCES webhook_endpoints (id) ON DELETE RESTRICT,
    old_key_version SMALLINT NOT NULL CHECK (old_key_version > 0),
    new_key_version SMALLINT NOT NULL CHECK (new_key_version > 0),
    operator_subject VARCHAR(255) NOT NULL CHECK (BTRIM(operator_subject) <> ''),
    rotated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT webhook_reencryption_versions_differ CHECK (old_key_version <> new_key_version)
);

CREATE TRIGGER webhook_secret_reencryptions_immutable
BEFORE UPDATE OR DELETE ON webhook_secret_reencryptions
FOR EACH ROW EXECUTE FUNCTION prevent_processed_event_mutation();

CREATE OR REPLACE FUNCTION enforce_outbox_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    ordinary_transition BOOLEAN;
    replay_transition BOOLEAN;
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

    ordinary_transition := (
        (OLD.status = 'PENDING' AND NEW.status = 'IN_FLIGHT'
            AND NEW.attempt_count = OLD.attempt_count + 1)
        OR (OLD.status = 'IN_FLIGHT' AND NEW.status = 'PUBLISHED'
            AND NEW.attempt_count = OLD.attempt_count)
        OR (OLD.status = 'IN_FLIGHT' AND NEW.status = 'PENDING'
            AND NEW.attempt_count = OLD.attempt_count)
    ) AND NEW.replay_count = OLD.replay_count
      AND NEW.last_replay_request_id IS NOT DISTINCT FROM OLD.last_replay_request_id;

    replay_transition := OLD.status = 'PUBLISHED'
        AND NEW.status = 'PENDING'
        AND NEW.attempt_count = OLD.attempt_count
        AND NEW.replay_count = OLD.replay_count + 1
        AND NEW.last_replay_request_id IS DISTINCT FROM OLD.last_replay_request_id
        AND EXISTS (
            SELECT 1 FROM outbox_replay_requests request
            WHERE request.id = NEW.last_replay_request_id AND request.event_id = OLD.id
        );

    IF NOT ordinary_transition AND NOT replay_transition THEN
        RAISE EXCEPTION 'invalid outbox delivery state transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
