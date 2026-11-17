-- Feature133-A: customer order notification outbox. Records only; nothing here sends mail.
--
-- Every customer-visible order event already appends exactly one immutable history row, whatever
-- the write path (JPA, JdbcTemplate, the Feature125 owned path with its own transaction manager).
-- A deferred constraint trigger on each of those rows records one notification in the same
-- transaction, at commit, when every row of that transaction (e.g. history items written after
-- the history header) is present. The payload is a snapshot taken then and never changes.
--
--   order_acceptances                 -> ORDER_ACCEPTED        (Feature127 formal acceptance)
--   order_content_change_histories    -> ITEMS_CHANGED
--   order_shipping_address_histories  -> ADDRESS_CHANGED
--   order_change_final_decisions      -> CHANGE_NOT_ADOPTED / CHANGE_LATE_ADOPTION (Feature128)
--   order_status_histories CANCELLED  -> ORDER_CANCELLED
--   order_status_histories SHIPPED    -> ORDER_SHIPPED
--
-- (event_type, source_key) is the idempotency key: one notification per source row.
-- Times come from the database clock; callers never supply them.

CREATE TABLE customer_order_notifications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type VARCHAR(30) NOT NULL,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    source_key VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ,
    claim_token UUID,
    claimed_by UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(30),
    delivery_uncertain BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    -- Audit only: the recording transaction. Never a business input.
    recorded_xid XID8 NOT NULL,
    CONSTRAINT uq_customer_order_notifications_source UNIQUE (event_type, source_key),
    CONSTRAINT chk_con_event_type CHECK (event_type IN ('ORDER_ACCEPTED','ITEMS_CHANGED','ADDRESS_CHANGED',
        'CHANGE_NOT_ADOPTED','CHANGE_LATE_ADOPTION','ORDER_CANCELLED','ORDER_SHIPPED')),
    CONSTRAINT chk_con_source_key CHECK (source_key ~ '^[0-9a-f-]{1,64}$'),
    CONSTRAINT chk_con_payload CHECK (jsonb_typeof(payload) = 'object'
        AND payload->>'version' IS NOT DISTINCT FROM '1'
        AND payload->>'eventType' IS NOT DISTINCT FROM event_type
        AND jsonb_typeof(payload->'order') IS NOT DISTINCT FROM 'object'),
    CONSTRAINT chk_con_status CHECK (status IN
        ('PENDING','CLAIMED','SENDING','RETRY_WAIT','SENT','EXHAUSTED','SKIPPED','EXPIRED')),
    CONSTRAINT chk_con_attempts CHECK (max_attempts BETWEEN 1 AND 10 AND attempt_count BETWEEN 0 AND max_attempts),
    CONSTRAINT chk_con_claim CHECK (num_nonnulls(claim_token, claimed_by, lease_until)
        = CASE WHEN status IN ('CLAIMED','SENDING') THEN 3 ELSE 0 END),
    CONSTRAINT chk_con_next_attempt CHECK ((status IN ('PENDING','RETRY_WAIT')) = (next_attempt_at IS NOT NULL)),
    CONSTRAINT chk_con_closed CHECK ((status IN ('SENT','EXHAUSTED','SKIPPED','EXPIRED')) = (closed_at IS NOT NULL)),
    CONSTRAINT chk_con_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT chk_con_close_reason CHECK (COALESCE(CASE status
        WHEN 'EXHAUSTED' THEN close_reason IS NOT NULL AND close_reason IN ('MAX_ATTEMPTS','DELIVERY_UNCERTAIN')
        WHEN 'SKIPPED' THEN close_reason IS NOT NULL AND close_reason IN ('NO_VERIFIED_EMAIL')
        WHEN 'EXPIRED' THEN close_reason IS NOT NULL AND close_reason IN ('STALE')
        ELSE close_reason IS NULL END, FALSE)),
    CONSTRAINT chk_con_sending_attempt CHECK (status NOT IN ('SENDING','SENT','RETRY_WAIT','EXHAUSTED') OR attempt_count > 0),
    CONSTRAINT chk_con_uncertain CHECK (NOT delivery_uncertain OR (attempt_count > 0 AND status NOT IN ('RETRY_WAIT','SENT'))),
    CONSTRAINT chk_con_times CHECK (updated_at >= created_at AND (closed_at IS NULL OR closed_at >= created_at)
        AND (sent_at IS NULL OR sent_at = closed_at)),
    CONSTRAINT chk_con_version CHECK (version >= 0)
);
CREATE INDEX idx_con_due ON customer_order_notifications(next_attempt_at, id) WHERE status IN ('PENDING','RETRY_WAIT');
CREATE INDEX idx_con_lease ON customer_order_notifications(lease_until) WHERE status IN ('CLAIMED','SENDING');
CREATE INDEX idx_con_order ON customer_order_notifications(order_id, id);
CREATE INDEX idx_con_status_created ON customer_order_notifications(status, created_at);

CREATE TABLE customer_order_notification_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES customer_order_notifications(id) ON DELETE RESTRICT,
    attempt_no INTEGER NOT NULL CHECK (attempt_no BETWEEN 1 AND 10),
    claim_token UUID NOT NULL,
    result VARCHAR(16) NOT NULL CHECK (result IN ('IN_PROGRESS','SUCCESS','FAILURE','UNKNOWN')),
    failure_code VARCHAR(40),
    recipient VARCHAR(254) NOT NULL CHECK (length(btrim(recipient)) > 0),
    subject VARCHAR(200) NOT NULL CHECK (length(btrim(subject)) > 0),
    body TEXT NOT NULL CHECK (length(body) > 0),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_cona_attempt UNIQUE (notification_id, attempt_no),
    CONSTRAINT uq_cona_claim UNIQUE (notification_id, claim_token),
    CONSTRAINT chk_cona_finished CHECK ((result = 'IN_PROGRESS') = (finished_at IS NULL)
        AND (finished_at IS NULL OR finished_at >= started_at)),
    CONSTRAINT chk_cona_failure CHECK ((result IN ('IN_PROGRESS','SUCCESS')) = (failure_code IS NULL))
);

-- ---------------------------------------------------------------------------------------------
-- Outbox guard: rows are created only by the recording triggers below, payload and identity never
-- change, only the delivery state machine may move, terminal rows are final and nothing is deleted.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION f133_notification_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    terminal CONSTANT TEXT[] := ARRAY['SENT','EXHAUSTED','SKIPPED','EXPIRED'];
    now_ts TIMESTAMPTZ := clock_timestamp();
    owner BIGINT;
    expected_attempts INTEGER;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Customer order notifications are never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- Depth 1 would be a direct INSERT; the recording triggers call in at depth 2.
        IF pg_trigger_depth() < 2 THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Customer order notifications are recorded only by order event triggers';
        END IF;
        SELECT o.user_id INTO owner FROM orders o WHERE o.id = NEW.order_id;
        IF owner IS DISTINCT FROM NEW.user_id THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Customer order notification recipient must be the order customer';
        END IF;
        NEW.status := 'PENDING'; NEW.attempt_count := 0; NEW.max_attempts := 3;
        NEW.claim_token := NULL; NEW.claimed_by := NULL; NEW.lease_until := NULL;
        NEW.created_at := now_ts; NEW.updated_at := now_ts; NEW.next_attempt_at := now_ts;
        NEW.sent_at := NULL; NEW.closed_at := NULL; NEW.close_reason := NULL;
        NEW.delivery_uncertain := FALSE; NEW.version := 0; NEW.recorded_xid := pg_current_xact_id();
        RETURN NEW;
    END IF;
    -- UPDATE
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.source_key IS DISTINCT FROM OLD.source_key OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.max_attempts IS DISTINCT FROM OLD.max_attempts OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.recorded_xid IS DISTINCT FROM OLD.recorded_xid THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Customer order notification identity and payload are immutable';
    END IF;
    IF OLD.status = ANY(terminal) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A closed customer order notification is final';
    END IF;
    IF NOT ((OLD.status, NEW.status) IN (
            ('PENDING','CLAIMED'), ('PENDING','EXPIRED'),
            ('RETRY_WAIT','CLAIMED'), ('RETRY_WAIT','EXPIRED'),
            ('CLAIMED','SENDING'), ('CLAIMED','PENDING'), ('CLAIMED','RETRY_WAIT'), ('CLAIMED','SKIPPED'), ('CLAIMED','EXPIRED'),
            ('SENDING','SENT'), ('SENDING','RETRY_WAIT'), ('SENDING','EXHAUSTED'))) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Invalid customer order notification state transition';
    END IF;
    -- An attempt is counted only when it is reserved for SMTP (CLAIMED -> SENDING), exactly once.
    -- (PL/pgSQL ends an IF condition at its first THEN, so no CASE inside the condition.)
    expected_attempts := OLD.attempt_count + (NEW.status = 'SENDING')::INTEGER;
    IF NEW.attempt_count IS DISTINCT FROM expected_attempts THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Customer order notification attempt count mismatch';
    END IF;
    IF OLD.status = 'CLAIMED' AND NEW.status = 'PENDING' AND OLD.attempt_count <> 0
        OR OLD.status = 'CLAIMED' AND NEW.status = 'RETRY_WAIT' AND OLD.attempt_count = 0 THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Customer order notification recovery state mismatch';
    END IF;
    IF NEW.status = 'RETRY_WAIT' AND NEW.attempt_count >= NEW.max_attempts
        OR NEW.status = 'EXHAUSTED' AND NEW.close_reason IS NOT DISTINCT FROM 'MAX_ATTEMPTS' AND NEW.attempt_count <> NEW.max_attempts
        OR NEW.status = 'EXHAUSTED' AND NEW.close_reason IS NOT DISTINCT FROM 'DELIVERY_UNCERTAIN' AND NOT NEW.delivery_uncertain THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Customer order notification retry budget mismatch';
    END IF;
    IF OLD.delivery_uncertain AND NOT NEW.delivery_uncertain THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Uncertain delivery is never cleared';
    END IF;
    -- Per-order FIFO: a later event is never claimed while an earlier one of the same order is open.
    IF NEW.status = 'CLAIMED' AND EXISTS (SELECT 1 FROM customer_order_notifications p
            WHERE p.order_id = NEW.order_id AND p.id < NEW.id AND NOT (p.status = ANY(terminal))) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An earlier notification of this order is still open';
    END IF;
    IF NEW.version IS DISTINCT FROM OLD.version + 1 THEN
        RAISE EXCEPTION USING ERRCODE='40001', MESSAGE='Customer order notification version conflict';
    END IF;
    NEW.updated_at := now_ts;
    IF NEW.status = ANY(terminal) THEN
        NEW.closed_at := now_ts;
        NEW.sent_at := CASE WHEN NEW.status = 'SENT' THEN now_ts END;
    ELSE
        NEW.closed_at := NULL; NEW.sent_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f133_notification_guard BEFORE INSERT OR UPDATE OR DELETE ON customer_order_notifications
    FOR EACH ROW EXECUTE FUNCTION f133_notification_guard();
CREATE TRIGGER f133_notification_truncate BEFORE TRUNCATE ON customer_order_notifications
    FOR EACH STATEMENT EXECUTE FUNCTION f133_notification_guard();

-- An attempt is reserved only for the notification's current SENDING claim and is finished once.
CREATE FUNCTION f133_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n customer_order_notifications%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Customer order notification attempts are never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO n FROM customer_order_notifications WHERE id = NEW.notification_id;
        IF n.id IS NULL OR n.status IS DISTINCT FROM 'SENDING' OR n.claim_token IS DISTINCT FROM NEW.claim_token
            OR n.attempt_count IS DISTINCT FROM NEW.attempt_no OR NEW.result IS DISTINCT FROM 'IN_PROGRESS' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Attempt must match the current sending claim';
        END IF;
        NEW.started_at := clock_timestamp(); NEW.finished_at := NULL; NEW.failure_code := NULL;
        RETURN NEW;
    END IF;
    IF OLD.result <> 'IN_PROGRESS' OR NEW.result = 'IN_PROGRESS'
        OR NEW.id IS DISTINCT FROM OLD.id OR NEW.notification_id IS DISTINCT FROM OLD.notification_id
        OR NEW.attempt_no IS DISTINCT FROM OLD.attempt_no OR NEW.claim_token IS DISTINCT FROM OLD.claim_token
        OR NEW.recipient IS DISTINCT FROM OLD.recipient OR NEW.subject IS DISTINCT FROM OLD.subject
        OR NEW.body IS DISTINCT FROM OLD.body OR NEW.started_at IS DISTINCT FROM OLD.started_at THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A notification attempt is finished once and otherwise immutable';
    END IF;
    NEW.finished_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f133_attempt_guard BEFORE INSERT OR UPDATE OR DELETE ON customer_order_notification_attempts
    FOR EACH ROW EXECUTE FUNCTION f133_attempt_guard();
CREATE TRIGGER f133_attempt_truncate BEFORE TRUNCATE ON customer_order_notification_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION f133_attempt_guard();

-- ---------------------------------------------------------------------------------------------
-- Snapshots. No phone number, internal note, administrator name or payment identifier is copied.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION f133_order_snapshot(target BIGINT) RETURNS JSONB LANGUAGE sql STABLE AS $$
    SELECT jsonb_build_object(
        'orderId', o.id, 'orderedAt', o.ordered_at, 'acceptedAt', o.accepted_at,
        'acceptanceMode', o.acceptance_mode, 'status', o.status, 'contentRevision', o.content_revision,
        'itemSubtotal', o.item_subtotal, 'chargeTotal', o.charge_total, 'taxAmount', o.tax_amount,
        'totalAmount', o.total_amount, 'changeDeadlineAt', o.change_deadline_at,
        'items', COALESCE((SELECT jsonb_agg(jsonb_build_object('productName', i.product_name, 'price', i.price,
            'quantity', i.quantity, 'subtotal', i.subtotal, 'taxRate', i.tax_rate) ORDER BY i.id)
            FROM order_items i WHERE i.order_id = o.id), '[]'::jsonb),
        'charges', COALESCE((SELECT jsonb_agg(jsonb_build_object('chargeType', c.charge_type, 'name', c.name,
            'amount', c.amount) ORDER BY c.display_order, c.id)
            FROM order_charges c WHERE c.order_id = o.id), '[]'::jsonb),
        'shipping', jsonb_build_object('name', o.shipping_name, 'postalCode', o.shipping_postal_code,
            'prefecture', o.shipping_prefecture, 'city', o.shipping_city, 'addressLine', o.shipping_address_line))
    FROM orders o WHERE o.id = target
$$;

CREATE FUNCTION f133_record(kind TEXT, target BIGINT, source TEXT, detail JSONB) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE snapshot JSONB := f133_order_snapshot(target);
BEGIN
    IF snapshot IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='Notification order is missing';
    END IF;
    INSERT INTO customer_order_notifications(event_type, order_id, user_id, source_key, payload, status,
            created_at, updated_at, recorded_xid)
    SELECT kind, o.id, o.user_id, source,
           jsonb_build_object('version', 1, 'eventType', kind, 'order', snapshot) || COALESCE(detail, '{}'::jsonb),
           'PENDING', clock_timestamp(), clock_timestamp(), pg_current_xact_id()
    FROM orders o WHERE o.id = target
    ON CONFLICT (event_type, source_key) DO NOTHING;
END;
$$;

CREATE FUNCTION f133_on_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f133_record('ORDER_ACCEPTED', NEW.order_id, NEW.order_id::text,
        jsonb_build_object('acceptedAt', NEW.accepted_at, 'changeDeadlineAt', NEW.change_deadline_at));
    RETURN NULL;
END;
$$;

CREATE FUNCTION f133_on_content_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f133_record('ITEMS_CHANGED', NEW.order_id, NEW.id::text, jsonb_build_object('change', jsonb_build_object(
        'historyId', NEW.id, 'source', NEW.change_source, 'changedAt', NEW.changed_at,
        'oldItemSubtotal', NEW.old_item_subtotal, 'newItemSubtotal', NEW.new_item_subtotal,
        'oldChargeTotal', NEW.old_charge_total, 'newChargeTotal', NEW.new_charge_total,
        'oldTaxAmount', NEW.old_tax_amount, 'newTaxAmount', NEW.new_tax_amount,
        'oldTotalAmount', NEW.old_total_amount, 'newTotalAmount', NEW.new_total_amount,
        'items', COALESCE((SELECT jsonb_agg(jsonb_build_object('productName', i.product_name, 'changeType', i.change_type,
            'oldPrice', i.old_price, 'newPrice', i.new_price, 'oldQuantity', i.old_quantity, 'newQuantity', i.new_quantity,
            'oldSubtotal', i.old_subtotal, 'newSubtotal', i.new_subtotal) ORDER BY i.id)
            FROM order_content_change_items i WHERE i.history_id = NEW.id), '[]'::jsonb),
        'charges', COALESCE((SELECT jsonb_agg(jsonb_build_object('chargeType', c.charge_type, 'changeType', c.change_type,
            'oldName', c.old_name, 'newName', c.new_name, 'oldAmount', c.old_amount, 'newAmount', c.new_amount) ORDER BY c.id)
            FROM order_content_change_charges c WHERE c.history_id = NEW.id), '[]'::jsonb))));
    RETURN NULL;
END;
$$;

CREATE FUNCTION f133_on_address_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f133_record('ADDRESS_CHANGED', NEW.order_id, NEW.id::text, jsonb_build_object(
        'changedAt', NEW.changed_at, 'changedByType', NEW.changed_by_type,
        'oldShipping', jsonb_build_object('name', NEW.old_shipping_name, 'postalCode', NEW.old_shipping_postal_code,
            'prefecture', NEW.old_shipping_prefecture, 'city', NEW.old_shipping_city, 'addressLine', NEW.old_shipping_address_line),
        'newShipping', jsonb_build_object('name', NEW.new_shipping_name, 'postalCode', NEW.new_shipping_postal_code,
            'prefecture', NEW.new_shipping_prefecture, 'city', NEW.new_shipping_city, 'addressLine', NEW.new_shipping_address_line)));
    RETURN NULL;
END;
$$;

CREATE FUNCTION f133_on_final_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f133_record(CASE NEW.outcome WHEN 'LATE_ADOPTION' THEN 'CHANGE_LATE_ADOPTION' ELSE 'CHANGE_NOT_ADOPTED' END,
        NEW.order_id, NEW.operation_id::text, jsonb_build_object(
        'outcome', NEW.outcome, 'shippingDisposition', NEW.shipping_disposition, 'reasonCode', NEW.reason_code,
        'deadlineAt', NEW.deadline_at, 'decidedAt', NEW.decided_at));
    RETURN NULL;
END;
$$;

CREATE FUNCTION f133_on_status_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE;
BEGIN
    SELECT * INTO STRICT o FROM orders WHERE id = NEW.order_id;
    IF NEW.to_status = 'CANCELLED' THEN
        PERFORM f133_record('ORDER_CANCELLED', NEW.order_id, NEW.id::text, jsonb_build_object(
            'changedAt', NEW.changed_at, 'changedByType', NEW.changed_by_type,
            'fromStatus', NEW.from_status, 'accepted', o.accepted_at IS NOT NULL));
    ELSIF NEW.to_status = 'SHIPPED' THEN
        PERFORM f133_record('ORDER_SHIPPED', NEW.order_id, NEW.id::text, jsonb_build_object(
            'changedAt', NEW.changed_at, 'changedByType', NEW.changed_by_type, 'shippedAt', o.shipped_at,
            'warehouseImport', NEW.changed_by_type = 'SYSTEM' AND NEW.changed_by_account_id IS NULL
                AND NEW.changed_by_username = 'WAREHOUSE_SHIPMENT_IMPORT'));
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER f133_record_acceptance AFTER INSERT ON order_acceptances
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f133_on_acceptance();
CREATE CONSTRAINT TRIGGER f133_record_content_change AFTER INSERT ON order_content_change_histories
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f133_on_content_change();
CREATE CONSTRAINT TRIGGER f133_record_address_change AFTER INSERT ON order_shipping_address_histories
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f133_on_address_change();
CREATE CONSTRAINT TRIGGER f133_record_final_decision AFTER INSERT ON order_change_final_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f133_on_final_decision();
CREATE CONSTRAINT TRIGGER f133_record_status_change AFTER INSERT ON order_status_histories
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.to_status IN ('CANCELLED','SHIPPED'))
    EXECUTE FUNCTION f133_on_status_change();

DO $$ DECLARE f RECORD; BEGIN
    FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
        AND proname LIKE 'f133\_%' LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp', f.signature, current_schema());
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f.signature);
    END LOOP;
END $$;
