-- Feature146-A: stop the shipment of a prepared but unshipped order whose card authorization has expired
-- (docs/feature146/shipping-stop.md).
--
-- A prepared order is frozen (V98) and, after the change deadline, may only be cancelled by the expired-authorization
-- cancellation (V94). The shipping stop reuses that cancellation and its V94 observation; this migration adds the
-- administrator's record and lets V98 accept CANCELLED for a prepared order only in the transaction that records it.
-- The warehouse is contacted by the administrator (warehouse_contacted must be true); files are never withdrawn.

CREATE TABLE order_shipping_stops (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    transaction_id BIGINT NOT NULL UNIQUE REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    authorization_kind VARCHAR(20) NOT NULL CHECK (authorization_kind IN ('EXPIRED')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED')),
    admin_account_id BIGINT NOT NULL REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    admin_username VARCHAR(100) NOT NULL CHECK (btrim(admin_username) <> ''),
    reason VARCHAR(500) NOT NULL CHECK (btrim(reason) <> ''),
    warehouse_contacted BOOLEAN NOT NULL CHECK (warehouse_contacted),
    -- Set by the trigger from the locked order (callers' values are ignored).
    preparation_kind VARCHAR(20) NOT NULL CHECK (preparation_kind IN ('WAREHOUSE_EXPORT','EMERGENCY','ADMINISTRATOR')),
    prepared_at TIMESTAMP NOT NULL,
    file_state VARCHAR(20) NOT NULL CHECK (file_state IN ('NONE','PENDING','WRITTEN','FAILED')),
    recording_xid xid8 NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_order_shipping_stops_order ON order_shipping_stops(order_id);

CREATE FUNCTION f146_stop_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE; p payments%ROWTYPE; t payment_transactions%ROWTYPE; x payment_authorization_expiries%ROWTYPE;
 allowed BOOLEAN; batch BIGINT; emergency BIGINT; fs VARCHAR(20);
BEGIN
 IF TG_OP <> 'INSERT' THEN
  RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A shipping stop record is append only';
 END IF;
 SELECT * INTO o FROM orders WHERE id = NEW.order_id FOR UPDATE;
 IF NOT FOUND OR o.shipping_preparation_started_at IS NULL OR o.status <> 'ORDERED' OR o.shipped_at IS NOT NULL THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Only a prepared and unshipped order can be stopped';
 END IF;
 SELECT emergency_shipping_allowed INTO allowed FROM admin_accounts WHERE id = NEW.admin_account_id AND enabled;
 IF allowed IS NOT TRUE THEN
  RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Only an administrator allowed to ship urgently can stop a shipment';
 END IF;
 SELECT * INTO p FROM payments WHERE id = NEW.payment_id;
 SELECT * INTO t FROM payment_transactions WHERE id = NEW.transaction_id;
 SELECT * INTO x FROM payment_authorization_expiries WHERE payment_id = NEW.payment_id;
 IF p.id IS NULL OR t.id IS NULL OR x.payment_id IS NULL
  OR p.order_id IS DISTINCT FROM o.id OR (o.active_payment_id IS NOT NULL AND o.active_payment_id <> p.id)
  OR p.status <> 'AUTHORIZED' OR p.provider <> 'PAYJP' OR p.payment_method <> 'CARD'
  OR t.payment_id IS DISTINCT FROM p.id OR t.transaction_type <> 'CANCEL' OR t.status <> 'PENDING'
  OR t.purpose IS DISTINCT FROM 'ORDER_CANCELLATION'
  OR x.expires_at > timezone('Asia/Tokyo', clock_timestamp()) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A shipping stop needs the pending cancellation of the expired active authorization';
 END IF;
 IF EXISTS(SELECT 1 FROM payment_transactions c WHERE c.payment_id = p.id AND c.transaction_type = 'CAPTURE' AND c.status = 'PENDING')
  OR EXISTS(SELECT 1 FROM warehouse_shipment_import_lines l WHERE l.order_id = o.id AND l.state IN ('PENDING','CAPTURE_UNCONFIRMED'))
  OR EXISTS(SELECT 1 FROM order_change_operations op WHERE op.order_id = o.id AND op.state NOT IN ('COMPLETED','ABORTED'))
  OR f131_order_has_blocking_admission(o.id) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A shipment being captured or an unfinished order change blocks a shipping stop';
 END IF;
 SELECT max(id) INTO emergency FROM emergency_shipping_preparations WHERE order_id = o.id;
 SELECT max(batch_id) INTO batch FROM warehouse_export_lines WHERE order_id = o.id;
 IF emergency IS NOT NULL THEN
  NEW.preparation_kind := 'EMERGENCY';
  SELECT state INTO fs FROM warehouse_file_outputs WHERE emergency_shipping_preparation_id = emergency ORDER BY id DESC LIMIT 1;
 ELSIF batch IS NOT NULL THEN
  NEW.preparation_kind := 'WAREHOUSE_EXPORT';
  SELECT state INTO fs FROM warehouse_file_outputs WHERE batch_id = batch ORDER BY id DESC LIMIT 1;
 ELSE
  NEW.preparation_kind := 'ADMINISTRATOR';
 END IF;
 NEW.file_state := coalesce(fs, 'NONE');
 NEW.prepared_at := o.shipping_preparation_started_at;
 NEW.recording_xid := pg_current_xact_id();
 NEW.created_at := clock_timestamp();
 RETURN NEW;
END;
$$;
CREATE TRIGGER f146_stop_insert BEFORE INSERT OR UPDATE OR DELETE ON order_shipping_stops
    FOR EACH ROW EXECUTE FUNCTION f146_stop_insert();

CREATE FUNCTION f146_stop_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A shipping stop record is append only';
END;
$$;
CREATE TRIGGER f146_stop_truncate BEFORE TRUNCATE ON order_shipping_stops
    FOR EACH STATEMENT EXECUTE FUNCTION f146_stop_truncate();

-- At commit the stop must have cancelled everything it stands for: the order, the payment, the cancellation
-- transaction and its V94 expired-cancellation observation.
CREATE FUNCTION f146_stop_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(
   SELECT 1 FROM order_shipping_stops s
   JOIN orders o ON o.id = s.order_id AND o.status = 'CANCELLED'
   JOIN payments p ON p.id = s.payment_id AND p.status = 'CANCELLED'
   JOIN payment_transactions t ON t.id = s.transaction_id AND t.status = 'SUCCEEDED'
   JOIN payment_expired_cancellation_observations c ON c.transaction_id = s.transaction_id AND c.payment_id = s.payment_id
   WHERE s.id = NEW.id) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A shipping stop must cancel the order and its expired authorization';
 END IF;
 RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f146_stop_commit AFTER INSERT ON order_shipping_stops
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f146_stop_commit();

-- V98 with one change: a prepared order may become CANCELLED in the transaction that records its shipping stop.
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
        OR (NEW.status = 'CANCELLED' AND OLD.status <> 'CANCELLED'
            AND NOT EXISTS(SELECT 1 FROM order_shipping_stops s
                WHERE s.order_id = OLD.id AND s.recording_xid = pg_current_xact_id())) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order is frozen once shipping preparation has started';
    END IF;
    RETURN NEW;
END;
$$;

DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND proname IN ('f146_stop_insert','f146_stop_truncate','f146_stop_commit') LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END $$;
