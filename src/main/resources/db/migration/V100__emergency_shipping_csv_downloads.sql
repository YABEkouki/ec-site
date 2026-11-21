-- Feature136-C2: the warehouse CSV of one emergency-shipped order, and the audit of every download.
--
-- Emergency-shipped orders never reach the 14:05 warehouse export (V86/V88), so an administrator downloads a
-- per-order CSV in the same format as the warehouse export. The rows come from the order, which is frozen
-- once shipping preparation has started (V98), so every download of an order is the same rendering; the digest
-- of the delivered bytes is stored to prove it.
--   * one row per download, append-only (55000);
--   * the order must still be prepared by its emergency record, with the recorded content revision;
--   * the administrator must be enabled and the name must match (42501); every administrator may download;
--   * the business date is the date of the emergency operation (prepared_at), the row count is the number of
--     order items, and the download time and the file name are fixed by the database clock.
-- Lock order: Order, then the emergency record, then the administrator. Times are Asia/Tokyo wall-clock values
-- read from the database clock. No function sets timezone.

CREATE TABLE emergency_shipping_csv_downloads (
    id BIGSERIAL PRIMARY KEY,
    emergency_shipping_preparation_id BIGINT NOT NULL REFERENCES emergency_shipping_preparations(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    admin_account_id BIGINT NOT NULL REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    admin_username VARCHAR(100) NOT NULL CHECK (admin_username ~ '[^[:space:]]'),
    business_date DATE NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count > 0),
    content_sha256 BYTEA NOT NULL CHECK (octet_length(content_sha256) = 32),
    filename VARCHAR(100) NOT NULL,
    downloaded_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_emergency_shipping_csv_downloads_order ON emergency_shipping_csv_downloads(order_id, id);

CREATE FUNCTION f136_csv_download_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    o orders%ROWTYPE;
    e emergency_shipping_preparations%ROWTYPE;
    a admin_accounts%ROWTYPE;
    item_count INTEGER;
    db_now TIMESTAMP WITHOUT TIME ZONE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Emergency shipping CSV downloads are append-only';
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    SELECT * INTO o FROM orders WHERE id = NEW.order_id FOR SHARE;
    SELECT * INTO e FROM emergency_shipping_preparations WHERE id = NEW.emergency_shipping_preparation_id;
    IF o.id IS NULL OR e.id IS NULL OR e.order_id IS DISTINCT FROM o.id
        OR o.shipping_preparation_started_at IS DISTINCT FROM e.prepared_at
        OR o.shipping_preparation_started_by_account_id IS DISTINCT FROM e.admin_account_id
        OR o.shipping_preparation_started_by_username IS DISTINCT FROM e.admin_username
        OR o.content_revision IS DISTINCT FROM e.content_revision THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only an order prepared by its emergency shipping record can be downloaded';
    END IF;
    SELECT * INTO a FROM admin_accounts WHERE id = NEW.admin_account_id FOR SHARE;
    IF a.id IS NULL OR NOT a.enabled OR a.username IS DISTINCT FROM NEW.admin_username THEN
        RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='The administrator cannot download the emergency shipping CSV';
    END IF;
    SELECT count(*) INTO item_count FROM order_items WHERE order_id = o.id;
    IF NEW.row_count IS DISTINCT FROM item_count OR NEW.business_date IS DISTINCT FROM e.prepared_at::date THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The emergency shipping CSV does not match the order';
    END IF;
    NEW.downloaded_at := db_now;
    NEW.filename := 'emergency-shipping-' || o.id || '-' || to_char(db_now, 'YYYYMMDD-HH24MISS') || '.csv';
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER f136_csv_download_guard BEFORE INSERT OR UPDATE OR DELETE ON emergency_shipping_csv_downloads
    FOR EACH ROW EXECUTE FUNCTION f136_csv_download_guard();
CREATE TRIGGER f136_csv_download_truncate BEFORE TRUNCATE ON emergency_shipping_csv_downloads
    FOR EACH STATEMENT EXECUTE FUNCTION f136_csv_download_guard();
