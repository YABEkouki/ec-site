-- Feature137-A: the lines of the warehouse files (docs/feature137/warehouse-file-output.md, ch.2-3).
--
-- The warehouse receives two files: a header file (one row per order: total, tax, total without tax) and a detail
-- file (one row per order item, then one row per charge such as the shipping fee when its amount is positive).
-- f137_order_file_lines(order) is the only definition of the detail rows. It is used by the export line snapshot
-- below and, for emergency shipping, read directly from the order (frozen once preparation starts, V86/V98).
--
-- Tax of a detail row: the order tax is, per tax rate, floor(gross * rate / (100 + rate)) (TaxCalculator).
-- Each row gets floor(subtotal * rate / (100 + rate)); the remainder of its rate (always smaller than the number
-- of rows of that rate) is added to the row of that rate with the largest subtotal (then the smallest line
-- number), so the row taxes always add up to the tax of their rate and to orders.tax_amount.
--
-- warehouse_export_lines gains the order tax and the detail rows. They are taken here from the locked order by
-- the database, never from callers, and must add up to the order total and tax. Lines written before V101 keep
-- NULL in both columns (old format; never written as warehouse files).
-- No function reads the clock; no function sets timezone.

CREATE FUNCTION f137_order_file_lines(target BIGINT)
RETURNS TABLE(line_no INTEGER, order_item_id BIGINT, order_charge_id BIGINT, product_id BIGINT, product_name VARCHAR,
              tax_rate NUMERIC, unit_price INTEGER, quantity INTEGER, subtotal INTEGER, tax_amount INTEGER)
LANGUAGE sql STABLE AS $$
    WITH source AS (
        SELECT 0 AS kind, 0 AS sort_key, i.id AS source_id, i.id AS item_id, NULL::BIGINT AS charge_id,
               i.product_id AS product, i.product_name::VARCHAR AS name, i.tax_rate AS rate, i.price AS unit,
               i.quantity AS qty, i.subtotal AS amount
        FROM order_items i WHERE i.order_id = target
        UNION ALL
        SELECT 1, c.display_order, c.id, NULL::BIGINT, c.id, NULL::BIGINT, c.name::VARCHAR, c.tax_rate, c.amount, 1, c.amount
        FROM order_charges c WHERE c.order_id = target AND c.amount > 0
    ), numbered AS (
        SELECT row_number() OVER (ORDER BY s.kind, s.sort_key, s.source_id)::INTEGER AS no, s.*,
               floor(s.amount * s.rate / (100 + s.rate)) AS base
        FROM source s
    ), ranked AS (
        SELECT n.*,
               floor(sum(n.amount) OVER per_rate * n.rate / (100 + n.rate)) - sum(n.base) OVER per_rate AS remainder,
               row_number() OVER (PARTITION BY n.rate ORDER BY n.amount DESC, n.no) AS rank_in_rate
        FROM numbered n
        WINDOW per_rate AS (PARTITION BY n.rate)
    )
    SELECT r.no, r.item_id, r.charge_id, r.product, r.name, r.rate, r.unit, r.qty, r.amount,
           (r.base + CASE WHEN r.rank_in_rate = 1 THEN r.remainder ELSE 0 END)::INTEGER
    FROM ranked r
    ORDER BY r.no
$$;

ALTER TABLE warehouse_export_lines ADD COLUMN tax_amount INTEGER, ADD COLUMN file_lines JSONB;
ALTER TABLE warehouse_export_lines ADD CONSTRAINT chk_warehouse_export_lines_file_snapshot CHECK (
    (tax_amount IS NULL AND file_lines IS NULL)
    OR (tax_amount IS NOT NULL AND tax_amount >= 0 AND file_lines IS NOT NULL
        AND jsonb_typeof(file_lines) IS NOT DISTINCT FROM 'array' AND jsonb_array_length(file_lines) > 0));

-- Runs after f131_line_guard and f135_line_expiry_guard (same event, name order). Lock order is unchanged: the
-- order row is already locked FOR UPDATE by f131_line_guard and by the application.
CREATE FUNCTION f137_line_file_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE; row_count INTEGER; incomplete INTEGER; line_total BIGINT; line_tax BIGINT; snapshot JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Warehouse export lines are append-only';
    END IF;
    SELECT * INTO o FROM orders WHERE id = NEW.order_id FOR UPDATE;
    SELECT count(*),
           count(*) FILTER (WHERE f.subtotal IS NULL OR f.tax_amount IS NULL OR f.unit_price IS NULL
                                OR f.quantity IS NULL OR f.tax_rate IS NULL OR f.product_name IS NULL),
           sum(f.subtotal), sum(f.tax_amount),
           jsonb_agg(jsonb_build_object('lineNo', f.line_no, 'orderItemId', f.order_item_id,
               'orderChargeId', f.order_charge_id, 'productId', f.product_id, 'productName', f.product_name,
               'taxRate', f.tax_rate, 'unitPrice', f.unit_price, 'quantity', f.quantity, 'subtotal', f.subtotal,
               'taxAmount', f.tax_amount) ORDER BY f.line_no)
      INTO row_count, incomplete, line_total, line_tax, snapshot
      FROM f137_order_file_lines(NEW.order_id) f;
    IF o.id IS NULL OR row_count = 0 OR incomplete <> 0
        OR line_total IS DISTINCT FROM o.total_amount::BIGINT OR line_tax IS DISTINCT FROM o.tax_amount::BIGINT
        OR NEW.total_amount IS DISTINCT FROM o.total_amount THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The warehouse file lines do not match the order amounts';
    END IF;
    NEW.tax_amount = o.tax_amount;
    NEW.file_lines = snapshot;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f137_line_file_snapshot BEFORE INSERT ON warehouse_export_lines
    FOR EACH ROW EXECUTE FUNCTION f137_line_file_snapshot();

-- Warehouse export holds: new reason (the application checks the same sums before preparing the order).
ALTER TABLE warehouse_export_holds DROP CONSTRAINT warehouse_export_holds_reason_check;
ALTER TABLE warehouse_export_holds ADD CONSTRAINT warehouse_export_holds_reason_check CHECK (reason IN (
    'MANUALLY_PREPARED','NOT_EXPORTABLE_STATUS','ORDER_CHANGE_UNRESOLVED','PAYMENT_NOT_READY',
    'AUTHORIZATION_EXPIRING','AMOUNT_MISMATCH','NOT_DUE','DATABASE_GUARD','FAILED'));
