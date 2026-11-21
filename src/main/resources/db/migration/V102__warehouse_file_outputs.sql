-- Feature137-B: warehouse file outputs (docs/feature137/warehouse-file-output.md ch.4).
--
-- warehouse_file_outputs has one row per output: a sealed 14:05 batch (REGULAR) or an emergency shipping record
-- (EMERGENCY). The row is created by the database in the same transaction that seals the batch or records the
-- emergency shipping, so no output is ever forgotten. The application writes the two files outside any
-- transaction and records the outcome:
--   PENDING -> WRITTEN                (both files are in the output folder; digests recorded)
--   PENDING -> PENDING                (claimed, or an attempt failed and is retried later)
--   PENDING -> FAILED                 (attempts exhausted)
--   FAILED  -> PENDING                (only by an administrator re-output, warehouse_file_output_retries)
-- WRITTEN is final: a written output is never written again (no re-fetch, decision 10).
-- File names, business date and the expected row counts are fixed by the trigger from the source rows.
-- Batches containing export lines written before V101 (old format, no snapshot) get no output row.
-- Lock order: output row, then the administrator (retries). Times are Asia/Tokyo wall-clock values read from the
-- database clock. No function sets timezone.

CREATE TABLE warehouse_file_outputs (
    id BIGSERIAL PRIMARY KEY,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('REGULAR','EMERGENCY')),
    batch_id BIGINT REFERENCES warehouse_export_batches(id) ON DELETE RESTRICT,
    emergency_shipping_preparation_id BIGINT REFERENCES emergency_shipping_preparations(id) ON DELETE RESTRICT,
    order_id BIGINT REFERENCES orders(id) ON DELETE RESTRICT,
    output_batch_id VARCHAR(20) NOT NULL CHECK (output_batch_id ~ '^E?[1-9][0-9]{0,17}$'),
    business_date DATE NOT NULL,
    header_filename VARCHAR(100) NOT NULL,
    detail_filename VARCHAR(100) NOT NULL,
    header_row_count INTEGER NOT NULL CHECK (header_row_count >= 0),
    detail_row_count INTEGER NOT NULL CHECK (detail_row_count >= 0),
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','WRITTEN','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP NOT NULL,
    claimed_by UUID,
    claimed_until TIMESTAMP,
    last_attempted_at TIMESTAMP,
    last_error VARCHAR(500),
    header_sha256 BYTEA CHECK (header_sha256 IS NULL OR octet_length(header_sha256) = 32),
    detail_sha256 BYTEA CHECK (detail_sha256 IS NULL OR octet_length(detail_sha256) = 32),
    written_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_warehouse_file_output_batch UNIQUE (batch_id),
    CONSTRAINT uq_warehouse_file_output_emergency UNIQUE (emergency_shipping_preparation_id),
    CHECK ((kind IS NOT DISTINCT FROM 'REGULAR' AND batch_id IS NOT NULL
                AND emergency_shipping_preparation_id IS NULL AND order_id IS NULL)
        OR (kind IS NOT DISTINCT FROM 'EMERGENCY' AND batch_id IS NULL
                AND emergency_shipping_preparation_id IS NOT NULL AND order_id IS NOT NULL)),
    CHECK ((state IS NOT DISTINCT FROM 'WRITTEN') IS NOT DISTINCT FROM (written_at IS NOT NULL)),
    CHECK ((written_at IS NULL) IS NOT DISTINCT FROM (header_sha256 IS NULL)),
    CHECK ((written_at IS NULL) IS NOT DISTINCT FROM (detail_sha256 IS NULL)),
    CHECK ((claimed_by IS NULL) IS NOT DISTINCT FROM (claimed_until IS NULL)),
    CHECK (state IS NOT DISTINCT FROM 'PENDING' OR claimed_by IS NULL),
    CHECK (state IS DISTINCT FROM 'FAILED' OR (attempts > 0 AND last_error IS NOT NULL))
);
CREATE INDEX idx_warehouse_file_outputs_due ON warehouse_file_outputs(next_attempt_at, id) WHERE state = 'PENDING';
CREATE INDEX idx_warehouse_file_outputs_order ON warehouse_file_outputs(order_id) WHERE order_id IS NOT NULL;

CREATE TABLE warehouse_file_output_retries (
    id BIGSERIAL PRIMARY KEY,
    output_id BIGINT NOT NULL REFERENCES warehouse_file_outputs(id) ON DELETE RESTRICT,
    admin_account_id BIGINT NOT NULL REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    admin_username VARCHAR(100) NOT NULL CHECK (admin_username ~ '[^[:space:]]'),
    previous_attempts INTEGER NOT NULL,
    previous_error VARCHAR(500),
    requested_at TIMESTAMP NOT NULL,
    retry_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_warehouse_file_output_retries_output ON warehouse_file_output_retries(output_id, id);

CREATE FUNCTION f137_file_output_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    db_now TIMESTAMP;
    b warehouse_export_batches%ROWTYPE;
    e emergency_shipping_preparations%ROWTYPE;
    legacy INTEGER;
    detail_rows INTEGER;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse file outputs cannot be removed';
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    IF TG_OP = 'INSERT' THEN
        IF NEW.kind IS NOT DISTINCT FROM 'REGULAR' THEN
            SELECT * INTO b FROM warehouse_export_batches WHERE id = NEW.batch_id FOR SHARE;
            IF b.id IS NULL OR b.state <> 'SEALED' OR NEW.emergency_shipping_preparation_id IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A warehouse file output needs a sealed batch';
            END IF;
            SELECT count(*) FILTER (WHERE file_lines IS NULL), coalesce(sum(jsonb_array_length(file_lines)), 0)
              INTO legacy, detail_rows FROM warehouse_export_lines WHERE batch_id = b.id;
            IF legacy <> 0 THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A batch with old-format export lines is not written as files';
            END IF;
            NEW.order_id := NULL;
            NEW.output_batch_id := b.id::TEXT;
            NEW.business_date := b.business_date;
            NEW.header_row_count := b.line_count;
            NEW.detail_row_count := detail_rows;
        ELSIF NEW.kind IS NOT DISTINCT FROM 'EMERGENCY' THEN
            SELECT * INTO e FROM emergency_shipping_preparations WHERE id = NEW.emergency_shipping_preparation_id;
            IF e.id IS NULL OR NEW.batch_id IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A warehouse file output needs an emergency shipping record';
            END IF;
            SELECT count(*) INTO detail_rows FROM f137_order_file_lines(e.order_id);
            NEW.order_id := e.order_id;
            NEW.output_batch_id := 'E' || e.id;
            NEW.business_date := e.prepared_at::DATE;
            NEW.header_row_count := 1;
            NEW.detail_row_count := detail_rows;
        ELSE
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unknown warehouse file output kind';
        END IF;
        NEW.header_filename := 'warehouse-header-' || to_char(NEW.business_date, 'YYYYMMDD') || '-' || NEW.output_batch_id || '.csv';
        NEW.detail_filename := 'warehouse-detail-' || to_char(NEW.business_date, 'YYYYMMDD') || '-' || NEW.output_batch_id || '.csv';
        NEW.state := 'PENDING'; NEW.attempts := 0; NEW.next_attempt_at := db_now;
        NEW.claimed_by := NULL; NEW.claimed_until := NULL; NEW.last_attempted_at := NULL; NEW.last_error := NULL;
        NEW.header_sha256 := NULL; NEW.detail_sha256 := NULL; NEW.written_at := NULL;
        NEW.created_at := db_now; NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE: what the output is never changes.
    IF ROW(NEW.id, NEW.kind, NEW.batch_id, NEW.emergency_shipping_preparation_id, NEW.order_id, NEW.output_batch_id,
           NEW.business_date, NEW.header_filename, NEW.detail_filename, NEW.header_row_count, NEW.detail_row_count,
           NEW.created_at, NEW.recorded_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.kind, OLD.batch_id, OLD.emergency_shipping_preparation_id, OLD.order_id,
           OLD.output_batch_id, OLD.business_date, OLD.header_filename, OLD.detail_filename, OLD.header_row_count,
           OLD.detail_row_count, OLD.created_at, OLD.recorded_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A warehouse file output cannot be redefined';
    END IF;
    IF OLD.state = 'PENDING' AND NEW.state IN ('PENDING','WRITTEN','FAILED') THEN
        IF NEW.attempts < OLD.attempts OR NEW.attempts > OLD.attempts + 1 THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A warehouse file output is attempted one at a time';
        END IF;
        IF NEW.attempts > OLD.attempts THEN
            NEW.last_attempted_at := db_now;
        ELSE
            NEW.last_attempted_at := OLD.last_attempted_at;
        END IF;
        IF NEW.state = 'WRITTEN' THEN
            IF NEW.header_sha256 IS NULL OR NEW.detail_sha256 IS NULL OR NEW.attempts = OLD.attempts THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A written warehouse file output records its attempt and digests';
            END IF;
            NEW.written_at := db_now; NEW.claimed_by := NULL; NEW.claimed_until := NULL;
        ELSE
            NEW.header_sha256 := NULL; NEW.detail_sha256 := NULL; NEW.written_at := NULL;
        END IF;
        IF NEW.state = 'FAILED' THEN
            IF NEW.attempts = OLD.attempts THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A warehouse file output fails only after an attempt';
            END IF;
            NEW.claimed_by := NULL; NEW.claimed_until := NULL;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state = 'FAILED' AND NEW.state = 'PENDING' THEN
        IF NOT EXISTS(SELECT 1 FROM warehouse_file_output_retries r
                      WHERE r.output_id = NEW.id AND r.retry_xid = pg_current_xact_id()) THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A failed warehouse file output is retried only by an administrator';
        END IF;
        NEW.attempts := 0; NEW.next_attempt_at := db_now; NEW.claimed_by := NULL; NEW.claimed_until := NULL;
        NEW.last_error := NULL; NEW.last_attempted_at := OLD.last_attempted_at;
        NEW.header_sha256 := NULL; NEW.detail_sha256 := NULL; NEW.written_at := NULL;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Invalid warehouse file output state transition';
END;
$$;
CREATE TRIGGER f137_file_output_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_file_outputs
    FOR EACH ROW EXECUTE FUNCTION f137_file_output_guard();
CREATE TRIGGER f137_file_output_truncate BEFORE TRUNCATE ON warehouse_file_outputs
    FOR EACH STATEMENT EXECUTE FUNCTION f137_file_output_guard();

-- Every batch sealed from now on gets its output, in the sealing transaction (unless it holds old-format lines).
CREATE FUNCTION f137_batch_sealed_output() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM warehouse_export_lines WHERE batch_id = NEW.id AND file_lines IS NULL) THEN
        INSERT INTO warehouse_file_outputs(kind, batch_id, output_batch_id, business_date, header_filename,
            detail_filename, header_row_count, detail_row_count, next_attempt_at, created_at)
        VALUES ('REGULAR', NEW.id, NEW.id::TEXT, NEW.business_date, '', '', 0, 0,
            timezone('Asia/Tokyo', clock_timestamp()), timezone('Asia/Tokyo', clock_timestamp()));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER f137_batch_sealed_output AFTER UPDATE ON warehouse_export_batches
    FOR EACH ROW WHEN (OLD.state = 'OPEN' AND NEW.state = 'SEALED')
    EXECUTE FUNCTION f137_batch_sealed_output();

-- Every emergency shipping recorded from now on gets its output, in the same transaction (decision 9).
CREATE FUNCTION f137_emergency_output() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO warehouse_file_outputs(kind, emergency_shipping_preparation_id, order_id, output_batch_id, business_date,
        header_filename, detail_filename, header_row_count, detail_row_count, next_attempt_at, created_at)
    VALUES ('EMERGENCY', NEW.id, NEW.order_id, 'E' || NEW.id, NEW.prepared_at::DATE, '', '', 0, 0,
        timezone('Asia/Tokyo', clock_timestamp()), timezone('Asia/Tokyo', clock_timestamp()));
    RETURN NULL;
END;
$$;
CREATE TRIGGER f137_emergency_output AFTER INSERT ON emergency_shipping_preparations
    FOR EACH ROW EXECUTE FUNCTION f137_emergency_output();

-- Administrator re-output of a FAILED output. Append-only; the row itself resets the output (after insert).
CREATE FUNCTION f137_file_output_retry_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o warehouse_file_outputs%ROWTYPE; a admin_accounts%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse file output retries are append-only';
    END IF;
    SELECT * INTO o FROM warehouse_file_outputs WHERE id = NEW.output_id FOR UPDATE;
    IF o.id IS NULL OR o.state <> 'FAILED' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Only a failed warehouse file output can be retried';
    END IF;
    SELECT * INTO a FROM admin_accounts WHERE id = NEW.admin_account_id FOR SHARE;
    IF a.id IS NULL OR NOT a.enabled OR a.username IS DISTINCT FROM NEW.admin_username THEN
        RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='The administrator cannot retry the warehouse file output';
    END IF;
    NEW.previous_attempts := o.attempts;
    NEW.previous_error := o.last_error;
    NEW.requested_at := timezone('Asia/Tokyo', clock_timestamp());
    NEW.retry_xid := pg_current_xact_id();
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f137_file_output_retry_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_file_output_retries
    FOR EACH ROW EXECUTE FUNCTION f137_file_output_retry_guard();
CREATE TRIGGER f137_file_output_retry_truncate BEFORE TRUNCATE ON warehouse_file_output_retries
    FOR EACH STATEMENT EXECUTE FUNCTION f137_file_output_retry_guard();

CREATE FUNCTION f137_file_output_retry_apply() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE warehouse_file_outputs SET state = 'PENDING' WHERE id = NEW.output_id;
    RETURN NULL;
END;
$$;
CREATE TRIGGER f137_file_output_retry_apply AFTER INSERT ON warehouse_file_output_retries
    FOR EACH ROW EXECUTE FUNCTION f137_file_output_retry_apply();
