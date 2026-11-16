-- Feature131 C-3: why an order was not exported (design doc ch.8: hold instead of exporting what is unproven).
--
-- One row per batch and order, written by the 14:05 export when it holds or fails to export a candidate.
-- A held order is judged again by the next day's batch, which writes its own row; the history therefore shows
-- on how many business dates an order has been held. Rows are append-only and are written only while the batch
-- is OPEN. Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

CREATE TABLE warehouse_export_holds (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES warehouse_export_batches(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    reason VARCHAR(40) NOT NULL CHECK (reason IN ('MANUALLY_PREPARED','NOT_EXPORTABLE_STATUS','ORDER_CHANGE_UNRESOLVED',
        'PAYMENT_NOT_READY','NOT_DUE','DATABASE_GUARD','FAILED')),
    recorded_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_warehouse_export_hold UNIQUE (batch_id, order_id)
);
CREATE INDEX idx_warehouse_export_holds_order ON warehouse_export_holds(order_id, batch_id);

CREATE FUNCTION f131_hold_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b warehouse_export_batches%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse export holds are append-only';
    END IF;
    SELECT * INTO b FROM warehouse_export_batches WHERE id = NEW.batch_id FOR SHARE;
    IF b.id IS NULL OR b.state <> 'OPEN' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A warehouse export hold is recorded only while its batch is open';
    END IF;
    NEW.recorded_at = timezone('Asia/Tokyo', clock_timestamp());
    RETURN NEW;
END;
$$;
CREATE TRIGGER f131_hold_guard BEFORE INSERT OR UPDATE OR DELETE ON warehouse_export_holds
    FOR EACH ROW EXECUTE FUNCTION f131_hold_guard();
CREATE TRIGGER f131_hold_truncate BEFORE TRUNCATE ON warehouse_export_holds
    FOR EACH STATEMENT EXECUTE FUNCTION f131_hold_guard();
