-- Feature131 C-1: warehouse export history and automatic shipping preparation (design doc ch.4 / ch.8).
--
-- The database is the source of truth for what was handed to the warehouse:
--   warehouse_export_batches : one REGULAR batch per business date (= the date of orders.change_deadline_at,
--                              fixed at 14:00 Asia/Tokyo by V81). Opened no earlier than 14:05 of that date,
--                              OPEN -> SEALED once; a sealed batch never receives another line.
--   warehouse_export_lines   : at most one line per order, ever. The line is inserted in the same transaction
--                              that starts shipping preparation; its snapshot (content, amount, payment,
--                              shipping address, items) is taken here from the locked rows, never from callers.
-- A CSV is only a rendering of these immutable rows, so a re-download cannot differ from the first one.
--
-- Automatic preparation is recorded without an administrator: account id NULL and the fixed preparer name
-- 'WAREHOUSE_EXPORT'. Such a preparation is valid only together with its export line (checked at commit).
-- Once an order has been exported (automatic preparation) its content, amount, address, active payment and the
-- preparation itself are frozen, and it can no longer be cancelled, so it cannot drift from the exported line.
-- Administrator-started preparations keep the existing application-only rules (unchanged in C-1).
--
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

ALTER TABLE orders DROP CONSTRAINT chk_orders_shipping_preparation_metadata;
ALTER TABLE orders ADD CONSTRAINT chk_orders_shipping_preparation_metadata CHECK (
    (shipping_preparation_started_at IS NULL
        AND shipping_preparation_started_by_account_id IS NULL
        AND shipping_preparation_started_by_username IS NULL)
    OR
    (shipping_preparation_started_at IS NOT NULL
        AND shipping_preparation_started_by_account_id IS NOT NULL
        AND shipping_preparation_started_by_username IS NOT NULL
        AND shipping_preparation_started_by_username ~ '[^[:space:]]')
    OR
    -- IS NOT DISTINCT FROM: a NULL comparison must not make the whole CHECK pass.
    (shipping_preparation_started_at IS NOT NULL
        AND shipping_preparation_started_by_account_id IS NULL
        AND shipping_preparation_started_by_username IS NOT DISTINCT FROM 'WAREHOUSE_EXPORT')
);

CREATE TABLE warehouse_export_batches (
    id BIGSERIAL PRIMARY KEY,
    business_date DATE NOT NULL,
    kind VARCHAR(20) NOT NULL DEFAULT 'REGULAR' CHECK (kind IN ('REGULAR')),
    state VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','SEALED')),
    opened_at TIMESTAMP NOT NULL,
    sealed_at TIMESTAMP,
    line_count INTEGER,
    -- Audit only: allows a later pg_xact_commit_timestamp comparison. Never a business input.
    open_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_warehouse_export_batch_day UNIQUE (business_date, kind),
    CHECK (opened_at >= business_date + TIME '14:05'),
    CHECK ((state = 'OPEN') = (sealed_at IS NULL)),
    CHECK ((state = 'OPEN') = (line_count IS NULL)),
    CHECK (sealed_at IS NULL OR sealed_at >= opened_at),
    CHECK (line_count IS NULL OR line_count >= 0)
);

CREATE TABLE warehouse_export_lines (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES warehouse_export_batches(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    order_status VARCHAR(20) NOT NULL CHECK (order_status IN ('ORDERED','PAID')),
    content_revision INTEGER NOT NULL,
    total_amount INTEGER NOT NULL,
    payment_id BIGINT REFERENCES payments(id) ON DELETE RESTRICT,
    payment_amount INTEGER,
    change_deadline_at TIMESTAMP NOT NULL,
    prepared_at TIMESTAMP NOT NULL,
    shipping_name VARCHAR(100),
    shipping_postal_code VARCHAR(8),
    shipping_prefecture VARCHAR(20),
    shipping_city VARCHAR(100),
    shipping_address_line VARCHAR(200),
    shipping_phone VARCHAR(20),
    items JSONB NOT NULL,
    -- Audit only, as above.
    export_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_warehouse_export_line_order UNIQUE (order_id),
    CHECK ((order_status = 'ORDERED') = (payment_id IS NOT NULL)),
    CHECK ((payment_id IS NULL) = (payment_amount IS NULL)),
    CHECK (payment_amount IS NULL OR payment_amount >= total_amount),
    CHECK (jsonb_typeof(items) = 'array' AND jsonb_array_length(items) > 0),
    CHECK (prepared_at >= change_deadline_at + INTERVAL '5 minutes')
);
CREATE INDEX idx_warehouse_export_lines_batch ON warehouse_export_lines(batch_id, order_id);

-- Same decision table as OrderEditAdmissionGuard.blocks / AdminOrderHoldViewService.ADMISSION_BLOCKS.
CREATE FUNCTION f131_order_has_blocking_admission(target BIGINT) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT 1 FROM order_edit_admissions a WHERE a.order_id = target AND (case
          when a.closed_at is null then true
          when a.admission_state='ACCEPTED' and a.execution_state='COMPLETED' then a.applied_operation_id is null
          when a.applied_operation_id is not null then true
          when a.admission_state='REJECTED_LATE' then coalesce(a.execution_state,'')<>'NOT_READY'
          else not (coalesce(a.admission_state,'') in ('RECORDED_UNVERIFIED','UNPROVEN','ACCEPTED')
                    and coalesce(a.execution_state,'') in ('CLOSED_UNAPPLIED','FAILED_UNAPPLIED'))
         end))
$$;

CREATE FUNCTION f131_batch_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE db_now TIMESTAMP; lines INTEGER;
BEGIN
    IF TG_OP = 'TRUNCATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse export batches cannot be removed';
    END IF;
    db_now = timezone('Asia/Tokyo', clock_timestamp());
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'OPEN' OR NEW.sealed_at IS NOT NULL OR NEW.line_count IS NOT NULL
            OR NEW.business_date IS NULL OR db_now < NEW.business_date + TIME '14:05' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A warehouse export batch opens only after 14:05 of its business date';
        END IF;
        NEW.opened_at = db_now; NEW.open_xid = pg_current_xact_id(); NEW.recorded_at = clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.state <> 'OPEN' OR NEW.state <> 'SEALED'
        OR ROW(NEW.id, NEW.business_date, NEW.kind, NEW.opened_at, NEW.open_xid, NEW.recorded_at)
           IS DISTINCT FROM ROW(OLD.id, OLD.business_date, OLD.kind, OLD.opened_at, OLD.open_xid, OLD.recorded_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A warehouse export batch can only be sealed once';
    END IF;
    -- The row is locked here; line writers hold FOR SHARE on it until they commit, so the count is final.
    SELECT count(*) INTO lines FROM warehouse_export_lines WHERE batch_id = NEW.id;
    NEW.sealed_at = db_now; NEW.line_count = lines;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f131_batch_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_export_batches
    FOR EACH ROW EXECUTE FUNCTION f131_batch_guard();
CREATE TRIGGER f131_batch_truncate BEFORE TRUNCATE ON warehouse_export_batches
    FOR EACH STATEMENT EXECUTE FUNCTION f131_batch_guard();

-- Lock order: Order -> Payments (id asc) -> batch (FOR SHARE). The application already holds the first two.
CREATE FUNCTION f131_line_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE; b warehouse_export_batches%ROWTYPE; p payments%ROWTYPE;
        db_now TIMESTAMP; tx_start TIMESTAMP; active_count INTEGER;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse export lines are append-only';
    END IF;
    PERFORM f125_require_read_committed();
    SELECT * INTO o FROM orders WHERE id = NEW.order_id FOR UPDATE;
    PERFORM 1 FROM payments WHERE order_id = NEW.order_id ORDER BY id FOR UPDATE;
    SELECT * INTO b FROM warehouse_export_batches WHERE id = NEW.batch_id FOR SHARE;
    db_now = timezone('Asia/Tokyo', clock_timestamp());
    tx_start = timezone('Asia/Tokyo', transaction_timestamp());
    IF o.id IS NULL OR b.id IS NULL OR b.state <> 'OPEN' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Warehouse export line requires an existing order and an open batch';
    END IF;
    -- Prepared by this transaction as an automatic warehouse export, after the final decision time.
    IF o.status NOT IN ('ORDERED','PAID') OR o.change_deadline_at IS NULL
        OR (o.acceptance_mode <> 'LEGACY' AND o.accepted_at IS NULL)
        OR o.shipping_preparation_started_at IS NULL OR o.shipping_preparation_started_by_account_id IS NOT NULL
        OR o.shipping_preparation_started_by_username IS DISTINCT FROM 'WAREHOUSE_EXPORT'
        OR o.shipping_preparation_started_at < tx_start OR o.shipping_preparation_started_at > db_now
        OR o.shipping_preparation_started_at < o.change_deadline_at + INTERVAL '5 minutes'
        OR o.shipping_preparation_started_at < b.business_date + TIME '14:05'
        OR o.change_deadline_at::date > b.business_date
        OR o.shipped_at IS NOT NULL OR o.shipment_capture_payment_id IS NOT NULL OR o.cancelled_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Order is not prepared for this warehouse export';
    END IF;
    -- Design doc ch.8: never export while order change or payment work is unresolved.
    IF EXISTS(SELECT 1 FROM order_change_operations WHERE order_id = o.id AND state NOT IN ('COMPLETED','ABORTED'))
        OR f131_order_has_blocking_admission(o.id)
        OR EXISTS(SELECT 1 FROM payment_transactions t JOIN payments x ON x.id = t.payment_id
                  WHERE x.order_id = o.id AND t.status = 'PENDING') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unresolved order change or payment work blocks the warehouse export';
    END IF;
    SELECT count(*) INTO active_count FROM payments WHERE order_id = o.id AND status NOT IN ('FAILED','CANCELLED');
    IF o.status = 'ORDERED' THEN
        SELECT * INTO p FROM payments WHERE order_id = o.id AND status NOT IN ('FAILED','CANCELLED');
        IF active_count <> 1 OR p.status <> 'AUTHORIZED' OR p.provider <> 'PAYJP' OR p.payment_method <> 'CARD'
            OR p.provider_payment_id IS NULL OR p.provider_payment_id !~ '[^[:space:]]'
            OR (o.active_payment_id IS NOT NULL AND o.active_payment_id <> p.id)
            OR p.amount < o.total_amount
            OR (p.amount = o.total_amount AND NOT EXISTS(
                SELECT 1 FROM payment_transactions t WHERE t.payment_id = p.id AND t.transaction_type = 'AUTHORIZE'
                    AND t.status = 'SUCCEEDED' AND t.amount = p.amount AND t.order_content_revision = o.content_revision)) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The original authorization cannot be proven for the warehouse export';
        END IF;
        NEW.payment_id = p.id; NEW.payment_amount = p.amount;
    ELSE
        IF EXISTS(SELECT 1 FROM payments WHERE order_id = o.id AND status IN ('PENDING','REQUIRES_ACTION','AUTHORIZED')) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A paid order with an open card payment cannot be exported';
        END IF;
        NEW.payment_id = NULL; NEW.payment_amount = NULL;
    END IF;
    NEW.order_status = o.status; NEW.content_revision = o.content_revision; NEW.total_amount = o.total_amount;
    NEW.change_deadline_at = o.change_deadline_at; NEW.prepared_at = o.shipping_preparation_started_at;
    NEW.shipping_name = o.shipping_name; NEW.shipping_postal_code = o.shipping_postal_code;
    NEW.shipping_prefecture = o.shipping_prefecture; NEW.shipping_city = o.shipping_city;
    NEW.shipping_address_line = o.shipping_address_line; NEW.shipping_phone = o.shipping_phone;
    SELECT coalesce(jsonb_agg(jsonb_build_object('orderItemId', i.id, 'productId', i.product_id,
            'productName', i.product_name, 'price', i.price, 'quantity', i.quantity, 'subtotal', i.subtotal) ORDER BY i.id), '[]'::jsonb)
        INTO NEW.items FROM order_items i WHERE i.order_id = o.id;
    NEW.export_xid = pg_current_xact_id(); NEW.recorded_at = clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f131_line_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_export_lines
    FOR EACH ROW EXECUTE FUNCTION f131_line_guard();
CREATE TRIGGER f131_line_truncate BEFORE TRUNCATE ON warehouse_export_lines
    FOR EACH STATEMENT EXECUTE FUNCTION f131_line_guard();

-- Orders: automatic preparation cannot be inserted, and an exported (automatically prepared) order is frozen.
CREATE FUNCTION f131_order_preparation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.shipping_preparation_started_at IS NOT NULL AND NEW.shipping_preparation_started_by_account_id IS NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Automatic shipping preparation requires a warehouse export';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.shipping_preparation_started_at IS NULL OR OLD.shipping_preparation_started_by_account_id IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF ROW(NEW.shipping_preparation_started_at, NEW.shipping_preparation_started_by_account_id, NEW.shipping_preparation_started_by_username)
           IS DISTINCT FROM ROW(OLD.shipping_preparation_started_at, OLD.shipping_preparation_started_by_account_id, OLD.shipping_preparation_started_by_username)
        OR ROW(NEW.user_id, NEW.total_amount, NEW.item_subtotal, NEW.charge_total, NEW.tax_amount, NEW.content_revision,
               NEW.shipping_name, NEW.shipping_postal_code, NEW.shipping_prefecture, NEW.shipping_city,
               NEW.shipping_address_line, NEW.shipping_phone, NEW.active_payment_id)
           IS DISTINCT FROM ROW(OLD.user_id, OLD.total_amount, OLD.item_subtotal, OLD.charge_total, OLD.tax_amount, OLD.content_revision,
               OLD.shipping_name, OLD.shipping_postal_code, OLD.shipping_prefecture, OLD.shipping_city,
               OLD.shipping_address_line, OLD.shipping_phone, OLD.active_payment_id)
        OR (NEW.status = 'CANCELLED' AND OLD.status <> 'CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order is frozen once shipping preparation has started';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f131_order_preparation_guard BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION f131_order_preparation_guard();

CREATE FUNCTION f131_export_preparation_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM warehouse_export_lines l WHERE l.order_id = NEW.id
            AND l.prepared_at = NEW.shipping_preparation_started_at) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Automatic shipping preparation requires its warehouse export line';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f131_export_preparation_commit AFTER UPDATE ON orders
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.shipping_preparation_started_at IS NULL AND NEW.shipping_preparation_started_at IS NOT NULL
          AND NEW.shipping_preparation_started_by_account_id IS NULL)
    EXECUTE FUNCTION f131_export_preparation_commit();

CREATE FUNCTION f131_prepared_content_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP <> 'INSERT' AND EXISTS(SELECT 1 FROM orders WHERE id = OLD.order_id
            AND shipping_preparation_started_at IS NOT NULL AND shipping_preparation_started_by_account_id IS NULL))
        OR (TG_OP <> 'DELETE' AND EXISTS(SELECT 1 FROM orders WHERE id = NEW.order_id
            AND shipping_preparation_started_at IS NOT NULL AND shipping_preparation_started_by_account_id IS NULL)) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Order content is frozen once shipping preparation has started';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER f131_prepared_items_guard BEFORE INSERT OR UPDATE OR DELETE ON order_items
    FOR EACH ROW EXECUTE FUNCTION f131_prepared_content_guard();
CREATE TRIGGER f131_prepared_charges_guard BEFORE INSERT OR UPDATE OR DELETE ON order_charges
    FOR EACH ROW EXECUTE FUNCTION f131_prepared_content_guard();
