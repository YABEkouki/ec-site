-- Feature142-A: closing an order whose checkout payment was never completed (docs/feature142/unpaid-order-closure.md).
--
-- A PAY.JP cancel also cancels a Payment Flow that has just been authorized, and there is no conditional cancel.
-- While a closure is REQUESTED or SENDING the order can therefore not be accepted: the payment cannot become
-- AUTHORIZED, the order cannot be formally accepted and no checkout card send can be created. SENDING is committed
-- before anything is sent and ends only when the Payment Flow is known to be canceled (CLOSED) or PAY.JP has
-- answered that it was not canceled (REJECTED). REQUESTED means nothing has been sent and may be RELEASED.
--   REQUESTED -> SENDING / CLOSED / RELEASED,  SENDING -> CLOSED / REJECTED
-- Sends:  PENDING -> ACCEPTED / REJECTED / UNCONFIRMED / ERROR / EXPIRED, UNCONFIRMED -> the same final ones.
-- No card data, client secret or response body is kept.
-- Lock order: orders, then payments in id order, then the closure. Times are Asia/Tokyo wall-clock values read from
-- the database clock. No function sets timezone.

CREATE TABLE unpaid_order_closures (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    transaction_id BIGINT NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    provider_payment_flow_id VARCHAR(255) NOT NULL CHECK (provider_payment_flow_id ~ '^pfw_[A-Za-z0-9_-]{1,251}$'),
    requested_by_type VARCHAR(20) NOT NULL CHECK (requested_by_type IN ('ADMIN','SYSTEM')),
    requested_by_account_id BIGINT,
    requested_by_username VARCHAR(100) NOT NULL CHECK (btrim(requested_by_username) <> ''),
    internal_note VARCHAR(500),
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','SENDING','CLOSED','RELEASED','REJECTED')),
    release_reason VARCHAR(30) CHECK (release_reason IN ('AUTHORIZED','PROCESSING','UNREADABLE','CHANGED','STALE')),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((requested_by_type IS NOT DISTINCT FROM 'ADMIN') IS NOT DISTINCT FROM (requested_by_account_id IS NOT NULL)),
    CHECK ((status IN ('CLOSED','RELEASED','REJECTED')) IS NOT DISTINCT FROM (completed_at IS NOT NULL)),
    CHECK ((status IS NOT DISTINCT FROM 'RELEASED') IS NOT DISTINCT FROM (release_reason IS NOT NULL))
);
CREATE UNIQUE INDEX uq_unpaid_order_closure_open ON unpaid_order_closures(order_id) WHERE status IN ('REQUESTED','SENDING');
CREATE INDEX idx_unpaid_order_closures_order ON unpaid_order_closures(order_id, id);
CREATE INDEX idx_unpaid_order_closures_payment ON unpaid_order_closures(payment_id);

CREATE TABLE unpaid_order_closure_sends (
    id BIGSERIAL PRIMARY KEY,
    closure_id BIGINT NOT NULL REFERENCES unpaid_order_closures(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(100) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,100}$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REJECTED','UNCONFIRMED','ERROR','EXPIRED')),
    error_code VARCHAR(100) CHECK (error_code ~ '^[A-Za-z0-9_]{1,100}$'),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uq_unpaid_order_closure_send_key UNIQUE (idempotency_key),
    CHECK ((status IN ('PENDING','UNCONFIRMED')) IS NOT DISTINCT FROM (completed_at IS NULL))
);
CREATE UNIQUE INDEX uq_unpaid_order_closure_send_in_flight ON unpaid_order_closure_sends(closure_id)
    WHERE status IN ('PENDING','UNCONFIRMED');

-- The single eligibility rule, shared by the application and the guard below.
CREATE FUNCTION f142_unpaid_order_closable(target BIGINT) RETURNS BOOLEAN LANGUAGE sql VOLATILE AS $$
    SELECT EXISTS (
        SELECT 1 FROM orders o
        JOIN payments p ON p.id = o.active_payment_id AND p.order_id = o.id
        JOIN payment_transactions t ON t.payment_id = p.id
        WHERE o.id = target AND o.status = 'ORDERED' AND o.acceptance_mode = 'AUTHORIZED_ACCEPTANCE'
          AND o.accepted_at IS NULL AND o.shipping_preparation_started_at IS NULL
          AND o.ordered_at <= timezone('Asia/Tokyo', clock_timestamp()) - INTERVAL '60 minutes'
          AND p.provider = 'PAYJP' AND p.payment_method = 'CARD' AND p.status IN ('PENDING','REQUIRES_ACTION')
          AND p.provider_payment_id ~ '^pfw_[A-Za-z0-9_-]{1,251}$' AND p.amount = o.total_amount
          AND t.transaction_type = 'AUTHORIZE' AND t.status = 'PENDING' AND t.purpose = 'INITIAL_ORDER_AUTHORIZATION'
          AND t.order_change_operation_id IS NULL AND t.amount = p.amount
          AND t.order_content_revision = o.content_revision
          AND (SELECT count(*) FROM payment_transactions x WHERE x.payment_id = p.id) = 1
          AND NOT EXISTS (SELECT 1 FROM payments x WHERE x.order_id = o.id AND x.id <> p.id
                          AND x.status NOT IN ('FAILED','CANCELLED'))
          AND NOT EXISTS (SELECT 1 FROM payments x JOIN payment_transactions y ON y.payment_id = x.id
                          WHERE x.order_id = o.id AND y.id <> t.id AND y.status = 'PENDING')
          AND NOT EXISTS (SELECT 1 FROM order_change_operations c WHERE c.order_id = o.id)
          AND NOT EXISTS (SELECT 1 FROM checkout_card_sends s WHERE s.order_id = o.id
                          AND s.status IN ('PENDING','UNCONFIRMED')))
$$;

CREATE FUNCTION f142_closure_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    db_now TIMESTAMP;
    latest unpaid_order_closure_sends%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Unpaid order closures cannot be removed';
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    -- A closure that may still be sent is decided under the order and payment locks.
    IF TG_OP = 'INSERT' OR NEW.status = 'SENDING' THEN
        PERFORM 1 FROM orders WHERE id = NEW.order_id FOR UPDATE;
        PERFORM 1 FROM payments WHERE order_id = NEW.order_id ORDER BY id FOR UPDATE;
        IF NOT f142_unpaid_order_closable(NEW.order_id) OR NOT EXISTS (
                SELECT 1 FROM orders o JOIN payments p ON p.id = o.active_payment_id
                JOIN payment_transactions t ON t.payment_id = p.id
                WHERE o.id = NEW.order_id AND p.id = NEW.payment_id AND t.id = NEW.transaction_id
                  AND p.provider_payment_id = NEW.provider_payment_flow_id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='An unpaid order closure needs an unaccepted order whose initial card authorization is pending';
        END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN
        NEW.status := 'REQUESTED';
        NEW.release_reason := NULL;
        NEW.completed_at := NULL;
        NEW.created_at := db_now;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF ROW(NEW.id, NEW.order_id, NEW.payment_id, NEW.transaction_id, NEW.provider_payment_flow_id, NEW.requested_by_type,
           NEW.requested_by_account_id, NEW.requested_by_username, NEW.internal_note, NEW.created_at, NEW.recorded_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.order_id, OLD.payment_id, OLD.transaction_id, OLD.provider_payment_flow_id,
           OLD.requested_by_type, OLD.requested_by_account_id, OLD.requested_by_username, OLD.internal_note,
           OLD.created_at, OLD.recorded_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An unpaid order closure cannot be redefined';
    END IF;
    IF NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('SENDING','CLOSED','RELEASED'))
            OR (OLD.status = 'SENDING' AND NEW.status IN ('CLOSED','REJECTED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An unpaid order closure status transition is not allowed';
    END IF;
    IF NEW.status = 'REJECTED' THEN
        SELECT * INTO latest FROM unpaid_order_closure_sends WHERE closure_id = NEW.id ORDER BY id DESC LIMIT 1;
        IF latest.id IS NULL OR latest.status IS DISTINCT FROM 'REJECTED' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A closure is rejected only by a rejected cancel';
        END IF;
    END IF;
    IF NEW.status = 'SENDING' THEN
        NEW.completed_at := NULL;
    ELSE
        NEW.completed_at := db_now;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f142_closure_guard BEFORE INSERT OR UPDATE OR DELETE ON unpaid_order_closures
    FOR EACH ROW EXECUTE FUNCTION f142_closure_guard();
CREATE TRIGGER f142_closure_truncate BEFORE TRUNCATE ON unpaid_order_closures
    FOR EACH STATEMENT EXECUTE FUNCTION f142_closure_guard();

CREATE FUNCTION f142_closure_send_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c unpaid_order_closures%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Unpaid order closure sends cannot be removed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO c FROM unpaid_order_closures WHERE id = NEW.closure_id FOR UPDATE;
        IF c.id IS NULL OR c.status IS DISTINCT FROM 'SENDING' OR EXISTS (
                SELECT 1 FROM unpaid_order_closure_sends WHERE closure_id = NEW.closure_id
                AND status NOT IN ('ERROR','EXPIRED')) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A cancel is sent only for a sending closure without an open answer';
        END IF;
        NEW.status := 'PENDING';
        NEW.error_code := NULL;
        NEW.completed_at := NULL;
        NEW.created_at := timezone('Asia/Tokyo', clock_timestamp());
        RETURN NEW;
    END IF;
    IF ROW(NEW.id, NEW.closure_id, NEW.idempotency_key, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.closure_id, OLD.idempotency_key, OLD.created_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An unpaid order closure send cannot be redefined';
    END IF;
    IF NOT ((OLD.status = 'PENDING' AND NEW.status IN ('ACCEPTED','REJECTED','UNCONFIRMED','ERROR','EXPIRED'))
            OR (OLD.status = 'UNCONFIRMED' AND NEW.status IN ('ACCEPTED','REJECTED','ERROR','EXPIRED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An unpaid order closure send status transition is not allowed';
    END IF;
    IF NEW.status = 'UNCONFIRMED' THEN
        NEW.completed_at := NULL;
    ELSE
        NEW.completed_at := timezone('Asia/Tokyo', clock_timestamp());
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f142_closure_send_guard BEFORE INSERT OR UPDATE OR DELETE ON unpaid_order_closure_sends
    FOR EACH ROW EXECUTE FUNCTION f142_closure_send_guard();
CREATE TRIGGER f142_closure_send_truncate BEFORE TRUNCATE ON unpaid_order_closure_sends
    FOR EACH STATEMENT EXECUTE FUNCTION f142_closure_send_guard();

-- The fence: nothing that leads to an accepted order while a closure is open.
CREATE FUNCTION f142_open_closure_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'orders' THEN
        target := NEW.id;
    ELSE
        target := NEW.order_id;
    END IF;
    IF TG_TABLE_NAME = 'checkout_card_sends' THEN
        -- Serializes with a closure being requested.
        PERFORM 1 FROM orders WHERE id = target FOR SHARE;
    END IF;
    IF EXISTS (SELECT 1 FROM unpaid_order_closures WHERE order_id = target AND status IN ('REQUESTED','SENDING')) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The order is being closed as unpaid';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f142_payment_fence BEFORE UPDATE OF status ON payments
    FOR EACH ROW WHEN (NEW.status = 'AUTHORIZED' AND OLD.status IS DISTINCT FROM 'AUTHORIZED')
    EXECUTE FUNCTION f142_open_closure_fence();
CREATE TRIGGER f142_order_acceptance_fence BEFORE UPDATE OF accepted_at ON orders
    FOR EACH ROW WHEN (NEW.accepted_at IS NOT NULL AND OLD.accepted_at IS NULL)
    EXECUTE FUNCTION f142_open_closure_fence();
CREATE TRIGGER f142_card_send_fence BEFORE INSERT ON checkout_card_sends
    FOR EACH ROW EXECUTE FUNCTION f142_open_closure_fence();

-- Commit time: CLOSED exactly with the cancelled order and the failed payment and authorization.
CREATE FUNCTION f142_closure_commit_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'orders' THEN
        IF EXISTS (SELECT 1 FROM unpaid_order_closures WHERE order_id = NEW.id AND status IN ('REQUESTED','SENDING')) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A cancelled order cannot keep an open unpaid closure';
        END IF;
        RETURN NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM orders o JOIN payments p ON p.order_id = o.id
            JOIN payment_transactions t ON t.payment_id = p.id
            WHERE o.id = NEW.order_id AND p.id = NEW.payment_id AND t.id = NEW.transaction_id
              AND o.status = 'CANCELLED' AND o.accepted_at IS NULL AND p.status = 'FAILED' AND t.status = 'FAILED') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A closed unpaid order needs its cancelled order and failed payment';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f142_closed_commit AFTER UPDATE OF status ON unpaid_order_closures
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status = 'CLOSED') EXECUTE FUNCTION f142_closure_commit_check();
CREATE CONSTRAINT TRIGGER f142_cancelled_order_commit AFTER UPDATE OF status ON orders
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status = 'CANCELLED') EXECUTE FUNCTION f142_closure_commit_check();
