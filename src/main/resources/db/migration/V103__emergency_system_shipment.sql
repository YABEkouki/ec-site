-- Feature138-A: the warehouse shipment import may also ship an emergency-shipped order as the system
-- (docs/feature138/emergency-shipment-import.md).
--
-- Since Feature137 an emergency-shipped order (V97, administrator preparation with its record) is handed to the
-- warehouse as files with batch id 'E' || record id, and its shipped row comes back in the shipped CSV.
--   * chk_orders_shipment_metadata: a system shipment (account id NULL, 'WAREHOUSE_SHIPMENT_IMPORT') is allowed
--     for an order prepared by the warehouse export (as before) or by an administrator.
--   * f132_system_shipment_guard: an exported order still needs its warehouse export line; an administrator-
--     prepared order needs the emergency shipping record of exactly that preparation. A legacy administrator
--     preparation (before V97, no record) cannot be shipped by the system.
--   * f138_emergency_shipment_guard (new): every shipment of an emergency-shipped order, by an administrator or
--     by the system, ships with the content and payment the record describes (as V87 does for exported orders):
--     the record's content revision and total amount; for a record taken while ORDERED a capture payment, equal
--     to the record's active payment when that was set (V86 rule); for a record taken while PAID no capture.
--     Legacy administrator preparations have no record and are not checked.
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
                AND ((shipping_preparation_started_by_account_id IS NULL
                        AND shipping_preparation_started_by_username IS NOT DISTINCT FROM 'WAREHOUSE_EXPORT')
                    OR (shipping_preparation_started_by_account_id IS NOT NULL
                        AND shipping_preparation_started_by_username IS NOT NULL
                        AND shipping_preparation_started_by_username ~ '[^[:space:]]')))))
);

CREATE OR REPLACE FUNCTION f132_system_shipment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    new_system BOOLEAN := NEW.shipped_by_account_id IS NULL
        AND NEW.shipped_by_username IS NOT DISTINCT FROM 'WAREHOUSE_SHIPMENT_IMPORT';
    handed_over BOOLEAN;
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
    IF NEW.shipping_preparation_started_by_account_id IS NULL THEN
        handed_over := EXISTS(SELECT 1 FROM warehouse_export_lines l WHERE l.order_id = NEW.id);
    ELSE
        handed_over := EXISTS(SELECT 1 FROM emergency_shipping_preparations r WHERE r.order_id = NEW.id
            AND r.admin_account_id = NEW.shipping_preparation_started_by_account_id
            AND r.admin_username IS NOT DISTINCT FROM NEW.shipping_preparation_started_by_username
            AND r.prepared_at IS NOT DISTINCT FROM NEW.shipping_preparation_started_at);
    END IF;
    IF OLD.status NOT IN ('ORDERED', 'PAID') OR NEW.status IS DISTINCT FROM 'SHIPPED' OR NOT handed_over THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='Only an exported or emergency-shipped order can be shipped by the warehouse shipment import';
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

CREATE FUNCTION f138_emergency_shipment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r emergency_shipping_preparations%ROWTYPE;
    payment_matches BOOLEAN;
BEGIN
    SELECT * INTO r FROM emergency_shipping_preparations WHERE order_id = NEW.id;
    IF r.id IS NULL THEN
        RETURN NEW;
    END IF;
    IF r.order_status = 'ORDERED' THEN
        payment_matches := NEW.shipment_capture_payment_id IS NOT NULL
            AND (r.active_payment_id IS NULL OR r.active_payment_id = NEW.shipment_capture_payment_id);
    ELSE
        payment_matches := NEW.shipment_capture_payment_id IS NULL;
    END IF;
    IF NEW.content_revision IS DISTINCT FROM r.content_revision OR NEW.total_amount IS DISTINCT FROM r.total_amount
            OR NOT payment_matches THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='An emergency-shipped order must ship with its recorded payment and content';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f138_emergency_shipment_guard BEFORE UPDATE ON orders
    FOR EACH ROW
    WHEN (NEW.status = 'SHIPPED' AND OLD.status IS DISTINCT FROM 'SHIPPED'
          AND NEW.shipping_preparation_started_at IS NOT NULL AND NEW.shipping_preparation_started_by_account_id IS NOT NULL)
    EXECUTE FUNCTION f138_emergency_shipment_guard();
