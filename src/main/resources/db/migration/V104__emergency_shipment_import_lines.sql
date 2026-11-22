-- Feature138-B: rows of the shipped CSV with an emergency batch id 'E' || record id
-- (docs/feature138/emergency-shipment-import.md).
--
-- warehouse_shipment_import_lines gains a reference to the emergency shipping record (V97). A line that is not
-- rejected names exactly one source: a warehouse export batch (14:05, as before) or an emergency shipping record.
-- An emergency line may be PENDING / ALREADY_SHIPPED only when the record belongs to the line's order and the
-- warehouse received the order:
--   * its warehouse file output (V102) is WRITTEN, or
--   * it was recorded before V102 (no output row) and its CSV was downloaded at least once (V100).
-- The rest of f132_import_line_guard (V90) is unchanged; the new column is immutable like the other contents.
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

ALTER TABLE warehouse_shipment_import_lines
    ADD COLUMN emergency_shipping_preparation_id BIGINT REFERENCES emergency_shipping_preparations(id) ON DELETE RESTRICT;

ALTER TABLE warehouse_shipment_import_lines DROP CONSTRAINT warehouse_shipment_import_lines_check1;
ALTER TABLE warehouse_shipment_import_lines ADD CONSTRAINT chk_warehouse_shipment_import_line_content CHECK (
    state IS NOT DISTINCT FROM 'REJECTED'
    OR (num_nonnulls(batch_id, emergency_shipping_preparation_id) IS NOT DISTINCT FROM 1
        AND order_id IS NOT NULL AND csv_shipped_on IS NOT NULL AND carrier IS NOT NULL AND tracking_number IS NOT NULL)
);
ALTER TABLE warehouse_shipment_import_lines ADD CONSTRAINT chk_warehouse_shipment_import_line_source CHECK (
    num_nonnulls(batch_id, emergency_shipping_preparation_id) <= 1
);
CREATE INDEX idx_warehouse_shipment_import_lines_emergency ON warehouse_shipment_import_lines(emergency_shipping_preparation_id)
    WHERE emergency_shipping_preparation_id IS NOT NULL;

CREATE OR REPLACE FUNCTION f132_import_line_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    o orders%ROWTYPE;
    handed_over BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse shipment import lines cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state NOT IN ('PENDING','ALREADY_SHIPPED','REJECTED') OR NEW.attempts <> 0 THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A new import line is PENDING, ALREADY_SHIPPED or REJECTED';
        END IF;
        IF NEW.state <> 'REJECTED' AND NEW.emergency_shipping_preparation_id IS NULL AND NOT EXISTS(SELECT 1
                FROM warehouse_export_lines l JOIN warehouse_export_batches b ON b.id = l.batch_id
                WHERE l.batch_id = NEW.batch_id AND l.order_id = NEW.order_id AND b.state = 'SEALED') THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An import line must name an order of a sealed warehouse export batch';
        END IF;
        IF NEW.state <> 'REJECTED' AND NEW.emergency_shipping_preparation_id IS NOT NULL THEN
            handed_over := EXISTS(SELECT 1 FROM emergency_shipping_preparations r
                WHERE r.id = NEW.emergency_shipping_preparation_id AND r.order_id = NEW.order_id
                  AND (EXISTS(SELECT 1 FROM warehouse_file_outputs f
                              WHERE f.emergency_shipping_preparation_id = r.id AND f.state = 'WRITTEN')
                       OR (NOT EXISTS(SELECT 1 FROM warehouse_file_outputs f WHERE f.emergency_shipping_preparation_id = r.id)
                           AND EXISTS(SELECT 1 FROM emergency_shipping_csv_downloads d
                                      WHERE d.emergency_shipping_preparation_id = r.id))));
            IF NOT handed_over THEN
                RAISE EXCEPTION USING ERRCODE='23514',
                    MESSAGE='An emergency import line must name an emergency-shipped order handed to the warehouse';
            END IF;
        END IF;
        IF NEW.state = 'ALREADY_SHIPPED' AND NOT EXISTS(SELECT 1 FROM orders WHERE id = NEW.order_id AND status = 'SHIPPED') THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only a shipped order is recorded as already shipped';
        END IF;
        NEW.processed_at = CASE WHEN NEW.state = 'PENDING' THEN NULL ELSE timezone('Asia/Tokyo', clock_timestamp()) END;
        NEW.recorded_at = clock_timestamp();
        RETURN NEW;
    END IF;
    -- UPDATE: only the outcome columns change, along the documented transitions.
    IF ROW(NEW.id, NEW.import_id, NEW.line_no, NEW.line_text, NEW.batch_id, NEW.emergency_shipping_preparation_id,
            NEW.order_id, NEW.csv_shipped_on, NEW.carrier, NEW.tracking_number, NEW.recorded_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.import_id, OLD.line_no, OLD.line_text, OLD.batch_id, OLD.emergency_shipping_preparation_id,
            OLD.order_id, OLD.csv_shipped_on, OLD.carrier, OLD.tracking_number, OLD.recorded_at) THEN
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
