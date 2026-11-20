-- Feature136-A: emergency shipping (design doc ch.5 / ch.12-9).
--
-- An administrator-started shipping preparation is now an "emergency shipping" operation:
--   * only an administrator whose admin_accounts.emergency_shipping_allowed is TRUE may start it. The flag is
--     granted and revoked by operator SQL only (the application never writes it); every change is recorded in
--     admin_emergency_shipping_permission_histories by trigger (DB clock and database user).
--   * every administrator preparation has exactly one emergency_shipping_preparations row: operator, reason
--     (required), operation time (DB clock, forced by trigger) and a snapshot of the order at that moment.
--     The row is written first, while the order is still unprepared and locked; the order's preparation must
--     then match it before commit (deferred check). An order cannot be inserted already prepared by an
--     administrator, and the preparation of an emergency-shipped order can no longer be changed or cleared.
--   * the row is refused while order change / payment work is unresolved or a payment discrepancy is open
--     (conflict prevention). Payment eligibility (single active card authorization, decreased-amount policy)
--     stays the application check it was before; the authorization expiry margin is enforced by
--     f135_order_expiry_guard (V93) on the order update.
-- Freezing the order content after preparation (all prepared orders) follows in Feature136-B; the timing rule
-- (after the change deadline only) stays an application rule until Feature136-C decides pre-deadline shipping.
-- Existing administrator preparations (before V97) have no row and are shown as legacy preparations.
-- The operation time of record is created_at (DB clock, set here); prepared_at is the preparation time the
-- application read from the DB clock and must equal the order's, but it is not compared with created_at
-- (tests simulate the deadline with a fixed application clock on orders whose acceptance is immutable, V81).

ALTER TABLE admin_accounts ADD COLUMN emergency_shipping_allowed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE admin_emergency_shipping_permission_histories (
    id BIGSERIAL PRIMARY KEY,
    admin_account_id BIGINT NOT NULL REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    allowed BOOLEAN NOT NULL,
    changed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    changed_by_db_user TEXT NOT NULL
);
CREATE INDEX idx_admin_emergency_shipping_permission_histories_admin
    ON admin_emergency_shipping_permission_histories(admin_account_id, id);

-- History rows are written only by f136_permission_audit (trigger depth > 1) and are append-only.
CREATE FUNCTION f136_permission_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND pg_trigger_depth() > 1 THEN
        NEW.changed_at := timezone('Asia/Tokyo', clock_timestamp());
        NEW.changed_by_db_user := current_user;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE='55000',
        MESSAGE='Emergency shipping permission history is written by the admin account trigger only and is append-only';
END;
$$;
CREATE TRIGGER f136_permission_history_guard BEFORE INSERT OR UPDATE OR DELETE
    ON admin_emergency_shipping_permission_histories
    FOR EACH ROW EXECUTE FUNCTION f136_permission_history_guard();
CREATE TRIGGER f136_permission_history_truncate BEFORE TRUNCATE ON admin_emergency_shipping_permission_histories
    FOR EACH STATEMENT EXECUTE FUNCTION f136_permission_history_guard();

CREATE FUNCTION f136_permission_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.emergency_shipping_allowed)
        OR (TG_OP = 'UPDATE' AND NEW.emergency_shipping_allowed IS DISTINCT FROM OLD.emergency_shipping_allowed) THEN
        INSERT INTO admin_emergency_shipping_permission_histories(admin_account_id, allowed, changed_at, changed_by_db_user)
        VALUES (NEW.id, NEW.emergency_shipping_allowed, timezone('Asia/Tokyo', clock_timestamp()), current_user);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER f136_permission_audit AFTER INSERT OR UPDATE OF emergency_shipping_allowed ON admin_accounts
    FOR EACH ROW EXECUTE FUNCTION f136_permission_audit();

CREATE TABLE emergency_shipping_preparations (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE REFERENCES orders(id) ON DELETE RESTRICT,
    admin_account_id BIGINT NOT NULL REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    admin_username VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    prepared_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    order_status VARCHAR(30) NOT NULL,
    content_revision INTEGER NOT NULL,
    total_amount INTEGER NOT NULL,
    active_payment_id BIGINT,
    change_deadline_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CHECK (reason ~ '[^[:space:]]'),
    CHECK (admin_username ~ '[^[:space:]]'),
    CHECK (order_status IN ('ORDERED', 'PAID'))
);
CREATE INDEX idx_emergency_shipping_preparations_created ON emergency_shipping_preparations(created_at DESC, id DESC);

CREATE FUNCTION f136_emergency_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
    NEW.created_at := db_now;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f136_emergency_guard BEFORE INSERT OR UPDATE OR DELETE ON emergency_shipping_preparations
    FOR EACH ROW EXECUTE FUNCTION f136_emergency_guard();
CREATE TRIGGER f136_emergency_truncate BEFORE TRUNCATE ON emergency_shipping_preparations
    FOR EACH STATEMENT EXECUTE FUNCTION f136_emergency_guard();

-- At commit the order must carry exactly the preparation the record describes.
CREATE FUNCTION f136_emergency_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM orders o WHERE o.id = NEW.order_id
            AND o.shipping_preparation_started_at IS NOT DISTINCT FROM NEW.prepared_at
            AND o.shipping_preparation_started_by_account_id IS NOT DISTINCT FROM NEW.admin_account_id
            AND o.shipping_preparation_started_by_username IS NOT DISTINCT FROM NEW.admin_username) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An emergency shipping record requires the matching shipping preparation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f136_emergency_commit AFTER INSERT ON emergency_shipping_preparations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f136_emergency_commit();

-- Orders: an administrator preparation needs its emergency record, and that preparation is then fixed.
CREATE FUNCTION f136_order_emergency_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.shipping_preparation_started_at IS NOT NULL AND NEW.shipping_preparation_started_by_account_id IS NOT NULL
            AND NEW.shipping_preparation_started_by_username IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='Administrator shipping preparation requires an emergency shipping record';
        END IF;
        RETURN NEW;
    END IF;
    -- Partial metadata is left to chk_orders_shipping_preparation_metadata (V53/V86).
    IF OLD.shipping_preparation_started_at IS NULL AND NEW.shipping_preparation_started_at IS NOT NULL
        AND NEW.shipping_preparation_started_by_account_id IS NOT NULL
        AND NEW.shipping_preparation_started_by_username IS NOT NULL
        AND NOT EXISTS(SELECT 1 FROM emergency_shipping_preparations r WHERE r.order_id = NEW.id
            AND r.admin_account_id = NEW.shipping_preparation_started_by_account_id
            AND r.admin_username IS NOT DISTINCT FROM NEW.shipping_preparation_started_by_username
            AND r.prepared_at IS NOT DISTINCT FROM NEW.shipping_preparation_started_at) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='Administrator shipping preparation requires an emergency shipping record';
    END IF;
    IF ROW(NEW.shipping_preparation_started_at, NEW.shipping_preparation_started_by_account_id,
           NEW.shipping_preparation_started_by_username)
           IS DISTINCT FROM ROW(OLD.shipping_preparation_started_at, OLD.shipping_preparation_started_by_account_id,
           OLD.shipping_preparation_started_by_username)
        AND EXISTS(SELECT 1 FROM emergency_shipping_preparations r WHERE r.order_id = OLD.id
            AND r.prepared_at IS NOT DISTINCT FROM OLD.shipping_preparation_started_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An emergency shipping preparation cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f136_order_emergency_guard BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION f136_order_emergency_guard();
