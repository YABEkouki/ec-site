-- Preparation only. No backfill, dispatch eligibility reset, financial closure or stock release.
ALTER TABLE order_change_operations
    ADD COLUMN abort_flow_closed_at TIMESTAMPTZ,
    ADD COLUMN abort_flow_close_digest BYTEA,
    ADD COLUMN abort_flow_close_version SMALLINT;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_abort_flow_closure CHECK (
    (abort_flow_closed_at IS NULL AND abort_flow_close_digest IS NULL AND abort_flow_close_version IS NULL)
    OR (abort_flow_closed_at IS NOT NULL AND abort_flow_close_digest IS NOT NULL
        AND octet_length(abort_flow_close_digest)=32 AND abort_flow_close_version IS NOT NULL
        AND abort_flow_close_version=1 AND abort_request_id IS NOT NULL AND new_payment_id IS NOT NULL
        AND switch_decided_at IS NULL AND content_change_history_id IS NULL
        AND state IN ('ABORTING','ABORT_UNKNOWN','MANUAL_REVIEW'))
);
ALTER TABLE order_change_operations DROP CONSTRAINT ck_change_abort_intent;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_abort_intent CHECK (
    (abort_requested_at IS NULL AND abort_request_id IS NULL AND abort_requester_type IS NULL
        AND abort_reason_code IS NULL AND abort_expected_version IS NULL)
    OR (abort_requested_at IS NOT NULL AND abort_request_id IS NOT NULL
        AND abort_requester_type IS NOT NULL AND abort_requester_type IN ('CUSTOMER','ADMINISTRATOR','SYSTEM')
        AND abort_reason_code IS NOT NULL AND abort_reason_code IN
            ('CUSTOMER_REQUEST','BROWSER_INTERRUPTED','AUTHENTICATION_INTERRUPTED','COMMUNICATION_FAILURE','PAYMENT_FAILURE')
        AND abort_expected_version IS NOT NULL AND abort_expected_version>=0 AND switch_decided_at IS NULL
        AND content_change_history_id IS NULL
        AND state IN ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED','MANUAL_REVIEW','ABORTING','ABORT_UNKNOWN'))
);

-- V62's immutable intent/CAS trigger remains installed. These are additional restrictions.
CREATE FUNCTION protect_requested_abort_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.abort_flow_closed_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort closure evidence cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.abort_request_id IS NOT NULL THEN
        IF NEW.state IS DISTINCT FROM OLD.state AND NEW.state NOT IN ('ABORTING','ABORT_UNKNOWN','MANUAL_REVIEW') THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort intent forbids ordinary progress';
        END IF;
        IF (OLD.state='MANUAL_REVIEW' AND NEW.state<>'MANUAL_REVIEW')
            OR (OLD.state='ABORT_UNKNOWN' AND NEW.state NOT IN ('ABORT_UNKNOWN','MANUAL_REVIEW'))
            OR (OLD.state='ABORTING' AND NEW.state NOT IN ('ABORTING','ABORT_UNKNOWN','MANUAL_REVIEW')) THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Requested abort progress cannot resume or rewind';
        END IF;
        IF ROW(NEW.order_id,NEW.customer_id,NEW.old_payment_id,NEW.new_payment_id,NEW.before_amount,NEW.after_amount,NEW.base_content_revision)
            IS DISTINCT FROM ROW(OLD.order_id,OLD.customer_id,OLD.old_payment_id,OLD.new_payment_id,OLD.before_amount,OLD.after_amount,OLD.base_content_revision) THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Requested abort roles are immutable';
        END IF;
        IF NEW IS DISTINCT FROM OLD AND NEW.version<>OLD.version+1 THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Requested abort update requires version CAS';
        END IF;
    END IF;
    IF OLD.abort_flow_closed_at IS NOT NULL AND
        ROW(NEW.abort_flow_closed_at,NEW.abort_flow_close_digest,NEW.abort_flow_close_version)
        IS DISTINCT FROM ROW(OLD.abort_flow_closed_at,OLD.abort_flow_close_digest,OLD.abort_flow_close_version) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort closure evidence is immutable';
    END IF;
    IF OLD.abort_flow_closed_at IS NULL AND NEW.abort_flow_closed_at IS NOT NULL
        AND (OLD.abort_request_id IS NULL OR NEW.version<>OLD.version+1) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort closure requires an existing intent and version CAS';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_requested_abort_progress BEFORE UPDATE OR DELETE ON order_change_operations
    FOR EACH ROW EXECUTE FUNCTION protect_requested_abort_progress();

-- V58 FK/UNIQUE/role and immutable-request triggers remain installed; V61 creates READY.
CREATE OR REPLACE FUNCTION reject_aborted_change_request() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op order_change_operations%ROWTYPE;
        next_payment payments%ROWTYPE;
        creation order_change_financial_requests%ROWTYPE;
BEGIN
    SELECT * INTO STRICT op FROM order_change_operations WHERE id=NEW.operation_id FOR UPDATE;
    IF op.abort_request_id IS NULL THEN RETURN NEW; END IF;
    IF (NEW.request_kind='NEW_ABORT' AND NEW.purpose='ORDER_CHANGE_NEW_AUTH_ABORT'
        AND NEW.transaction_type='CANCEL' AND op.state='ABORTING'
        AND NEW.order_id=op.order_id AND NEW.payment_id=op.new_payment_id AND NEW.payment_id<>op.old_payment_id
        AND op.switch_decided_at IS NULL AND op.content_change_history_id IS NULL AND op.abort_flow_closed_at IS NULL) IS NOT TRUE THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Abort intent allows only dedicated new Payment cancellation preparation';
    END IF;
    IF EXISTS(SELECT 1 FROM order_change_financial_requests WHERE operation_id=op.id AND request_kind='OLD_CANCEL')
        OR EXISTS(SELECT 1 FROM payment_transactions WHERE order_change_operation_id=op.id AND purpose='ORDER_CHANGE_OLD_AUTH_CANCEL') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Old cancellation already exists';
    END IF;
    SELECT * INTO STRICT next_payment FROM payments WHERE id=op.new_payment_id AND order_id=op.order_id;
    SELECT * INTO STRICT creation FROM order_change_financial_requests
        WHERE operation_id=op.id AND request_kind='CREATE';
    IF (next_payment.provider='PAYJP' AND next_payment.payment_method='CARD' AND next_payment.status='AUTHORIZED'
        AND next_payment.provider_payment_id IS NOT NULL AND NEW.provider_flow_id=next_payment.provider_payment_id
        AND NEW.amount=next_payment.amount AND NEW.amount=op.after_amount
        AND creation.payment_id=next_payment.id
        AND ROW(NEW.connection_id,NEW.provider_account_ref,NEW.mode,NEW.api_origin)
            =ROW(creation.connection_id,creation.provider_account_ref,creation.mode,creation.api_origin)) IS NOT TRUE THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='New abort target or connection mismatch';
    END IF;
    RETURN NEW;
END;
$$;
