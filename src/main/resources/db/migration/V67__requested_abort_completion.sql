-- Requested-abort completion only. No backfill, external observations or V64 freeze changes.
-- The application obtains fresh trusted old-authorization GET evidence and holds the existing
-- Order -> operation -> Payment -> transactions -> reservations -> dispatches lock order.
-- These predicates deliberately acquire no upper locks; the digest/timestamp attest to the
-- application observation boundary, whose provider authenticity cannot be checked by SQL.
ALTER TABLE order_change_operations
    ADD COLUMN abort_completed_at TIMESTAMPTZ,
    ADD COLUMN abort_completion_digest BYTEA,
    ADD COLUMN abort_completion_version SMALLINT,
    ADD COLUMN abort_old_authorization_observed_at TIMESTAMPTZ;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_abort_completion CHECK (
    (abort_completed_at IS NULL AND abort_completion_digest IS NULL AND abort_completion_version IS NULL
        AND abort_old_authorization_observed_at IS NULL)
    OR (abort_completed_at IS NOT NULL AND abort_completion_digest IS NOT NULL
        AND octet_length(abort_completion_digest)=32 AND abort_completion_version IS NOT NULL
        AND abort_completion_version=1 AND abort_old_authorization_observed_at IS NOT NULL
        AND abort_request_id IS NOT NULL AND abort_flow_closed_at IS NOT NULL
        AND state='ABORTED' AND switch_decided_at IS NULL AND content_change_history_id IS NULL)
);
-- Intent-bearing terminal rows must carry the entire completion proof. Ordinary legacy
-- non-intent ABORTED rows remain valid.
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_requested_abort_terminal CHECK (
    abort_request_id IS NULL OR state<>'ABORTED' OR abort_completed_at IS NOT NULL
);
ALTER TABLE order_change_operations DROP CONSTRAINT ck_change_abort_flow_closure;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_abort_flow_closure CHECK (
    (abort_flow_closed_at IS NULL AND abort_flow_close_digest IS NULL AND abort_flow_close_version IS NULL)
    OR (abort_flow_closed_at IS NOT NULL AND abort_flow_close_digest IS NOT NULL
        AND octet_length(abort_flow_close_digest)=32 AND abort_flow_close_version IS NOT NULL
        AND abort_flow_close_version=1 AND abort_request_id IS NOT NULL AND new_payment_id IS NOT NULL
        AND switch_decided_at IS NULL AND content_change_history_id IS NULL
        AND state IN ('ABORTING','ABORT_UNKNOWN','MANUAL_REVIEW','ABORTED'))
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
        AND state IN ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED','MANUAL_REVIEW','ABORTING','ABORT_UNKNOWN','ABORTED'))
);

CREATE OR REPLACE FUNCTION protect_requested_abort_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.abort_flow_closed_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort closure evidence cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.abort_request_id IS NOT NULL THEN
        IF NEW.state IS DISTINCT FROM OLD.state AND NEW.state NOT IN ('ABORTING','ABORT_UNKNOWN','MANUAL_REVIEW','ABORTED') THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort intent forbids ordinary progress';
        END IF;
        IF (OLD.state='MANUAL_REVIEW' AND NEW.state<>'MANUAL_REVIEW')
            OR (OLD.state='ABORT_UNKNOWN' AND NEW.state NOT IN ('ABORT_UNKNOWN','MANUAL_REVIEW','ABORTED'))
            OR (OLD.state='ABORTING' AND NEW.state NOT IN ('ABORTING','ABORT_UNKNOWN','MANUAL_REVIEW','ABORTED')) THEN
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
-- Separate guard also covers INSERT, preventing a terminal row bypassing prior saved closure.
CREATE FUNCTION guard_requested_abort_completion() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE next_payment payments%ROWTYPE;
        expected_count INTEGER;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.abort_completed_at IS NOT NULL OR NEW.abort_completion_digest IS NOT NULL
            OR NEW.abort_completion_version IS NOT NULL OR NEW.abort_old_authorization_observed_at IS NOT NULL
            OR (NEW.abort_request_id IS NOT NULL AND NEW.state='ABORTED') THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort completion requires an existing operation';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        IF OLD.abort_completed_at IS NOT NULL OR (OLD.abort_request_id IS NOT NULL AND OLD.state='ABORTED') THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Completed requested abort cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.abort_request_id IS NOT NULL AND OLD.state='ABORTED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Completed requested abort is immutable';
    END IF;
    IF OLD.abort_completed_at IS NOT NULL AND
        ROW(NEW.abort_completed_at,NEW.abort_completion_digest,NEW.abort_completion_version,NEW.abort_old_authorization_observed_at)
        IS DISTINCT FROM ROW(OLD.abort_completed_at,OLD.abort_completion_digest,OLD.abort_completion_version,OLD.abort_old_authorization_observed_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort completion proof is immutable';
    END IF;
    -- The CHECK rejects partial tuples. Even a complete tuple can only be first saved
    -- in the same CAS that completes a previously closed ABORTING/ABORT_UNKNOWN row.
    IF OLD.abort_completed_at IS NULL AND (NEW.abort_completed_at IS NOT NULL
        OR (NEW.abort_request_id IS NOT NULL AND NEW.state='ABORTED')) THEN
        IF (OLD.abort_request_id IS NOT NULL AND OLD.abort_flow_closed_at IS NOT NULL
            AND OLD.state IN ('ABORTING','ABORT_UNKNOWN') AND NEW.state='ABORTED'
            AND NEW.abort_completed_at IS NOT NULL AND NEW.version=OLD.version+1
            AND NEW.switch_decided_at IS NULL AND NEW.content_change_history_id IS NULL) IS NOT TRUE THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Abort completion requires prior saved closure and version CAS';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM orders o JOIN payments p ON p.id=NEW.old_payment_id AND p.order_id=o.id
            WHERE o.id=NEW.order_id AND o.user_id=NEW.customer_id AND o.status='ORDERED'
                AND o.total_amount=NEW.before_amount AND o.content_revision=NEW.base_content_revision
                AND o.active_payment_id=NEW.old_payment_id AND o.shipping_preparation_started_at IS NULL
                AND o.shipping_preparation_started_by_account_id IS NULL
                AND o.shipping_preparation_started_by_username IS NULL AND o.shipment_capture_payment_id IS NULL
                AND p.status='AUTHORIZED' AND p.provider='PAYJP' AND p.payment_method='CARD'
                AND p.amount=NEW.before_amount AND p.provider_payment_id IS NOT NULL)
            OR EXISTS(SELECT 1 FROM order_change_financial_requests WHERE operation_id=NEW.id AND request_kind='OLD_CANCEL')
            OR EXISTS(SELECT 1 FROM payment_transactions WHERE order_change_operation_id=NEW.id AND purpose='ORDER_CHANGE_OLD_AUTH_CANCEL') THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Abort completion old order or authorization mismatch';
        END IF;
        SELECT * INTO STRICT next_payment FROM payments WHERE id=NEW.new_payment_id AND order_id=NEW.order_id;
        IF (next_payment.provider='PAYJP' AND next_payment.payment_method='CARD'
            AND next_payment.amount=NEW.after_amount AND next_payment.provider_payment_id IS NOT NULL
            AND next_payment.status IN ('FAILED','CANCELLED')) IS NOT TRUE THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Abort completion new Payment mismatch';
        END IF;
        expected_count=CASE WHEN next_payment.status='FAILED' THEN 1 ELSE 2 END;
        IF (SELECT count(*) FROM payment_transactions WHERE payment_id=next_payment.id OR order_change_operation_id=NEW.id)<>expected_count
            OR (SELECT count(*) FROM order_change_financial_requests WHERE operation_id=NEW.id)<>expected_count
            OR NOT EXISTS(SELECT 1 FROM payment_transactions t JOIN order_change_financial_requests r ON r.transaction_id=t.id
                JOIN order_change_request_dispatches d ON d.request_id=r.id
                WHERE t.payment_id=next_payment.id AND t.order_change_operation_id=NEW.id
                    AND t.order_change_order_id=NEW.order_id AND t.purpose='ORDER_CHANGE_AUTHORIZATION'
                    AND t.transaction_type='AUTHORIZE' AND t.amount=NEW.after_amount
                    AND t.order_content_revision=NEW.base_content_revision::BIGINT+1 AND t.completed_at IS NOT NULL
                    AND t.status=CASE WHEN next_payment.status='FAILED' THEN 'FAILED' ELSE 'SUCCEEDED' END
                    AND ((next_payment.status='FAILED' AND (t.provider_transaction_id IS NULL OR t.provider_transaction_id=next_payment.provider_payment_id))
                        OR (next_payment.status='CANCELLED' AND t.provider_transaction_id=next_payment.provider_payment_id))
                    AND r.operation_id=NEW.id AND r.request_kind='CREATE' AND d.state='OBSERVED')
            OR (next_payment.status='CANCELLED' AND NOT EXISTS(
                SELECT 1 FROM payment_transactions t JOIN order_change_financial_requests r ON r.transaction_id=t.id
                    JOIN order_change_request_dispatches d ON d.request_id=r.id
                    JOIN order_change_financial_requests c ON c.operation_id=NEW.id AND c.request_kind='CREATE'
                WHERE t.payment_id=next_payment.id AND t.order_change_operation_id=NEW.id
                    AND t.order_change_order_id=NEW.order_id AND t.purpose='ORDER_CHANGE_NEW_AUTH_ABORT'
                    AND t.transaction_type='CANCEL' AND t.status='SUCCEEDED' AND t.amount=NEW.after_amount
                    AND t.order_content_revision=NEW.base_content_revision AND t.completed_at IS NOT NULL
                    AND t.provider_transaction_id=next_payment.provider_payment_id
                    AND r.operation_id=NEW.id AND r.request_kind='NEW_ABORT'
                    AND r.provider_flow_id=next_payment.provider_payment_id AND d.state='OBSERVED'
                    AND ROW(r.connection_id,r.provider_account_ref,r.mode,r.api_origin)
                        =ROW(c.connection_id,c.provider_account_ref,c.mode,c.api_origin)))
            OR EXISTS(SELECT 1 FROM order_change_financial_requests r LEFT JOIN order_change_request_dispatches d ON d.request_id=r.id
                WHERE r.operation_id=NEW.id AND d.state IS DISTINCT FROM 'OBSERVED') THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Abort completion requires canonical observed terminal new history';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM order_change_items WHERE operation_id=NEW.id)
            OR (SELECT count(*) FROM order_change_items WHERE operation_id=NEW.id)
                <>(SELECT count(*) FROM inventory_reservations WHERE operation_id=NEW.id)
            OR EXISTS(SELECT 1 FROM order_change_items i LEFT JOIN inventory_reservations r
                ON r.operation_id=i.operation_id AND r.order_item_id=i.order_item_id
                LEFT JOIN stock_movements m ON m.id=r.release_movement_id
                WHERE i.operation_id=NEW.id AND (r.state IS DISTINCT FROM 'RELEASED'
                    OR r.product_id IS DISTINCT FROM i.product_id OR r.quantity IS DISTINCT FROM i.increase_quantity
                    OR r.released_at IS NULL OR r.consumed_at IS NOT NULL
                    OR m.id IS NULL OR m.product_id IS DISTINCT FROM i.product_id
                    OR m.order_id IS DISTINCT FROM NEW.order_id OR m.quantity IS DISTINCT FROM i.increase_quantity
                    OR m.movement_type IS DISTINCT FROM 'ORDER_CHANGE_RELEASE'
                    OR m.reason IS DISTINCT FROM 'order_change='||NEW.id::TEXT||', order_item='||i.order_item_id::TEXT)) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Abort completion requires every candidate reservation released with matching movement';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_requested_abort_completion BEFORE INSERT OR UPDATE OR DELETE ON order_change_operations
    FOR EACH ROW EXECUTE FUNCTION guard_requested_abort_completion();
