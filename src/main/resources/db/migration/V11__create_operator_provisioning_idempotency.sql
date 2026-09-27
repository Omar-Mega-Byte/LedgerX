CREATE TABLE operator_provisioning_requests (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    owner_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT operator_provisioning_owner_type_check CHECK (owner_type IN ('PERSON', 'MERCHANT')),
    CONSTRAINT operator_provisioning_result_check CHECK (
        (owner_id IS NULL AND completed_at IS NULL)
        OR (owner_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE FUNCTION enforce_operator_provisioning_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'operator provisioning requests are retained' USING ERRCODE = '55000';
    END IF;
    IF NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.owner_type IS DISTINCT FROM OLD.owner_type
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR OLD.owner_id IS NOT NULL
        OR NEW.owner_id IS NULL
        OR OLD.completed_at IS NOT NULL
        OR NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'operator provisioning may only complete once' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER operator_provisioning_mutation_guard
BEFORE UPDATE OR DELETE ON operator_provisioning_requests
FOR EACH ROW
EXECUTE FUNCTION enforce_operator_provisioning_mutation();
