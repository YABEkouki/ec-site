-- Feature133-C: administrator manual resend of a customer order notification.
--
-- The original notification is never reopened or changed. An administrator records a resend request (who, why,
-- risk acknowledgement); its trigger then records a new notification that copies the original's event, order,
-- recipient user and payload snapshot, linked by resend_of / resend_request_id. The existing worker delivers it.
--
-- A request is accepted only when the latest notification of the chain (the original or its newest copy) is
-- closed without a confirmed delivery (EXHAUSTED, SKIPPED or EXPIRED), at most three per original, and with the
-- risk acknowledged once any delivery of the chain is uncertain. request_nonce makes a resubmitted form idempotent.

ALTER TABLE customer_order_notifications
    ADD COLUMN resend_of BIGINT REFERENCES customer_order_notifications(id) ON DELETE RESTRICT,
    ADD COLUMN resend_request_id BIGINT;

CREATE TABLE customer_order_notification_resends (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES customer_order_notifications(id) ON DELETE RESTRICT,
    request_no INTEGER NOT NULL CHECK (request_no BETWEEN 1 AND 3),
    request_nonce UUID NOT NULL UNIQUE,
    actor_admin_id BIGINT NOT NULL REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    actor_username VARCHAR(100) NOT NULL CHECK (length(btrim(actor_username)) > 0),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0),
    risk_acknowledged BOOLEAN NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_conr_number UNIQUE (notification_id, request_no)
);

ALTER TABLE customer_order_notifications
    ADD CONSTRAINT fk_con_resend_request FOREIGN KEY (resend_request_id)
        REFERENCES customer_order_notification_resends(id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_con_resend_request UNIQUE (resend_request_id),
    ADD CONSTRAINT chk_con_resend_link CHECK ((resend_of IS NULL) = (resend_request_id IS NULL));
CREATE INDEX idx_con_resend_of ON customer_order_notifications(resend_of, id) WHERE resend_of IS NOT NULL;

-- Resend requests: validated against the locked chain, append-only.
CREATE FUNCTION f133_resend_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    original customer_order_notifications%ROWTYPE;
    latest customer_order_notifications%ROWTYPE;
    copies INTEGER;
    uncertain BOOLEAN;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Customer notification resend requests are append-only';
    END IF;
    -- Serializes concurrent requests for the same original.
    SELECT * INTO original FROM customer_order_notifications WHERE id = NEW.notification_id FOR UPDATE;
    IF original.id IS NULL OR original.resend_of IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only an original customer notification can be resent';
    END IF;
    SELECT count(*), bool_or(delivery_uncertain) INTO copies, uncertain
        FROM customer_order_notifications WHERE resend_of = original.id;
    SELECT * INTO latest FROM customer_order_notifications
        WHERE id = original.id OR resend_of = original.id ORDER BY id DESC LIMIT 1;
    IF NOT (latest.status IN ('EXHAUSTED','SKIPPED','EXPIRED')) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The latest notification of this chain is not closed without delivery';
    END IF;
    IF copies >= 3 THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A customer notification is resent at most three times';
    END IF;
    IF (original.delivery_uncertain OR COALESCE(uncertain, FALSE)) AND NOT NEW.risk_acknowledged THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A possibly delivered notification needs an acknowledged risk';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM admin_accounts a WHERE a.id = NEW.actor_admin_id AND a.username = NEW.actor_username) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Resend actor must be an existing administrator';
    END IF;
    NEW.request_no := copies + 1;
    NEW.requested_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f133_resend_guard BEFORE INSERT OR UPDATE OR DELETE ON customer_order_notification_resends
    FOR EACH ROW EXECUTE FUNCTION f133_resend_guard();
CREATE TRIGGER f133_resend_truncate BEFORE TRUNCATE ON customer_order_notification_resends
    FOR EACH STATEMENT EXECUTE FUNCTION f133_resend_guard();

-- Each accepted request records exactly one copy of the original notification in the same transaction.
CREATE FUNCTION f133_on_resend() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO customer_order_notifications(event_type, order_id, user_id, source_key, payload, status,
            created_at, updated_at, recorded_xid, resend_of, resend_request_id)
    SELECT o.event_type, o.order_id, o.user_id, NEW.request_nonce::text, o.payload, 'PENDING',
           clock_timestamp(), clock_timestamp(), pg_current_xact_id(), o.id, NEW.id
    FROM customer_order_notifications o WHERE o.id = NEW.notification_id;
    RETURN NULL;
END;
$$;
CREATE TRIGGER f133_record_resend AFTER INSERT ON customer_order_notification_resends
    FOR EACH ROW EXECUTE FUNCTION f133_on_resend();

-- Resend links: a copy matches its request and original exactly; links never change afterwards.
-- (Runs after f133_notification_guard, which already rejects direct inserts.)
CREATE FUNCTION f133_resend_link_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.resend_of IS DISTINCT FROM OLD.resend_of OR NEW.resend_request_id IS DISTINCT FROM OLD.resend_request_id THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Customer notification resend links are immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.resend_request_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM customer_order_notification_resends r JOIN customer_order_notifications o ON o.id = r.notification_id
            WHERE r.id = NEW.resend_request_id AND o.id IS NOT DISTINCT FROM NEW.resend_of
              AND o.event_type = NEW.event_type AND o.order_id = NEW.order_id AND o.user_id = NEW.user_id
              AND o.payload = NEW.payload AND r.request_nonce::text = NEW.source_key) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A resent notification must copy its request and original';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f133_resend_link_guard BEFORE INSERT OR UPDATE ON customer_order_notifications
    FOR EACH ROW EXECUTE FUNCTION f133_resend_link_guard();

DO $$ DECLARE f RECORD; BEGIN
    FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
        AND proname IN ('f133_resend_guard','f133_on_resend','f133_resend_link_guard') LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp', f.signature, current_schema());
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f.signature);
    END LOOP;
END $$;
