-- Feature136-B: freeze every prepared order (design doc ch.4.2 / ch.5).
--
-- V86 froze only orders prepared by the warehouse export (account id NULL). From V98 the same rule applies to
-- every order whose shipping preparation has started: warehouse export, emergency shipping (V97) and legacy
-- administrator preparations from before V97. Once preparation has started:
--   * the preparation itself, the customer, amounts, content revision, shipping address and active payment
--     cannot change, and the order cannot become CANCELLED (orders);
--   * order items and charges cannot be inserted, updated or deleted.
-- The application enforces the same rule in Order.requireContentMutable (OrderItem / OrderCharge included).
-- Existing rows are not rewritten; status changes to PAID / SHIPPED and shipment metadata stay allowed.

CREATE OR REPLACE FUNCTION f131_order_preparation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.shipping_preparation_started_at IS NOT NULL AND NEW.shipping_preparation_started_by_account_id IS NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Automatic shipping preparation requires a warehouse export';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.shipping_preparation_started_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF ROW(NEW.shipping_preparation_started_at, NEW.shipping_preparation_started_by_account_id, NEW.shipping_preparation_started_by_username)
           IS DISTINCT FROM ROW(OLD.shipping_preparation_started_at, OLD.shipping_preparation_started_by_account_id, OLD.shipping_preparation_started_by_username)
        OR ROW(NEW.user_id, NEW.total_amount, NEW.item_subtotal, NEW.charge_total, NEW.tax_amount, NEW.content_revision,
               NEW.shipping_name, NEW.shipping_postal_code, NEW.shipping_prefecture, NEW.shipping_city,
               NEW.shipping_address_line, NEW.shipping_phone, NEW.active_payment_id)
           IS DISTINCT FROM ROW(OLD.user_id, OLD.total_amount, OLD.item_subtotal, OLD.charge_total, OLD.tax_amount, OLD.content_revision,
               OLD.shipping_name, OLD.shipping_postal_code, OLD.shipping_prefecture, OLD.shipping_city,
               OLD.shipping_address_line, OLD.shipping_phone, OLD.active_payment_id)
        OR (NEW.status = 'CANCELLED' AND OLD.status <> 'CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order is frozen once shipping preparation has started';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION f131_prepared_content_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP <> 'INSERT' AND EXISTS(SELECT 1 FROM orders WHERE id = OLD.order_id
            AND shipping_preparation_started_at IS NOT NULL))
        OR (TG_OP <> 'DELETE' AND EXISTS(SELECT 1 FROM orders WHERE id = NEW.order_id
            AND shipping_preparation_started_at IS NOT NULL)) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Order content is frozen once shipping preparation has started';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
