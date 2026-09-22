CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    check_version SMALLINT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(16) NOT NULL,
    finding_count INTEGER NOT NULL DEFAULT 0,
    failure_category VARCHAR(100),
    CONSTRAINT reconciliation_runs_check_version_check CHECK (check_version > 0),
    CONSTRAINT reconciliation_runs_status_check CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT reconciliation_runs_finding_count_check CHECK (finding_count >= 0),
    CONSTRAINT reconciliation_runs_status_shape_check CHECK (
        (status = 'RUNNING' AND completed_at IS NULL AND failure_category IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND failure_category IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND failure_category IS NOT NULL)
    )
);

CREATE INDEX reconciliation_runs_started_index ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_findings (
    id UUID PRIMARY KEY,
    reconciliation_run_id UUID NOT NULL,
    finding_type VARCHAR(100) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    fingerprint VARCHAR(64) NOT NULL,
    details JSONB NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT reconciliation_findings_run_fkey
        FOREIGN KEY (reconciliation_run_id) REFERENCES reconciliation_runs (id) ON DELETE RESTRICT,
    CONSTRAINT reconciliation_findings_run_fingerprint_key
        UNIQUE (reconciliation_run_id, fingerprint),
    CONSTRAINT reconciliation_findings_type_check CHECK (BTRIM(finding_type) <> ''),
    CONSTRAINT reconciliation_findings_severity_check CHECK (severity IN ('WARNING', 'ERROR', 'CRITICAL')),
    CONSTRAINT reconciliation_findings_entity_type_check CHECK (BTRIM(entity_type) <> ''),
    CONSTRAINT reconciliation_findings_fingerprint_check CHECK (fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX reconciliation_findings_run_index
    ON reconciliation_findings (reconciliation_run_id, severity, detected_at);

CREATE FUNCTION enforce_reconciliation_run_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'reconciliation runs are retained'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.check_version IS DISTINCT FROM OLD.check_version
        OR NEW.started_at IS DISTINCT FROM OLD.started_at THEN
        RAISE EXCEPTION 'reconciliation run identity fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'RUNNING' OR NEW.status NOT IN ('COMPLETED', 'FAILED')
        OR NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'reconciliation run may only complete or fail once'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER reconciliation_runs_mutation_guard
BEFORE UPDATE OR DELETE ON reconciliation_runs
FOR EACH ROW
EXECUTE FUNCTION enforce_reconciliation_run_mutation();

CREATE FUNCTION prevent_reconciliation_finding_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation findings are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER reconciliation_findings_immutable
BEFORE UPDATE OR DELETE ON reconciliation_findings
FOR EACH ROW
EXECUTE FUNCTION prevent_reconciliation_finding_mutation();
