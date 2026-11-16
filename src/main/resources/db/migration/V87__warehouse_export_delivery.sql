-- Feature131 C-2: warehouse export delivery (administrator CSV download) and shipment/export consistency.
--
-- warehouse_export_downloads is the append-only audit of every CSV handed out. Only a SEALED batch can be
-- downloaded, so every download of a batch is the same rendering of the same immutable lines; the digest
-- of the delivered bytes is stored to prove it.
--
-- An exported order may be shipped only against what the warehouse received: the Capture payment (or no
-- payment for a PAID order) and the content revision/amount must equal its export line.
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

CREATE TABLE warehouse_export_downloads (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES warehouse_export_batches(id) ON DELETE RESTRICT,
    admin_account_id BIGINT NOT NULL CHECK (admin_account_id > 0),
    admin_username VARCHAR(100) NOT NULL CHECK (admin_username ~ '[^[:space:]]'),
    downloaded_at TIMESTAMP NOT NULL,
    line_count INTEGER NOT NULL CHECK (line_count >= 0),
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    content_sha256 BYTEA NOT NULL CHECK (octet_length(content_sha256) = 32),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_warehouse_export_downloads_batch ON warehouse_export_downloads(batch_id, id);

CREATE FUNCTION f131_download_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b warehouse_export_batches%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse export downloads are append-only';
    END IF;
    SELECT * INTO b FROM warehouse_export_batches WHERE id = NEW.batch_id FOR SHARE;
    IF b.id IS NULL OR b.state <> 'SEALED' OR NEW.line_count IS DISTINCT FROM b.line_count THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only a sealed warehouse export batch can be downloaded';
    END IF;
    NEW.downloaded_at = timezone('Asia/Tokyo', clock_timestamp()); NEW.recorded_at = clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f131_download_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_export_downloads
    FOR EACH ROW EXECUTE FUNCTION f131_download_guard();
CREATE TRIGGER f131_download_truncate BEFORE TRUNCATE ON warehouse_export_downloads
    FOR EACH STATEMENT EXECUTE FUNCTION f131_download_guard();

CREATE FUNCTION f131_exported_shipment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM warehouse_export_lines l WHERE l.order_id = NEW.id
            AND l.payment_id IS NOT DISTINCT FROM NEW.shipment_capture_payment_id
            AND l.content_revision = NEW.content_revision AND l.total_amount = NEW.total_amount) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An exported order must ship with its exported payment and content';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f131_exported_shipment_guard BEFORE UPDATE ON orders
    FOR EACH ROW
    WHEN (NEW.status = 'SHIPPED' AND OLD.status IS DISTINCT FROM 'SHIPPED'
          AND NEW.shipping_preparation_started_at IS NOT NULL AND NEW.shipping_preparation_started_by_account_id IS NULL)
    EXECUTE FUNCTION f131_exported_shipment_guard();
