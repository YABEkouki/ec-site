-- Feature135-C2: the cancellation notification says when an order was cancelled because its card authorization
-- expired (Feature135-B1, V94).
--
-- The ORDER_CANCELLED payload (V91) gains authorizationExpired: true when the cancellation carries a V94 audit row
-- (payment_expired_cancellation_observations). The trigger is deferred, so at commit the audit row written in the
-- same transaction is visible. Notifications recorded before this migration keep their payload (no key, read as
-- false). Everything else is the V91 function unchanged. No function sets timezone.

CREATE OR REPLACE FUNCTION f133_on_status_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE;
BEGIN
    SELECT * INTO STRICT o FROM orders WHERE id = NEW.order_id;
    IF NEW.to_status = 'CANCELLED' THEN
        PERFORM f133_record('ORDER_CANCELLED', NEW.order_id, NEW.id::text, jsonb_build_object(
            'changedAt', NEW.changed_at, 'changedByType', NEW.changed_by_type,
            'fromStatus', NEW.from_status, 'accepted', o.accepted_at IS NOT NULL,
            'authorizationExpired', EXISTS(SELECT 1 FROM payment_expired_cancellation_observations x
                                           JOIN payments p ON p.id = x.payment_id
                                           WHERE p.order_id = NEW.order_id)));
    ELSIF NEW.to_status = 'SHIPPED' THEN
        PERFORM f133_record('ORDER_SHIPPED', NEW.order_id, NEW.id::text, jsonb_build_object(
            'changedAt', NEW.changed_at, 'changedByType', NEW.changed_by_type, 'shippedAt', o.shipped_at,
            'warehouseImport', NEW.changed_by_type = 'SYSTEM' AND NEW.changed_by_account_id IS NULL
                AND NEW.changed_by_username = 'WAREHOUSE_SHIPMENT_IMPORT'));
    END IF;
    RETURN NULL;
END;
$$;
