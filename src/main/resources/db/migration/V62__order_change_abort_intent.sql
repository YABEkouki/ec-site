-- Intent only. No backfill, cancellation, reservation release or terminal transition.
ALTER TABLE order_change_operations
    ADD COLUMN abort_requested_at TIMESTAMP,
    ADD COLUMN abort_request_id UUID UNIQUE,
    ADD COLUMN abort_requester_type VARCHAR(20),
    ADD COLUMN abort_reason_code VARCHAR(40),
    ADD COLUMN abort_expected_version BIGINT;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_abort_intent CHECK (
    (abort_requested_at IS NULL AND abort_request_id IS NULL AND abort_requester_type IS NULL
        AND abort_reason_code IS NULL AND abort_expected_version IS NULL)
    OR (abort_requested_at IS NOT NULL AND abort_request_id IS NOT NULL
        AND abort_requester_type IS NOT NULL AND abort_requester_type IN ('CUSTOMER','ADMINISTRATOR','SYSTEM')
        AND abort_reason_code IS NOT NULL AND abort_reason_code IN
            ('CUSTOMER_REQUEST','BROWSER_INTERRUPTED','AUTHENTICATION_INTERRUPTED','COMMUNICATION_FAILURE','PAYMENT_FAILURE')
        AND abort_expected_version IS NOT NULL AND abort_expected_version>=0 AND switch_decided_at IS NULL
        AND state IN ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED','MANUAL_REVIEW'))
);
CREATE FUNCTION protect_order_change_abort_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.abort_request_id IS NOT NULL AND
       ROW(NEW.abort_requested_at,NEW.abort_request_id,NEW.abort_requester_type,NEW.abort_reason_code,NEW.abort_expected_version)
       IS DISTINCT FROM ROW(OLD.abort_requested_at,OLD.abort_request_id,OLD.abort_requester_type,OLD.abort_reason_code,OLD.abort_expected_version) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort intent is immutable';
    END IF;
    IF OLD.abort_request_id IS NULL AND NEW.abort_request_id IS NOT NULL
       AND (NEW.abort_expected_version<>OLD.version OR NEW.version<>OLD.version+1 OR NEW.state<>OLD.state) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort intent requires version CAS and preserves phase';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_change_abort_intent BEFORE UPDATE ON order_change_operations
    FOR EACH ROW EXECUTE FUNCTION protect_order_change_abort_intent();
CREATE FUNCTION reject_aborted_change_request() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE intent UUID;
BEGIN
    SELECT abort_request_id INTO STRICT intent FROM order_change_operations WHERE id=NEW.operation_id FOR UPDATE;
    IF intent IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort intent forbids new financial requests';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_change_request_abort_intent BEFORE INSERT ON order_change_financial_requests
    FOR EACH ROW EXECUTE FUNCTION reject_aborted_change_request();
