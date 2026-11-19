-- Feature135-B2: a shipment capture whose Payment Flow PAY.JP has canceled is settled as FAILED.
--
-- PAY.JP cancels an uncaptured Flow when its authorization expires (status canceled, cancellation_reason expired).
-- A shipped order whose capture is still PENDING can then never be captured. When the application reads such a
-- Flow (reconciliation, webhook, administrator requery or the repeated shipment), it marks the capture FAILED with
-- failure_code 'payment_flow_canceled'. The order stays SHIPPED and the payment AUTHORIZED; the administrator
-- screens show the existing "決済要確認" state.
--
-- This table is the audit record of each such settlement. It is append-only, and a row can only be written for
-- the shipment capture of a shipped, unpaid order. Both directions are required by commit:
--   * a CAPTURE transaction FAILED with failure_code 'payment_flow_canceled' has an observation row;
--   * an observation row belongs to a CAPTURE transaction FAILED with that failure_code.
-- Times are Asia/Tokyo wall-clock values. No function sets timezone.

CREATE TABLE payment_capture_cancellation_observations (
    transaction_id BIGINT PRIMARY KEY REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    provider_payment_id VARCHAR(255) NOT NULL,
    cancellation_reason VARCHAR(40) NOT NULL CHECK (cancellation_reason ~ '^[a-z][a-z0-9_]{0,39}$'),
    observed_status VARCHAR(30) NOT NULL CHECK (observed_status = 'canceled'),
    observed_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_payment_capture_cancellation_observations_order ON payment_capture_cancellation_observations(order_id);

CREATE FUNCTION f135_capture_cancellation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t payment_transactions%ROWTYPE; p payments%ROWTYPE; o orders%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Capture cancellation observations are append-only';
    END IF;
    SELECT * INTO t FROM payment_transactions WHERE id = NEW.transaction_id;
    SELECT * INTO p FROM payments WHERE id = NEW.payment_id;
    SELECT * INTO o FROM orders WHERE id = p.order_id;
    IF t.id IS NULL OR p.id IS NULL OR o.id IS NULL
        OR t.payment_id IS DISTINCT FROM p.id OR t.transaction_type <> 'CAPTURE'
        OR t.status NOT IN ('PENDING','FAILED')
        OR p.provider IS DISTINCT FROM 'PAYJP'
        OR p.provider_payment_id IS DISTINCT FROM NEW.provider_payment_id
        OR o.status <> 'SHIPPED' OR o.shipment_capture_payment_id IS DISTINCT FROM p.id OR o.paid_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='A capture cancellation needs the pending shipment capture of a shipped, unpaid order';
    END IF;
    -- The recorded values are always taken from the database.
    NEW.order_id = o.id;
    NEW.observed_at = timezone('Asia/Tokyo', clock_timestamp());
    RETURN NEW;
END;
$$;
CREATE TRIGGER f135_capture_cancellation_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_capture_cancellation_observations
    FOR EACH ROW EXECUTE FUNCTION f135_capture_cancellation_guard();
CREATE TRIGGER f135_capture_cancellation_truncate BEFORE TRUNCATE ON payment_capture_cancellation_observations
    FOR EACH STATEMENT EXECUTE FUNCTION f135_capture_cancellation_guard();

-- Commit-time pairing, checked against the committed state of both rows.
CREATE FUNCTION f135_capture_cancellation_pairing() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'payment_capture_cancellation_observations' THEN
        target = NEW.transaction_id;
        IF NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.id = target AND t.transaction_type = 'CAPTURE'
                      AND t.status = 'FAILED' AND t.failure_code IS NOT DISTINCT FROM 'payment_flow_canceled') THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='A capture cancellation observation needs its capture failed as payment_flow_canceled';
        END IF;
        RETURN NULL;
    END IF;
    target = NEW.id;
    IF EXISTS(SELECT 1 FROM payment_transactions t WHERE t.id = target AND t.transaction_type = 'CAPTURE'
              AND t.status = 'FAILED' AND t.failure_code IS NOT DISTINCT FROM 'payment_flow_canceled')
        AND NOT EXISTS(SELECT 1 FROM payment_capture_cancellation_observations c WHERE c.transaction_id = target) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='A capture failed as payment_flow_canceled needs its cancellation observation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f135_capture_cancellation_pairing AFTER INSERT OR UPDATE ON payment_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f135_capture_cancellation_pairing();
CREATE CONSTRAINT TRIGGER f135_capture_cancellation_pairing AFTER INSERT ON payment_capture_cancellation_observations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f135_capture_cancellation_pairing();
