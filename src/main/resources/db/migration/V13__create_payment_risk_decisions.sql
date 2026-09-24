CREATE TABLE risk_policy_versions (
    id UUID PRIMARY KEY,
    version_number BIGINT NOT NULL UNIQUE CHECK (version_number > 0),
    enabled BOOLEAN NOT NULL,
    max_payment_amount NUMERIC(19, 2) NOT NULL CHECK (max_payment_amount > 0),
    review_payment_count INTEGER NOT NULL CHECK (review_payment_count > 0),
    review_payment_total NUMERIC(19, 2) NOT NULL CHECK (review_payment_total > 0),
    command_key VARCHAR(255) UNIQUE,
    actor_subject VARCHAR(255) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE risk_policy_activation (
    singleton_id INTEGER PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    policy_id UUID NOT NULL REFERENCES risk_policy_versions (id) ON DELETE RESTRICT
);

INSERT INTO risk_policy_versions
    (id, version_number, enabled, max_payment_amount, review_payment_count,
     review_payment_total, command_key, actor_subject, change_reason, created_at)
VALUES
    ('00000000-0000-4000-8000-000000000001', 1, FALSE, 99999999999999999.99,
     2147483647, 99999999999999999.99, NULL, 'system', 'initial disabled policy', CURRENT_TIMESTAMP);
INSERT INTO risk_policy_activation (singleton_id, policy_id)
VALUES (1, '00000000-0000-4000-8000-000000000001');

CREATE TABLE risk_assessments (
    id UUID PRIMARY KEY,
    payment_idempotency_id UUID NOT NULL REFERENCES payment_idempotency (id) ON DELETE RESTRICT,
    payer_owner_id UUID NOT NULL REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    payer_wallet_id UUID NOT NULL REFERENCES ledger_accounts (id) ON DELETE RESTRICT,
    merchant_wallet_id UUID NOT NULL REFERENCES ledger_accounts (id) ON DELETE RESTRICT,
    policy_id UUID NOT NULL REFERENCES risk_policy_versions (id) ON DELETE RESTRICT,
    request_fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('ALLOW', 'REVIEW', 'BLOCK')),
    rule_codes VARCHAR(128) NOT NULL,
    completed_count INTEGER NOT NULL CHECK (completed_count >= 0),
    completed_total NUMERIC(19, 2) NOT NULL CHECK (completed_total >= 0),
    payment_id UUID UNIQUE REFERENCES payments (id) ON DELETE RESTRICT,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT risk_assessment_payment_check CHECK (
        (outcome = 'ALLOW' AND payment_id IS NOT NULL)
        OR (outcome <> 'ALLOW' AND payment_id IS NULL)
    )
);
CREATE INDEX risk_assessments_idempotency_time_index
    ON risk_assessments (payment_idempotency_id, evaluated_at DESC);

CREATE TABLE risk_review_cases (
    id UUID PRIMARY KEY,
    assessment_id UUID NOT NULL UNIQUE REFERENCES risk_assessments (id) ON DELETE RESTRICT,
    payment_idempotency_id UUID NOT NULL UNIQUE REFERENCES payment_idempotency (id) ON DELETE RESTRICT,
    payer_owner_id UUID NOT NULL REFERENCES wallet_owners (id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL CHECK (status IN
        ('OPEN', 'APPROVED', 'DECLINED', 'EXPIRED', 'POLICY_BLOCKED', 'CONSUMED')),
    open_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approval_expires_at TIMESTAMP WITH TIME ZONE,
    payment_id UUID UNIQUE REFERENCES payments (id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT risk_review_case_completion_check CHECK (
        (status = 'CONSUMED' AND payment_id IS NOT NULL)
        OR (status <> 'CONSUMED' AND payment_id IS NULL)
    )
);
CREATE INDEX risk_review_cases_queue_index ON risk_review_cases (status, created_at, id);
CREATE INDEX risk_review_cases_payer_index ON risk_review_cases (payer_owner_id, created_at DESC);

CREATE TABLE risk_review_actions (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES risk_review_cases (id) ON DELETE RESTRICT,
    actor_subject VARCHAR(255) NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('APPROVE', 'DECLINE')),
    prior_status VARCHAR(20) NOT NULL CHECK (prior_status = 'OPEN'),
    new_status VARCHAR(20) NOT NULL CHECK (new_status IN ('APPROVED', 'DECLINED')),
    reason VARCHAR(500) NOT NULL CHECK (BTRIM(reason) <> ''),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT risk_review_action_transition_check CHECK (
        (action = 'APPROVE' AND new_status = 'APPROVED')
        OR (action = 'DECLINE' AND new_status = 'DECLINED')
    )
);

ALTER TABLE payment_idempotency DROP CONSTRAINT payment_idempotency_state_check;
ALTER TABLE payment_idempotency ADD CONSTRAINT payment_idempotency_state_check
    CHECK (state IN ('PROCESSING', 'REVIEW', 'BLOCKED', 'COMPLETED'));
ALTER TABLE payment_idempotency DROP CONSTRAINT payment_idempotency_completion_check;
ALTER TABLE payment_idempotency ADD CONSTRAINT payment_idempotency_completion_check CHECK (
    (state IN ('PROCESSING', 'REVIEW') AND payment_id IS NULL AND completed_at IS NULL)
    OR (state = 'BLOCKED' AND payment_id IS NULL AND completed_at IS NOT NULL)
    OR (state = 'COMPLETED' AND payment_id IS NOT NULL AND completed_at IS NOT NULL)
);

CREATE OR REPLACE FUNCTION enforce_payment_or_refund_idempotency_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% records are immutable', TG_TABLE_NAME USING ERRCODE = '55000';
    END IF;
    IF NEW.owner_id IS DISTINCT FROM OLD.owner_id
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION '% identity fields are immutable', TG_TABLE_NAME USING ERRCODE = '55000';
    END IF;
    IF TG_TABLE_NAME = 'refund_idempotency' THEN
        IF OLD.state <> 'PROCESSING' OR NEW.state <> 'COMPLETED'
            OR OLD.refund_id IS NOT NULL OR NEW.refund_id IS NULL
            OR OLD.completed_at IS NOT NULL OR NEW.completed_at IS NULL THEN
            RAISE EXCEPTION 'invalid refund idempotency transition' USING ERRCODE = '55000';
        END IF;
    ELSIF NOT (
        (OLD.state = 'PROCESSING' AND NEW.state IN ('REVIEW', 'BLOCKED', 'COMPLETED'))
        OR (OLD.state = 'REVIEW' AND NEW.state IN ('BLOCKED', 'COMPLETED'))
    ) THEN
        RAISE EXCEPTION 'invalid payment idempotency transition' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_risk_fact_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% records are immutable', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER risk_policy_versions_immutable
    BEFORE UPDATE OR DELETE ON risk_policy_versions
    FOR EACH ROW EXECUTE FUNCTION prevent_risk_fact_mutation();
CREATE TRIGGER risk_assessments_immutable
    BEFORE UPDATE OR DELETE ON risk_assessments
    FOR EACH ROW EXECUTE FUNCTION prevent_risk_fact_mutation();
CREATE TRIGGER risk_review_actions_immutable
    BEFORE UPDATE OR DELETE ON risk_review_actions
    FOR EACH ROW EXECUTE FUNCTION prevent_risk_fact_mutation();

CREATE FUNCTION enforce_risk_case_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'risk review cases cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.assessment_id IS DISTINCT FROM OLD.assessment_id
        OR NEW.payment_idempotency_id IS DISTINCT FROM OLD.payment_idempotency_id
        OR NEW.payer_owner_id IS DISTINCT FROM OLD.payer_owner_id
        OR NEW.open_expires_at IS DISTINCT FROM OLD.open_expires_at
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NOT ((OLD.status = 'OPEN' AND NEW.status IN ('APPROVED', 'DECLINED', 'EXPIRED'))
            OR (OLD.status = 'APPROVED' AND NEW.status IN
                ('CONSUMED', 'EXPIRED', 'POLICY_BLOCKED'))) THEN
        RAISE EXCEPTION 'invalid risk review case transition' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER risk_review_cases_transition_guard
    BEFORE UPDATE OR DELETE ON risk_review_cases
    FOR EACH ROW EXECUTE FUNCTION enforce_risk_case_transition();
