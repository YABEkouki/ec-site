-- Feature128 A-1: no order-change reauthorization may start at or after the order change deadline.
--
-- The deadline is orders.change_deadline_at (Asia/Tokyo wall clock; NULL before formal acceptance).
-- A reauthorization has two externally visible starts:
--   1. the server CREATE POST of the new PAY.JP Payment Flow, whose single permit is the
--      order_change_request_dispatches READY -> STARTED commit (V61), and
--   2. the browser confirmPayment of that Flow, which the server cannot fence directly. The one
--      durable first-confirm screen (browser_confirmation_issued_at, V60) is therefore treated as
--      its start: a screen issued before the deadline counts as started before the deadline.
-- Both starts require the database clock to be before the deadline, checked when the row changes
-- and again at commit (deferred), because the external effect follows the commit, not the UPDATE.
-- Times are never accepted from callers. Existing rows, grace_until and Feature125 evidence are
-- not recalculated; the 14:05 final decision is a later step (A-2).

CREATE TABLE order_change_deadline_closures (
    operation_id UUID PRIMARY KEY REFERENCES order_change_operations(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    -- BEFORE_CREATE_REQUEST: no new Payment, transaction or financial request existed.
    -- CREATE_NOT_STARTED: the CREATE request existed but its dispatch permit was never consumed.
    phase VARCHAR(30) NOT NULL CHECK (phase IN ('BEFORE_CREATE_REQUEST','CREATE_NOT_STARTED')),
    deadline_at TIMESTAMP NOT NULL,
    operation_state VARCHAR(30) NOT NULL,
    operation_version BIGINT NOT NULL,
    closed_at TIMESTAMP NOT NULL,
    -- Audit only: allows a later pg_xact_commit_timestamp comparison. Never a business input.
    closure_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (closed_at>=deadline_at)
);
CREATE INDEX idx_change_deadline_closures_order ON order_change_deadline_closures(order_id);

CREATE FUNCTION f128_deadline_closure_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op order_change_operations%ROWTYPE; v_deadline TIMESTAMP; db_now TIMESTAMP; create_states TEXT[];
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Order change deadline closures are append-only';
    END IF;
    SELECT * INTO op FROM order_change_operations WHERE id=NEW.operation_id;
    SELECT change_deadline_at INTO v_deadline FROM orders WHERE id=NEW.order_id;
    db_now=timezone('Asia/Tokyo',clock_timestamp());
    IF op.id IS NULL OR op.order_id<>NEW.order_id OR v_deadline IS NULL OR NEW.deadline_at IS DISTINCT FROM v_deadline
        OR db_now<v_deadline OR NEW.operation_state IS DISTINCT FROM op.state OR NEW.operation_version IS DISTINCT FROM op.version
        OR op.switch_decided_at IS NOT NULL OR op.abort_request_id IS NOT NULL OR op.content_change_history_id IS NOT NULL
        OR NEW.closure_xid<>pg_current_xact_id() THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Order change deadline closure does not match the locked operation';
    END IF;
    SELECT coalesce(array_agg(r.request_kind||':'||d.state ORDER BY r.id),'{}') INTO create_states
    FROM order_change_financial_requests r JOIN order_change_request_dispatches d ON d.request_id=r.id
    WHERE r.operation_id=NEW.operation_id;
    IF NEW.phase='BEFORE_CREATE_REQUEST' AND (op.state<>'RESERVED' OR op.new_payment_id IS NOT NULL
            OR EXISTS(SELECT 1 FROM order_change_financial_requests WHERE operation_id=NEW.operation_id)
            OR EXISTS(SELECT 1 FROM payment_transactions WHERE order_change_operation_id=NEW.operation_id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial work already exists for this order change';
    END IF;
    IF NEW.phase='CREATE_NOT_STARTED' AND (op.state<>'AUTHORIZING' OR op.new_payment_id IS NULL
            OR create_states<>ARRAY['CREATE:READY']
            OR EXISTS(SELECT 1 FROM order_change_financial_requests r2 WHERE r2.operation_id=NEW.operation_id
                AND NOT EXISTS(SELECT 1 FROM order_change_request_dispatches d2 WHERE d2.request_id=r2.id))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Authorization request is not provably unstarted';
    END IF;
    NEW.closed_at=db_now;NEW.recorded_at=clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f128_deadline_closure_guard BEFORE INSERT OR UPDATE OR DELETE ON order_change_deadline_closures
    FOR EACH ROW EXECUTE FUNCTION f128_deadline_closure_guard();
CREATE TRIGGER f128_deadline_closure_truncate BEFORE TRUNCATE ON order_change_deadline_closures
    FOR EACH STATEMENT EXECUTE FUNCTION f128_deadline_closure_guard();

-- SQLSTATE EC128 lets the application distinguish "never started because of the deadline".
CREATE FUNCTION f128_create_dispatch_deadline() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_kind TEXT; v_operation UUID; v_deadline TIMESTAMP;
BEGIN
    SELECT r.request_kind,r.operation_id,o.change_deadline_at INTO STRICT v_kind,v_operation,v_deadline
    FROM order_change_financial_requests r JOIN orders o ON o.id=r.order_id WHERE r.id=NEW.request_id;
    IF v_kind<>'CREATE' THEN RETURN NEW; END IF;
    IF v_deadline IS NULL OR timezone('Asia/Tokyo',clock_timestamp())>=v_deadline
        OR EXISTS(SELECT 1 FROM order_change_deadline_closures c WHERE c.operation_id=v_operation) THEN
        RAISE EXCEPTION USING ERRCODE='EC128',MESSAGE='Order change authorization cannot start at or after the change deadline';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f128_create_dispatch_deadline BEFORE UPDATE ON order_change_request_dispatches
    FOR EACH ROW WHEN (OLD.state='READY' AND NEW.state='STARTED') EXECUTE FUNCTION f128_create_dispatch_deadline();
CREATE CONSTRAINT TRIGGER f128_create_dispatch_deadline_commit AFTER UPDATE ON order_change_request_dispatches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.state='READY' AND NEW.state='STARTED') EXECUTE FUNCTION f128_create_dispatch_deadline();

CREATE FUNCTION f128_browser_confirmation_deadline() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_deadline TIMESTAMP;
BEGIN
    SELECT change_deadline_at INTO v_deadline FROM orders WHERE id=NEW.order_id;
    IF v_deadline IS NULL OR timezone('Asia/Tokyo',clock_timestamp())>=v_deadline
        OR EXISTS(SELECT 1 FROM order_change_deadline_closures c WHERE c.operation_id=NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE='EC128',MESSAGE='Order change confirmation cannot be issued at or after the change deadline';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f128_browser_confirmation_deadline BEFORE UPDATE ON order_change_operations
    FOR EACH ROW WHEN (OLD.browser_confirmation_issued_at IS NULL AND NEW.browser_confirmation_issued_at IS NOT NULL)
    EXECUTE FUNCTION f128_browser_confirmation_deadline();
CREATE CONSTRAINT TRIGGER f128_browser_confirmation_deadline_commit AFTER UPDATE ON order_change_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.browser_confirmation_issued_at IS NULL AND NEW.browser_confirmation_issued_at IS NOT NULL)
    EXECUTE FUNCTION f128_browser_confirmation_deadline();
