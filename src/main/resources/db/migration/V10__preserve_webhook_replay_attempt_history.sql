-- A manual replay restarts attempt_number at one. Scope its uniqueness to the replay cycle
-- so append-only history from earlier cycles remains intact.
ALTER TABLE webhook_delivery_attempts
    ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE webhook_delivery_attempts
    ADD CONSTRAINT webhook_delivery_attempts_replay_count_check CHECK (replay_count >= 0);

ALTER TABLE webhook_delivery_attempts
    DROP CONSTRAINT webhook_delivery_attempts_delivery_number_key;

ALTER TABLE webhook_delivery_attempts
    ADD CONSTRAINT webhook_delivery_attempts_delivery_replay_number_key
        UNIQUE (webhook_delivery_id, replay_count, attempt_number);
