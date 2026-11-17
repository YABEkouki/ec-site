-- Feature132-B: shipped CSV files from the warehouse, and the background shipment of each listed order.
--
-- warehouse_shipment_imports is the append-only record of every accepted file (one row per distinct SHA-256).
-- warehouse_shipment_import_lines holds one row per CSV data row. A row is either rejected when the file is
-- uploaded, recorded as already shipped, or left PENDING for the background worker, which ships the order as
-- the system (V89) and records the outcome:
--   PENDING -> SHIPPED | CAPTURE_UNCONFIRMED | CAPTURE_FAILED | ALREADY_SHIPPED | REJECTED
--   CAPTURE_UNCONFIRMED -> SHIPPED | CAPTURE_FAILED   (once reconciliation settles the capture)
-- At most one active row (PENDING / SHIPPED / CAPTURE_UNCONFIRMED / CAPTURE_FAILED) exists per order, so
-- exactly one row is the shipper of an order and carries its carrier and tracking number.
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

CREATE TABLE warehouse_shipment_imports (
    id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL CHECK (file_name ~ '[^[:space:]]'),
    content_sha256 BYTEA NOT NULL CHECK (octet_length(content_sha256) = 32),
    byte_size INTEGER NOT NULL CHECK (byte_size > 0),
    line_count INTEGER NOT NULL CHECK (line_count > 0),
    uploaded_by_account_id BIGINT NOT NULL CHECK (uploaded_by_account_id > 0),
    uploaded_by_username VARCHAR(100) NOT NULL CHECK (uploaded_by_username ~ '[^[:space:]]'),
    uploaded_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_warehouse_shipment_import_content UNIQUE (content_sha256)
);

CREATE TABLE warehouse_shipment_import_lines (
    id BIGSERIAL PRIMARY KEY,
    import_id BIGINT NOT NULL REFERENCES warehouse_shipment_imports(id) ON DELETE RESTRICT,
    line_no INTEGER NOT NULL CHECK (line_no >= 2),
    line_text VARCHAR(1000) NOT NULL,
    batch_id BIGINT REFERENCES warehouse_export_batches(id) ON DELETE RESTRICT,
    order_id BIGINT REFERENCES orders(id) ON DELETE RESTRICT,
    csv_shipped_on DATE,
    carrier VARCHAR(50) CHECK (carrier IS NULL OR (carrier ~ '[^[:space:]]' AND carrier !~ '[[:cntrl:]]')),
    tracking_number VARCHAR(50) CHECK (tracking_number IS NULL OR tracking_number ~ '^[A-Za-z0-9-]+$'),
    state VARCHAR(24) NOT NULL CHECK (state IN
        ('PENDING','SHIPPED','CAPTURE_UNCONFIRMED','CAPTURE_FAILED','ALREADY_SHIPPED','REJECTED')),
    reason VARCHAR(40),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    processed_at TIMESTAMP,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_warehouse_shipment_import_line UNIQUE (import_id, line_no),
    CHECK ((state = 'REJECTED') = (reason IS NOT NULL)),
    CHECK (reason IS NULL OR reason ~ '^[A-Z_]+$'),
    CHECK (state = 'REJECTED' OR (batch_id IS NOT NULL AND order_id IS NOT NULL AND csv_shipped_on IS NOT NULL
        AND carrier IS NOT NULL AND tracking_number IS NOT NULL)),
    CHECK ((state = 'PENDING') = (processed_at IS NULL))
);
CREATE INDEX idx_warehouse_shipment_import_lines_import ON warehouse_shipment_import_lines(import_id, line_no);
CREATE INDEX idx_warehouse_shipment_import_lines_work ON warehouse_shipment_import_lines(id)
    WHERE state IN ('PENDING','CAPTURE_UNCONFIRMED');
CREATE UNIQUE INDEX uq_warehouse_shipment_import_active_order ON warehouse_shipment_import_lines(order_id)
    WHERE state IN ('PENDING','SHIPPED','CAPTURE_UNCONFIRMED','CAPTURE_FAILED');

CREATE FUNCTION f132_import_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse shipment imports are append-only';
    END IF;
    NEW.uploaded_at = timezone('Asia/Tokyo', clock_timestamp()); NEW.recorded_at = clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f132_import_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_shipment_imports
    FOR EACH ROW EXECUTE FUNCTION f132_import_guard();
CREATE TRIGGER f132_import_truncate BEFORE TRUNCATE ON warehouse_shipment_imports
    FOR EACH STATEMENT EXECUTE FUNCTION f132_import_guard();

CREATE FUNCTION f132_import_line_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse shipment import lines cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state NOT IN ('PENDING','ALREADY_SHIPPED','REJECTED') OR NEW.attempts <> 0 THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A new import line is PENDING, ALREADY_SHIPPED or REJECTED';
        END IF;
        IF NEW.state <> 'REJECTED' AND NOT EXISTS(SELECT 1 FROM warehouse_export_lines l
                JOIN warehouse_export_batches b ON b.id = l.batch_id
                WHERE l.batch_id = NEW.batch_id AND l.order_id = NEW.order_id AND b.state = 'SEALED') THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An import line must name an order of a sealed warehouse export batch';
        END IF;
        IF NEW.state = 'ALREADY_SHIPPED' AND NOT EXISTS(SELECT 1 FROM orders WHERE id = NEW.order_id AND status = 'SHIPPED') THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only a shipped order is recorded as already shipped';
        END IF;
        NEW.processed_at = CASE WHEN NEW.state = 'PENDING' THEN NULL ELSE timezone('Asia/Tokyo', clock_timestamp()) END;
        NEW.recorded_at = clock_timestamp();
        RETURN NEW;
    END IF;
    -- UPDATE: only the outcome columns change, along the documented transitions.
    IF ROW(NEW.id, NEW.import_id, NEW.line_no, NEW.line_text, NEW.batch_id, NEW.order_id, NEW.csv_shipped_on,
            NEW.carrier, NEW.tracking_number, NEW.recorded_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.import_id, OLD.line_no, OLD.line_text, OLD.batch_id, OLD.order_id,
            OLD.csv_shipped_on, OLD.carrier, OLD.tracking_number, OLD.recorded_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Import line content is immutable';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Import line attempts never decrease';
    END IF;
    IF NEW.state = OLD.state THEN
        IF OLD.state <> 'PENDING' AND NEW.attempts <> OLD.attempts THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Only a pending import line is attempted';
        END IF;
        NEW.processed_at = OLD.processed_at;
        RETURN NEW;
    END IF;
    IF NOT ((OLD.state = 'PENDING' AND NEW.state IN ('SHIPPED','CAPTURE_UNCONFIRMED','CAPTURE_FAILED','ALREADY_SHIPPED','REJECTED'))
            OR (OLD.state = 'CAPTURE_UNCONFIRMED' AND NEW.state IN ('SHIPPED','CAPTURE_FAILED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Invalid import line state transition';
    END IF;
    IF NEW.state IN ('SHIPPED','CAPTURE_UNCONFIRMED','CAPTURE_FAILED') THEN
        SELECT * INTO o FROM orders WHERE id = NEW.order_id;
        IF o.status IS DISTINCT FROM 'SHIPPED' OR o.shipped_by_account_id IS NOT NULL
                OR o.shipped_by_username IS DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only an order shipped by the warehouse shipment import is recorded as shipped';
        END IF;
        IF NEW.state <> 'SHIPPED' AND o.shipment_capture_payment_id IS NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A shipment without a capture cannot wait for one';
        END IF;
    END IF;
    IF NEW.state = 'ALREADY_SHIPPED' AND NOT EXISTS(SELECT 1 FROM orders WHERE id = NEW.order_id AND status = 'SHIPPED') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only a shipped order is recorded as already shipped';
    END IF;
    NEW.processed_at = timezone('Asia/Tokyo', clock_timestamp());
    RETURN NEW;
END;
$$;
CREATE TRIGGER f132_import_line_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_shipment_import_lines
    FOR EACH ROW EXECUTE FUNCTION f132_import_line_guard();
CREATE TRIGGER f132_import_line_truncate BEFORE TRUNCATE ON warehouse_shipment_import_lines
    FOR EACH STATEMENT EXECUTE FUNCTION f132_import_line_guard();
