-- Feature136-C1: same-day emergency shipping of orders accepted after the 14:00 cutoff.
--
-- Besides the existing emergency shipping of orders past their change deadline (V97, mode AFTER_DEADLINE),
-- an order formally accepted at or after 14:00 (change deadline = 14:00 of the next day) may be shipped on
-- its acceptance day without waiting for the next day's 14:05 warehouse export (mode SAME_DAY).
--   * mode SAME_DAY requires a formally accepted order (AUTHORIZED_ACCEPTANCE) whose deadline is 14:00 of
--     the day after its acceptance, and a preparation time on the acceptance day before that deadline.
--   * the record keeps the acceptance time and whether the operation happened before the deadline by the
--     database clock (observed_before_deadline), for audit.
-- Like V97, the database does not compare the preparation time with its own clock (tests fix the application
-- clock on orders whose acceptance is immutable, V81); the application checks the time window. A reason
-- (the administrator's comment) stays required for both modes. Rows recorded before V99 are AFTER_DEADLINE
-- with no observation.

ALTER TABLE emergency_shipping_preparations
    ADD COLUMN mode VARCHAR(30) NOT NULL DEFAULT 'AFTER_DEADLINE',
    ADD COLUMN accepted_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN observed_before_deadline BOOLEAN;
ALTER TABLE emergency_shipping_preparations ADD CONSTRAINT ck_emergency_shipping_mode
    CHECK (mode IN ('AFTER_DEADLINE', 'SAME_DAY'));
ALTER TABLE emergency_shipping_preparations ADD CONSTRAINT ck_emergency_shipping_same_day
    CHECK (mode IS DISTINCT FROM 'SAME_DAY' OR (accepted_at IS NOT NULL AND observed_before_deadline IS NOT NULL));

CREATE OR REPLACE FUNCTION f136_emergency_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    o orders%ROWTYPE;
    a admin_accounts%ROWTYPE;
    db_now TIMESTAMP WITHOUT TIME ZONE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Emergency shipping records are append-only';
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    -- Lock order: Order, then Payments (read only here), then the administrator.
    SELECT * INTO o FROM orders WHERE id = NEW.order_id FOR UPDATE;
    IF o.id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Emergency shipping requires an existing order';
    END IF;
    SELECT * INTO a FROM admin_accounts WHERE id = NEW.admin_account_id FOR SHARE;
    IF a.id IS NULL OR NOT a.enabled OR NOT a.emergency_shipping_allowed
        OR a.username IS DISTINCT FROM NEW.admin_username THEN
        RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='The administrator is not allowed to start emergency shipping';
    END IF;
    IF o.shipping_preparation_started_at IS NOT NULL OR o.shipping_preparation_started_by_account_id IS NOT NULL
        OR o.shipping_preparation_started_by_username IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Shipping preparation has already started';
    END IF;
    IF o.status NOT IN ('ORDERED', 'PAID') OR o.change_deadline_at IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The order cannot be shipped in an emergency in this state';
    END IF;
    IF NEW.mode IS NULL OR NEW.mode NOT IN ('AFTER_DEADLINE', 'SAME_DAY') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unknown emergency shipping mode';
    END IF;
    IF NEW.mode = 'SAME_DAY' AND (o.acceptance_mode IS DISTINCT FROM 'AUTHORIZED_ACCEPTANCE' OR o.accepted_at IS NULL
            OR o.change_deadline_at IS DISTINCT FROM (o.accepted_at::date + 1) + TIME '14:00'
            OR NEW.prepared_at IS NULL OR NEW.prepared_at::date IS DISTINCT FROM o.accepted_at::date
            OR NEW.prepared_at < o.accepted_at OR NEW.prepared_at >= o.change_deadline_at) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='Same-day emergency shipping requires an order accepted after 14:00, prepared on its acceptance day';
    END IF;
    IF EXISTS(SELECT 1 FROM order_change_operations WHERE order_id = o.id AND state NOT IN ('COMPLETED', 'ABORTED'))
        OR f131_order_has_blocking_admission(o.id)
        OR EXISTS(SELECT 1 FROM payment_transactions t JOIN payments x ON x.id = t.payment_id
                  WHERE x.order_id = o.id AND t.status = 'PENDING') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unresolved order change or payment work blocks emergency shipping';
    END IF;
    IF EXISTS(SELECT 1 FROM payment_discrepancies d JOIN payments x ON x.id = d.payment_id
              WHERE x.order_id = o.id AND d.status = 'OPEN') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An open payment discrepancy blocks emergency shipping';
    END IF;
    NEW.order_status := o.status;
    NEW.content_revision := o.content_revision;
    NEW.total_amount := o.total_amount;
    NEW.active_payment_id := o.active_payment_id;
    NEW.change_deadline_at := o.change_deadline_at;
    NEW.accepted_at := o.accepted_at;
    NEW.observed_before_deadline := db_now < o.change_deadline_at;
    NEW.created_at := db_now;
    RETURN NEW;
END;
$$;
