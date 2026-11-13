-- Feature127: formal order acceptance time and acceptance-based change deadline.
--
-- An order created by checkout is not formally accepted until its initial PAY.JP authorization
-- (including any 3-D Secure step) succeeds and the server records that success. The acceptance
-- time is the database clock read under the Order lock inside the same transaction that marks the
-- authorization SUCCEEDED. The change deadline (Asia/Tokyo) is 14:00 of the acceptance day when
-- accepted before 14:00, otherwise 14:00 of the next day. Until acceptance the deadline is NULL,
-- so every deadline-based modification or shipping-preparation decision fails closed.
--
-- Rows existing before V81 keep their ordered_at-based deadline as LEGACY. They are never
-- recalculated: open order-edit admissions (V71) are bound to the stored deadline.

ALTER TABLE orders ADD COLUMN acceptance_mode VARCHAR(30) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE orders ADD COLUMN accepted_at TIMESTAMP;
ALTER TABLE orders ALTER COLUMN change_deadline_at DROP NOT NULL;

ALTER TABLE orders ADD CONSTRAINT ck_orders_acceptance_mode
    CHECK (acceptance_mode IN ('LEGACY','AUTHORIZED_ACCEPTANCE'));
ALTER TABLE orders ADD CONSTRAINT ck_orders_legacy_deadline
    CHECK (acceptance_mode<>'LEGACY' OR (change_deadline_at IS NOT NULL AND accepted_at IS NULL));
ALTER TABLE orders ADD CONSTRAINT ck_orders_acceptance_pair
    CHECK (acceptance_mode<>'AUTHORIZED_ACCEPTANCE' OR ((accepted_at IS NULL)=(change_deadline_at IS NULL)));
-- Fixed 14:00 Asia/Tokyo business deadline, consistent with the V71 admission guard.
ALTER TABLE orders ADD CONSTRAINT ck_orders_acceptance_deadline
    CHECK (accepted_at IS NULL OR change_deadline_at=
        CASE WHEN accepted_at<date_trunc('day',accepted_at)+INTERVAL '14 hours'
             THEN date_trunc('day',accepted_at)+INTERVAL '14 hours'
             ELSE date_trunc('day',accepted_at)+INTERVAL '1 day 14 hours' END);

CREATE TABLE order_acceptances (
    order_id BIGINT PRIMARY KEY REFERENCES orders(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL,
    authorization_transaction_id BIGINT NOT NULL UNIQUE REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    accepted_at TIMESTAMP NOT NULL,
    change_deadline_at TIMESTAMP NOT NULL,
    -- Audit only: allows a later pg_xact_commit_timestamp comparison. Never a business input.
    acceptance_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (order_id,payment_id) REFERENCES payments(order_id,id) ON DELETE RESTRICT,
    CHECK (change_deadline_at>accepted_at)
);

-- Orders: acceptance mode is fixed at insert; formal acceptance happens at most once, uses the
-- database clock of the current transaction, and is immutable afterwards.
CREATE FUNCTION f127_order_acceptance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE tx_start TIMESTAMP; db_now TIMESTAMP;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.acceptance_mode='AUTHORIZED_ACCEPTANCE' AND NEW.accepted_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='A new order cannot be inserted as formally accepted';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.acceptance_mode IS DISTINCT FROM OLD.acceptance_mode THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Order acceptance mode is immutable';
    END IF;
    IF NEW.acceptance_mode<>'AUTHORIZED_ACCEPTANCE' THEN RETURN NEW; END IF;
    IF OLD.accepted_at IS NOT NULL THEN
        IF NEW.accepted_at IS DISTINCT FROM OLD.accepted_at OR NEW.change_deadline_at IS DISTINCT FROM OLD.change_deadline_at THEN
            RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Formal order acceptance is immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.accepted_at IS NULL THEN RETURN NEW; END IF;
    -- First acceptance: the stamp must come from this transaction's database clock.
    tx_start=timezone('Asia/Tokyo',transaction_timestamp());db_now=timezone('Asia/Tokyo',clock_timestamp());
    IF NEW.accepted_at<tx_start OR NEW.accepted_at>db_now THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Acceptance time must be the current transaction database time';
    END IF;
    IF OLD.status<>'ORDERED' OR NEW.status<>'ORDERED' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Only an ORDERED order can be formally accepted';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER orders_acceptance_guard BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION f127_order_acceptance_guard();

-- Acceptance evidence must match the accepted order and its successful initial authorization.
CREATE FUNCTION f127_acceptance_record_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE; t payment_transactions%ROWTYPE;
BEGIN
    SELECT * INTO o FROM orders WHERE id=NEW.order_id;
    SELECT * INTO t FROM payment_transactions WHERE id=NEW.authorization_transaction_id;
    IF o.id IS NULL OR t.id IS NULL OR o.acceptance_mode<>'AUTHORIZED_ACCEPTANCE' OR o.accepted_at IS NULL
        OR NEW.accepted_at IS DISTINCT FROM o.accepted_at OR NEW.change_deadline_at IS DISTINCT FROM o.change_deadline_at
        OR t.payment_id<>NEW.payment_id OR t.transaction_type<>'AUTHORIZE' OR t.status<>'SUCCEEDED'
        OR t.purpose<>'INITIAL_ORDER_AUTHORIZATION' OR t.order_change_operation_id IS NOT NULL
        OR NEW.acceptance_xid IS DISTINCT FROM pg_current_xact_id() THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Order acceptance evidence does not match the accepted order';
    END IF;
    NEW.recorded_at=clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER order_acceptances_guard BEFORE INSERT ON order_acceptances
    FOR EACH ROW EXECUTE FUNCTION f127_acceptance_record_guard();

CREATE FUNCTION f127_acceptance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Order acceptance evidence is append-only'; END;
$$;
CREATE TRIGGER order_acceptances_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_acceptances
    FOR EACH STATEMENT EXECUTE FUNCTION f127_acceptance_immutable();

-- An accepted order and its evidence commit together.
CREATE FUNCTION f127_acceptance_commit_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM orders o JOIN order_acceptances a ON a.order_id=o.id
            WHERE o.id=NEW.id AND a.accepted_at=o.accepted_at AND a.change_deadline_at=o.change_deadline_at) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Formal order acceptance must commit with its evidence';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER orders_acceptance_commit_check AFTER UPDATE OF accepted_at ON orders
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.accepted_at IS NULL AND NEW.accepted_at IS NOT NULL)
    EXECUTE FUNCTION f127_acceptance_commit_check();
