-- Feature146-B: stop the shipment of a prepared, unshipped order whose card authorization is still valid
-- (docs/feature146/shipping-stop.md chapter 12).
--
-- A VALID stop is recorded REQUESTED together with the pending ORDER_CANCELLATION; the cancel POST is sent outside
-- any transaction. The stop becomes COMPLETED in the transaction that applies the canceled result (whichever route
-- applies it), or REJECTED when the administrator withdraws it after PAY.JP shows the authorization still capturable;
-- the cancellation transaction then becomes FAILED and the order stays prepared.

ALTER TABLE order_shipping_stops DROP CONSTRAINT order_shipping_stops_authorization_kind_check;
ALTER TABLE order_shipping_stops ADD CONSTRAINT order_shipping_stops_authorization_kind_check
    CHECK (authorization_kind IN ('EXPIRED','VALID'));
ALTER TABLE order_shipping_stops DROP CONSTRAINT order_shipping_stops_status_check;
ALTER TABLE order_shipping_stops ADD CONSTRAINT order_shipping_stops_status_check
    CHECK (status IN ('REQUESTED','COMPLETED','REJECTED'));
ALTER TABLE order_shipping_stops
    ADD COLUMN completed_xid xid8,
    ADD COLUMN resolved_at TIMESTAMPTZ,
    ADD COLUMN withdrawn_by_account_id BIGINT REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    ADD COLUMN withdrawn_by_username VARCHAR(100),
    ADD COLUMN withdrawal_reason VARCHAR(500);
ALTER TABLE order_shipping_stops ADD CONSTRAINT order_shipping_stops_resolution_check CHECK (
    (status = 'REQUESTED' AND completed_xid IS NULL AND resolved_at IS NULL AND withdrawn_by_account_id IS NULL
        AND withdrawn_by_username IS NULL AND withdrawal_reason IS NULL)
    OR (status = 'COMPLETED' AND withdrawn_by_account_id IS NULL AND withdrawn_by_username IS NULL AND withdrawal_reason IS NULL)
    OR (status = 'REJECTED' AND authorization_kind = 'VALID' AND completed_xid IS NULL AND resolved_at IS NOT NULL
        AND withdrawn_by_account_id IS NOT NULL AND btrim(coalesce(withdrawn_by_username, '')) <> ''
        AND btrim(coalesce(withdrawal_reason, '')) <> ''));
-- A withdrawn (REJECTED) stop does not keep the order from being stopped again.
DROP INDEX uq_order_shipping_stops_order;
CREATE UNIQUE INDEX uq_order_shipping_stops_order ON order_shipping_stops(order_id) WHERE status <> 'REJECTED';

-- V114 with the VALID kind and the REQUESTED -> COMPLETED / REJECTED transitions.
CREATE OR REPLACE FUNCTION f146_stop_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE; p payments%ROWTYPE; t payment_transactions%ROWTYPE; x payment_authorization_expiries%ROWTYPE;
 allowed BOOLEAN; batch BIGINT; emergency BIGINT; fs VARCHAR(20);
BEGIN
 IF TG_OP = 'DELETE' THEN
  RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A shipping stop record is append only';
 END IF;
 IF TG_OP = 'UPDATE' THEN
  IF OLD.status <> 'REQUESTED' OR OLD.authorization_kind <> 'VALID' OR NEW.status NOT IN ('COMPLETED','REJECTED')
   OR ROW(NEW.id, NEW.order_id, NEW.payment_id, NEW.transaction_id, NEW.authorization_kind, NEW.admin_account_id,
          NEW.admin_username, NEW.reason, NEW.warehouse_contacted, NEW.preparation_kind, NEW.prepared_at, NEW.file_state,
          NEW.recording_xid, NEW.created_at)
      IS DISTINCT FROM ROW(OLD.id, OLD.order_id, OLD.payment_id, OLD.transaction_id, OLD.authorization_kind, OLD.admin_account_id,
          OLD.admin_username, OLD.reason, OLD.warehouse_contacted, OLD.preparation_kind, OLD.prepared_at, OLD.file_state,
          OLD.recording_xid, OLD.created_at) THEN
   RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A shipping stop record is append only';
  END IF;
  PERFORM 1 FROM orders WHERE id = OLD.order_id FOR UPDATE;
  IF NEW.status = 'COMPLETED' THEN
   NEW.withdrawn_by_account_id := NULL; NEW.withdrawn_by_username := NULL; NEW.withdrawal_reason := NULL;
   NEW.completed_xid := pg_current_xact_id();
  ELSE
   SELECT emergency_shipping_allowed INTO allowed FROM admin_accounts WHERE id = NEW.withdrawn_by_account_id AND enabled;
   IF allowed IS NOT TRUE THEN
    RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Only an administrator allowed to ship urgently can withdraw a shipping stop';
   END IF;
   NEW.completed_xid := NULL;
  END IF;
  NEW.resolved_at := clock_timestamp();
  RETURN NEW;
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
  OR t.purpose IS DISTINCT FROM 'ORDER_CANCELLATION' THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A shipping stop needs the pending cancellation of the active authorization';
 END IF;
 IF NEW.authorization_kind = 'EXPIRED' THEN
  IF NEW.status <> 'COMPLETED' OR x.expires_at > timezone('Asia/Tokyo', clock_timestamp()) THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An expired shipping stop needs an expired authorization';
  END IF;
 ELSIF NEW.status <> 'REQUESTED' OR x.expires_at <= timezone('Asia/Tokyo', clock_timestamp()) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A valid shipping stop starts REQUESTED for a valid authorization';
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
 NEW.completed_xid := NULL; NEW.resolved_at := NULL;
 NEW.withdrawn_by_account_id := NULL; NEW.withdrawn_by_username := NULL; NEW.withdrawal_reason := NULL;
 IF NEW.status = 'COMPLETED' THEN
  NEW.completed_xid := NEW.recording_xid; NEW.resolved_at := NEW.created_at;
 END IF;
 RETURN NEW;
END;
$$;

-- At commit each stop must match its state: COMPLETED cancelled everything it stands for (an EXPIRED one with its
-- V94 observation), REQUESTED keeps the order prepared with the cancellation pending, REJECTED keeps the order
-- prepared and the payment authorized with the cancellation failed.
CREATE OR REPLACE FUNCTION f146_stop_commit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s order_shipping_stops%ROWTYPE; os VARCHAR(20); ps VARCHAR(20); ts VARCHAR(20); prepared BOOLEAN;
BEGIN
 SELECT * INTO s FROM order_shipping_stops WHERE id = NEW.id;
 SELECT o.status, o.shipping_preparation_started_at IS NOT NULL AND o.shipped_at IS NULL INTO os, prepared FROM orders o WHERE o.id = s.order_id;
 SELECT status INTO ps FROM payments WHERE id = s.payment_id;
 SELECT status INTO ts FROM payment_transactions WHERE id = s.transaction_id;
 IF s.status = 'COMPLETED' THEN
  IF os IS DISTINCT FROM 'CANCELLED' OR ps IS DISTINCT FROM 'CANCELLED' OR ts IS DISTINCT FROM 'SUCCEEDED'
   OR (s.authorization_kind = 'EXPIRED' AND NOT EXISTS(SELECT 1 FROM payment_expired_cancellation_observations c
        WHERE c.transaction_id = s.transaction_id AND c.payment_id = s.payment_id)) THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A shipping stop must cancel the order and its authorization';
  END IF;
 ELSIF os IS DISTINCT FROM 'ORDERED' OR prepared IS NOT TRUE OR ps IS DISTINCT FROM 'AUTHORIZED'
  OR (s.status = 'REQUESTED' AND ts IS DISTINCT FROM 'PENDING') OR (s.status = 'REJECTED' AND ts IS DISTINCT FROM 'FAILED') THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A requested or withdrawn shipping stop must keep the order prepared';
 END IF;
 RETURN NULL;
END;
$$;
DROP TRIGGER f146_stop_commit ON order_shipping_stops;
CREATE CONSTRAINT TRIGGER f146_stop_commit AFTER INSERT OR UPDATE ON order_shipping_stops
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f146_stop_commit();

-- The cancellation of a REQUESTED stop settles only together with the stop: at commit, a stop whose transaction
-- is no longer PENDING must not be REQUESTED (the result route completes it, the withdrawal rejects it).
CREATE FUNCTION f146_stop_transaction_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM order_shipping_stops s JOIN payment_transactions t ON t.id = s.transaction_id
           WHERE s.transaction_id = NEW.id AND s.status = 'REQUESTED' AND t.status <> 'PENDING') THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The cancellation of a requested shipping stop must settle the stop';
 END IF;
 RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f146_stop_transaction_commit AFTER UPDATE OF status ON payment_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.transaction_type = 'CANCEL' AND NEW.status IS DISTINCT FROM OLD.status)
    EXECUTE FUNCTION f146_stop_transaction_commit();

-- V114 with one change: the transaction that completes a REQUESTED stop may also cancel the prepared order.
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
                WHERE s.order_id = OLD.id AND s.status = 'COMPLETED' AND s.completed_xid = pg_current_xact_id())) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order is frozen once shipping preparation has started';
    END IF;
    RETURN NEW;
END;
$$;

-- V94 with one change: an ORDER_CANCELLATION after the change deadline may also be the cancellation of a VALID
-- shipping stop recorded in the same transaction.
CREATE OR REPLACE FUNCTION f135_late_cancellation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE deadline TIMESTAMP;
BEGIN
    IF NEW.transaction_type <> 'CANCEL' OR NEW.purpose IS DISTINCT FROM 'ORDER_CANCELLATION' THEN
        RETURN NULL;
    END IF;
    SELECT o.change_deadline_at INTO deadline FROM payments p JOIN orders o ON o.id = p.order_id WHERE p.id = NEW.payment_id;
    IF deadline IS NOT NULL AND NEW.created_at >= deadline
        AND NOT EXISTS(SELECT 1 FROM payment_expired_cancellation_observations c WHERE c.transaction_id = NEW.id)
        AND NOT EXISTS(SELECT 1 FROM order_shipping_stops s WHERE s.transaction_id = NEW.id AND s.authorization_kind = 'VALID') THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='An order cancellation after the change deadline must be an expired-authorization cancellation';
    END IF;
    RETURN NULL;
END;
$$;

DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND proname IN ('f146_stop_insert','f146_stop_commit','f146_stop_transaction_commit') LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END $$;
