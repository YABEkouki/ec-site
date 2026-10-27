-- Explicit administrator GET-only recovery. No backfill or changes to financial-send permissions.
CREATE TABLE order_change_recovery_audits (
    request_id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL,
    operation_id UUID NOT NULL,
    old_cancel_request_id BIGINT NOT NULL REFERENCES order_change_financial_requests(id) ON DELETE RESTRICT,
    actor_id BIGINT NOT NULL CHECK(actor_id>0),
    actor_username VARCHAR(100) NOT NULL CHECK(length(btrim(actor_username))>0),
    reason_code VARCHAR(32) NOT NULL CHECK(reason_code='VERIFY_OLD_CANCEL'),
    original_reason VARCHAR(64) NOT NULL CHECK(original_reason='CANCEL_FLOW_EVIDENCE_MISMATCH'),
    expected_version BIGINT NOT NULL CHECK(expected_version>=0),
    applied_version BIGINT,
    completed_version BIGINT,
    old_flow_id VARCHAR(255) NOT NULL,
    new_flow_id VARCHAR(255) NOT NULL CHECK(new_flow_id<>old_flow_id),
    context_digest BYTEA NOT NULL CHECK(octet_length(context_digest)=32),
    evidence_format INTEGER NOT NULL DEFAULT 1 CHECK(evidence_format=1),
    evidence_digest BYTEA CHECK(evidence_digest IS NULL OR octet_length(evidence_digest)=32),
    result_code VARCHAR(24) NOT NULL CHECK(result_code IN ('STARTED','HOLD','CONFLICT','COMMIT_PENDING','COMPLETED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    content_change_history_id BIGINT UNIQUE REFERENCES order_content_change_histories(id) ON DELETE RESTRICT,
    FOREIGN KEY(operation_id,order_id) REFERENCES order_change_operations(id,order_id) ON DELETE RESTRICT,
    CHECK(updated_at>=started_at AND (finished_at IS NULL OR finished_at>=started_at)),
    CHECK((result_code IN ('STARTED','HOLD','CONFLICT') AND applied_version IS NULL AND completed_version IS NULL
        AND evidence_digest IS NULL AND content_change_history_id IS NULL)
      OR (result_code='COMMIT_PENDING' AND applied_version IS NOT NULL AND applied_version=expected_version+1 AND completed_version IS NULL
        AND evidence_digest IS NOT NULL AND content_change_history_id IS NULL)
      OR (result_code='COMPLETED' AND applied_version IS NOT NULL AND completed_version IS NOT NULL
        AND applied_version=expected_version+1 AND completed_version=applied_version+1
        AND evidence_digest IS NOT NULL AND content_change_history_id IS NOT NULL)),
    CHECK((result_code='STARTED' AND finished_at IS NULL) OR (result_code<>'STARTED' AND finished_at IS NOT NULL))
);
CREATE INDEX idx_change_recovery_operation ON order_change_recovery_audits(operation_id,started_at,request_id);

CREATE FUNCTION protect_old_cancel_recovery_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.result_code IS DISTINCT FROM 'STARTED' THEN
            RAISE EXCEPTION 'Recovery audit must start at STARTED';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM order_change_financial_requests r
            JOIN order_change_operations o ON o.id=r.operation_id AND o.order_id=r.order_id
            JOIN payments prior_payment ON prior_payment.id=o.old_payment_id
            JOIN payments candidate_payment ON candidate_payment.id=o.new_payment_id
            WHERE r.id=NEW.old_cancel_request_id AND r.operation_id=NEW.operation_id AND r.order_id=NEW.order_id
              AND r.request_kind='OLD_CANCEL' AND r.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL' AND r.transaction_type='CANCEL'
              AND r.payment_id=o.old_payment_id AND r.provider_flow_id=NEW.old_flow_id
              AND prior_payment.provider_payment_id=NEW.old_flow_id AND candidate_payment.provider_payment_id=NEW.new_flow_id) THEN
            RAISE EXCEPTION 'Recovery audit target mismatch';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Recovery audit cannot be deleted'; END IF;
    IF ROW(NEW.request_id,NEW.order_id,NEW.operation_id,NEW.old_cancel_request_id,NEW.actor_id,NEW.actor_username,
           NEW.reason_code,NEW.original_reason,NEW.expected_version,NEW.old_flow_id,NEW.new_flow_id,NEW.context_digest,NEW.evidence_format,NEW.started_at)
       IS DISTINCT FROM ROW(OLD.request_id,OLD.order_id,OLD.operation_id,OLD.old_cancel_request_id,OLD.actor_id,OLD.actor_username,
           OLD.reason_code,OLD.original_reason,OLD.expected_version,OLD.old_flow_id,OLD.new_flow_id,OLD.context_digest,OLD.evidence_format,OLD.started_at)
       OR (OLD.applied_version IS NOT NULL AND ROW(NEW.applied_version,NEW.evidence_digest) IS DISTINCT FROM ROW(OLD.applied_version,OLD.evidence_digest))
       OR (OLD.result_code='COMMIT_PENDING' AND NEW.result_code NOT IN ('COMMIT_PENDING','COMPLETED'))
       OR (OLD.result_code IN ('CONFLICT','COMPLETED') AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Recovery identity and committed outcomes are immutable';
    END IF;
    -- Unchanged writes are harmless; reads and INSERT ON CONFLICT DO NOTHING remain idempotent.
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    -- Only transitions exercised by the explicit recovery repository are permitted.
    IF NOT ((OLD.result_code='STARTED' AND NEW.result_code IN ('HOLD','CONFLICT','COMMIT_PENDING'))
         OR (OLD.result_code='HOLD' AND NEW.result_code IN ('HOLD','CONFLICT','COMMIT_PENDING'))
         OR (OLD.result_code='COMMIT_PENDING' AND NEW.result_code='COMPLETED')) THEN
        RAISE EXCEPTION 'Invalid recovery audit transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_old_cancel_recovery_audit BEFORE INSERT OR UPDATE OR DELETE ON order_change_recovery_audits
    FOR EACH ROW EXECUTE FUNCTION protect_old_cancel_recovery_audit();
