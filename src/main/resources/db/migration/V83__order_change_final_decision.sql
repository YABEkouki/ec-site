-- Feature128 A-2: irreversible 14:05 final decision for order changes.
--
-- Times are derived from orders.change_deadline_at (Asia/Tokyo wall clock), never from callers:
--   switch cutoff  = deadline + 4 minutes  (last moment the old authorization cancel may be decided)
--   final decision = deadline + 5 minutes  (14:05)
-- grace_until (deadline + 10 minutes), automatic_until and Feature125 evidence are not recalculated.
--
-- A change is adopted only when its operation is COMPLETED. An operation still unresolved at the
-- final decision time receives exactly one append-only decision:
--   NOT_ADOPTED / ORIGINAL : no reauthorization had started; the reservation is released now.
--   NOT_ADOPTED / HOLD     : no switch was decided, but payment work is unresolved (shipping waits).
--   LATE_ADOPTION / HOLD   : the old authorization cancel was already decided; the change can only
--                            move forward (completed later by webhook, reconciliation or an administrator).

CREATE TABLE order_change_final_decisions (
    operation_id UUID PRIMARY KEY REFERENCES order_change_operations(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('NOT_ADOPTED','LATE_ADOPTION')),
    shipping_disposition VARCHAR(20) NOT NULL CHECK (shipping_disposition IN ('ORIGINAL','HOLD')),
    reason_code VARCHAR(40) NOT NULL,
    deadline_at TIMESTAMP NOT NULL,
    final_at TIMESTAMP NOT NULL,
    operation_state VARCHAR(30) NOT NULL,
    operation_version BIGINT NOT NULL,
    switch_decided_at TIMESTAMP,
    decided_at TIMESTAMP NOT NULL,
    -- Audit only: allows a later pg_xact_commit_timestamp comparison. Never a business input.
    decision_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (final_at=deadline_at+INTERVAL '5 minutes'),
    CHECK (decided_at>=final_at),
    CHECK ((outcome='LATE_ADOPTION')=(switch_decided_at IS NOT NULL)),
    CHECK (outcome='NOT_ADOPTED' OR shipping_disposition='HOLD')
);
CREATE INDEX idx_change_final_decisions_order ON order_change_final_decisions(order_id);
CREATE INDEX idx_change_final_due ON order_change_operations(deadline_at,order_id) WHERE state NOT IN ('COMPLETED','ABORTED');

CREATE FUNCTION f128_final_decision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op order_change_operations%ROWTYPE; v_deadline TIMESTAMP; db_now TIMESTAMP;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Order change final decisions are append-only';
    END IF;
    SELECT * INTO op FROM order_change_operations WHERE id=NEW.operation_id;
    SELECT change_deadline_at INTO v_deadline FROM orders WHERE id=NEW.order_id;
    db_now=timezone('Asia/Tokyo',clock_timestamp());
    IF op.id IS NULL OR op.order_id<>NEW.order_id OR v_deadline IS NULL OR NEW.deadline_at IS DISTINCT FROM v_deadline
        OR db_now<v_deadline+INTERVAL '5 minutes' OR op.state IN ('COMPLETED','ABORTED')
        OR NEW.operation_state IS DISTINCT FROM op.state OR NEW.operation_version IS DISTINCT FROM op.version
        OR NEW.switch_decided_at IS DISTINCT FROM op.switch_decided_at OR NEW.decision_xid<>pg_current_xact_id() THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Order change final decision does not match the locked operation or is not due';
    END IF;
    -- Only an operation with no financial work may be returned to the original order immediately.
    IF NEW.shipping_disposition='ORIGINAL' AND (op.state<>'RESERVED' OR op.new_payment_id IS NOT NULL OR op.abort_request_id IS NOT NULL
            OR EXISTS(SELECT 1 FROM order_change_financial_requests WHERE operation_id=op.id)
            OR EXISTS(SELECT 1 FROM payment_transactions WHERE order_change_operation_id=op.id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved payment work requires a shipping hold';
    END IF;
    NEW.final_at=v_deadline+INTERVAL '5 minutes';NEW.decided_at=db_now;NEW.recorded_at=clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f128_final_decision_guard BEFORE INSERT OR UPDATE OR DELETE ON order_change_final_decisions
    FOR EACH ROW EXECUTE FUNCTION f128_final_decision_guard();
CREATE TRIGGER f128_final_decision_truncate BEFORE TRUNCATE ON order_change_final_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION f128_final_decision_guard();

-- The old authorization cancel (switch) may be decided only before deadline + 4 minutes and never
-- after a final decision. Checked on the row and again at commit, with the database clock.
CREATE FUNCTION f128_switch_decision_cutoff() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_deadline TIMESTAMP;
BEGIN
    SELECT change_deadline_at INTO v_deadline FROM orders WHERE id=NEW.order_id;
    IF v_deadline IS NULL OR timezone('Asia/Tokyo',clock_timestamp())>=v_deadline+INTERVAL '4 minutes'
        OR EXISTS(SELECT 1 FROM order_change_final_decisions d WHERE d.operation_id=NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE='EC128',MESSAGE='Order change switch cannot be decided after the switch cutoff';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f128_switch_decision_cutoff BEFORE UPDATE ON order_change_operations
    FOR EACH ROW WHEN (OLD.switch_decided_at IS NULL AND NEW.switch_decided_at IS NOT NULL)
    EXECUTE FUNCTION f128_switch_decision_cutoff();
CREATE CONSTRAINT TRIGGER f128_switch_decision_cutoff_commit AFTER UPDATE ON order_change_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.switch_decided_at IS NULL AND NEW.switch_decided_at IS NOT NULL)
    EXECUTE FUNCTION f128_switch_decision_cutoff();
