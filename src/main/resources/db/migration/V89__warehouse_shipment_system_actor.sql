-- Feature132-A: the warehouse shipment import may ship an exported order as the system.
--
-- A system shipment is recorded like the automatic warehouse-export preparation (V86): no account id and a
-- fixed name. Every part of one system shipment carries the same identity:
--   orders.shipped_by_account_id = NULL, orders.shipped_by_username = 'WAREHOUSE_SHIPMENT_IMPORT'
--   the SHIPMENT_CAPTURE transaction initiator = SYSTEM / NULL / 'WAREHOUSE_SHIPMENT_IMPORT' (card orders)
--   the SHIPPED status history actor            = SYSTEM / NULL / 'WAREHOUSE_SHIPMENT_IMPORT'
-- Only an order prepared by the warehouse export and present in warehouse_export_lines can ship this way,
-- and a recorded system shipment actor never changes. Administrator shipments are unchanged.
-- No function sets timezone.

ALTER TABLE orders DROP CONSTRAINT chk_orders_shipment_metadata;
ALTER TABLE orders ADD CONSTRAINT chk_orders_shipment_metadata CHECK (
    (shipped_by_account_id IS NULL
        AND shipped_by_username IS NULL
        AND shipment_capture_payment_id IS NULL)
    OR
    (status IS NOT NULL AND status = 'SHIPPED'
        AND shipped_at IS NOT NULL
        AND shipping_preparation_started_at IS NOT NULL
        AND ((shipped_by_account_id IS NOT NULL
                AND shipped_by_username IS NOT NULL
                AND shipped_by_username ~ '[^[:space:]]')
            OR (shipped_by_account_id IS NULL
                AND shipped_by_username IS NOT DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT'
                AND shipping_preparation_started_by_account_id IS NULL
                AND shipping_preparation_started_by_username IS NOT DISTINCT FROM 'WAREHOUSE_EXPORT')))
);

ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transactions_system_shipment_capture CHECK (
    purpose IS DISTINCT FROM 'SHIPMENT_CAPTURE'
    OR initiator_type IS DISTINCT FROM 'SYSTEM'
    OR (initiator_id IS NULL AND initiator_username IS NOT DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT')
);

ALTER TABLE order_status_histories ADD CONSTRAINT chk_order_status_histories_system_shipment CHECK (
    to_status IS DISTINCT FROM 'SHIPPED'
    OR changed_by_type IS DISTINCT FROM 'SYSTEM'
    OR (changed_by_account_id IS NULL AND changed_by_username IS NOT DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT')
);

CREATE FUNCTION f132_system_shipment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    new_system BOOLEAN := NEW.shipped_by_account_id IS NULL
        AND NEW.shipped_by_username IS NOT DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT';
    capture_count INTEGER;
    system_capture_count INTEGER;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF new_system THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An order cannot be created as a system shipment';
        END IF;
        RETURN NEW;
    END IF;
    -- A system shipment actor is never added to, or removed from, an already recorded shipment.
    IF OLD.shipped_by_username IS NOT NULL
            AND (new_system OR (OLD.shipped_by_account_id IS NULL
                AND OLD.shipped_by_username IS NOT DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT'))
            AND ROW(NEW.shipped_by_account_id, NEW.shipped_by_username)
                IS DISTINCT FROM ROW(OLD.shipped_by_account_id, OLD.shipped_by_username) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A recorded shipment actor cannot change';
    END IF;
    IF NOT new_system OR OLD.shipped_by_username IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.status NOT IN ('ORDERED', 'PAID') OR NEW.status IS DISTINCT FROM 'SHIPPED'
            OR NOT EXISTS(SELECT 1 FROM warehouse_export_lines l WHERE l.order_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only an exported order can be shipped by the warehouse shipment import';
    END IF;
    IF NEW.shipment_capture_payment_id IS NOT NULL THEN
        SELECT count(*), count(*) FILTER (WHERE t.initiator_type = 'SYSTEM' AND t.initiator_id IS NULL
                    AND t.initiator_username IS NOT DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT')
            INTO capture_count, system_capture_count
            FROM payment_transactions t
            WHERE t.payment_id = NEW.shipment_capture_payment_id AND t.transaction_type = 'CAPTURE';
        IF capture_count <> 1 OR system_capture_count <> 1 THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A system shipment needs exactly one system shipment capture';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f132_system_shipment_guard BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION f132_system_shipment_guard();
