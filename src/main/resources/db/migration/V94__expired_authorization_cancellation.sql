-- Feature135-B1: cancelling an order whose card authorization has already expired.
--
-- PAY.JP cancels an expired authorization by itself (status canceled, cancellation_reason expired). When an
-- administrator or the customer cancels such an order, the application first reads the Payment Flow and, if it
-- is already canceled, applies the cancellation without sending a cancel POST. Everything else (order lock,
-- payment locks in ID order, the CANCEL transaction, stock and history) is the existing cancellation.
--
-- This table is the audit record of each cancellation completed without a POST. It is append-only, and a row
-- can only be written for a CANCEL transaction of a payment whose recorded authorization expiry (V93) has
-- passed at the database clock. Times are Asia/Tokyo wall-clock values. No function sets timezone.

CREATE TABLE payment_expired_cancellation_observations (
    transaction_id BIGINT PRIMARY KEY REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    provider_payment_id VARCHAR(255) NOT NULL,
    authorization_expires_at TIMESTAMP NOT NULL,
    expiry_source VARCHAR(20) NOT NULL CHECK (expiry_source IN ('ESTIMATED','PROVIDER')),
    observed_status VARCHAR(30) NOT NULL CHECK (observed_status = 'canceled'),
    observed_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_payment_expired_cancellation_observations_payment ON payment_expired_cancellation_observations(payment_id);

CREATE FUNCTION f135_expired_cancellation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t payment_transactions%ROWTYPE; p payments%ROWTYPE; e payment_authorization_expiries%ROWTYPE;
        db_now TIMESTAMP;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Expired cancellation observations are append-only';
    END IF;
    db_now = timezone('Asia/Tokyo', clock_timestamp());
    SELECT * INTO t FROM payment_transactions WHERE id = NEW.transaction_id;
    SELECT * INTO p FROM payments WHERE id = NEW.payment_id;
    SELECT * INTO e FROM payment_authorization_expiries WHERE payment_id = NEW.payment_id;
    IF t.id IS NULL OR p.id IS NULL OR e.payment_id IS NULL
        OR t.payment_id IS DISTINCT FROM p.id OR t.transaction_type <> 'CANCEL'
        OR t.status NOT IN ('PENDING','SUCCEEDED')
        OR p.provider_payment_id IS DISTINCT FROM NEW.provider_payment_id
        OR e.expires_at > db_now THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='An expired cancellation needs a CANCEL transaction of a payment whose authorization has expired';
    END IF;
    -- The recorded values are always taken from the database.
    NEW.authorization_expires_at = e.expires_at;
    NEW.expiry_source = e.source;
    NEW.observed_at = db_now;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f135_expired_cancellation_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_expired_cancellation_observations
    FOR EACH ROW EXECUTE FUNCTION f135_expired_cancellation_guard();
CREATE TRIGGER f135_expired_cancellation_truncate BEFORE TRUNCATE ON payment_expired_cancellation_observations
    FOR EACH STATEMENT EXECUTE FUNCTION f135_expired_cancellation_guard();

-- Order cancellations are allowed only before the order change deadline (OrderModificationGuard), so a regular
-- ORDER_CANCELLATION transaction is always created before it. One created at or after the deadline must be the
-- expired-authorization cancellation and carry its audit row by commit.
CREATE FUNCTION f135_late_cancellation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE deadline TIMESTAMP;
BEGIN
    IF NEW.transaction_type <> 'CANCEL' OR NEW.purpose IS DISTINCT FROM 'ORDER_CANCELLATION' THEN
        RETURN NULL;
    END IF;
    SELECT o.change_deadline_at INTO deadline FROM payments p JOIN orders o ON o.id = p.order_id WHERE p.id = NEW.payment_id;
    IF deadline IS NOT NULL AND NEW.created_at >= deadline
        AND NOT EXISTS(SELECT 1 FROM payment_expired_cancellation_observations c WHERE c.transaction_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='An order cancellation after the change deadline must be an expired-authorization cancellation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f135_late_cancellation_guard AFTER INSERT ON payment_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f135_late_cancellation_guard();
