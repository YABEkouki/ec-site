-- Feature144: closing a not-adopted owned order change whose new authorization expired at PAY.JP
-- (docs/feature144/expired-change-closure.md).
--
-- PAY.JP v2 turns an expired uncaptured Payment Flow into canceled (cancellation_reason=expired). A change whose new
-- authorization succeeded but was never adopted (past automatic_until, after which the database refuses every claim,
-- so it can never be switched) and then expired has no exit: Feature129-B
-- cancels a valid authorization only, and the observation of the expired flow ends in MANUAL_REVIEW. Nothing is sent
-- to PAY.JP here. After the administrator read the flow as canceled by expiry, one transaction records the cancel of
-- the new payment as an observed fact, aborts the change, returns its stock and closes its admission.
-- Same model as V85/V111: an append-only, DB-verified proof row is a transaction-bound capability. The replaced
-- functions are their latest definitions with only the NEW_AUTH_EXPIRED alternative added. Lock order: runtime, order,
-- operations, payments, ... (f125_reauth_lock). No function sets timezone. No card data or client secret is kept.

-- Append-only proof; only ever written by f144_close_expired.
CREATE TABLE order_change_expired_closures (
    admission_id UUID PRIMARY KEY REFERENCES order_edit_admissions(id),
    order_id BIGINT NOT NULL REFERENCES orders(id),
    operation_id UUID NOT NULL UNIQUE REFERENCES order_change_operations(id),
    new_payment_id BIGINT NOT NULL UNIQUE REFERENCES payments(id),
    requested_by_account_id BIGINT NOT NULL,
    requested_by_username VARCHAR(100) NOT NULL CHECK (length(requested_by_username) > 0),
    internal_note VARCHAR(500),
    observation JSONB NOT NULL,
    document JSONB NOT NULL,
    canonical BYTEA NOT NULL,
    digest BYTEA NOT NULL CHECK (digest = sha256(canonical)),
    recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_order_change_expired_closures_order ON order_change_expired_closures(order_id);
REVOKE ALL ON order_change_expired_closures FROM PUBLIC;

CREATE FUNCTION f144_expired_proof(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_change_expired_closures n
 WHERE n.admission_id=target AND n.recording_xid=pg_current_xact_id()
 AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical));
$$;
CREATE FUNCTION f144_expired_attempt_resolved(attempt UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_execution_attempts a
 JOIN order_change_expired_closures n ON n.admission_id=a.admission_id
 WHERE a.id=attempt AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical)
 AND n.document->'resolvedAttempts' ? attempt::text);
$$;
-- The cancel of the new payment recorded from the expiry instead of a sent request.
CREATE FUNCTION f144_expired_cancel(operation UUID, cancel BIGINT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_change_expired_closures n JOIN payment_transactions t ON t.payment_id=n.new_payment_id
 WHERE n.operation_id=operation AND t.id=cancel AND t.order_change_operation_id=operation
 AND t.purpose='ORDER_CHANGE_NEW_AUTH_ABORT' AND t.transaction_type='CANCEL' AND t.status='SUCCEEDED'
 AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical));
$$;

-- The single eligibility rule, shared by the application and the guards below.
CREATE FUNCTION f144_change_closable(target UUID) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM order_change_operations op
 JOIN orders o ON o.id=op.order_id
 JOIN order_edit_admissions r ON r.order_id=op.order_id AND r.submission_key=op.submission_key
 JOIN order_edit_execution_controls c ON c.admission_id=r.id
 JOIN payments p ON p.id=op.new_payment_id AND p.order_id=op.order_id
 JOIN payments oldp ON oldp.id=op.old_payment_id AND oldp.order_id=op.order_id
 JOIN payment_authorization_expiries x ON x.payment_id=p.id
 JOIN payment_transactions t ON t.payment_id=p.id AND t.order_change_operation_id=op.id
 JOIN order_change_financial_requests q ON q.operation_id=op.id AND q.transaction_id=t.id AND q.request_kind='CREATE'
 JOIN order_change_request_dispatches d ON d.request_id=q.id AND d.state='OBSERVED'
 WHERE op.id=target AND o.status='ORDERED' AND o.shipping_preparation_started_at IS NULL AND o.active_payment_id=oldp.id
 AND op.execution_version=1 AND op.execution_route='REAUTHORIZE' AND f125_reauth_is_owned(op.order_id,op.id)
 AND (op.state='AUTHORIZED' OR (op.state='MANUAL_REVIEW'
  AND op.financial_review_reason IN ('NEW_FLOW_EVIDENCE_MISMATCH','ESTABLISHED_AUTH_CANCELED_EXTERNALLY')))
 AND op.switch_decided_at IS NULL AND op.application_xid IS NULL AND op.content_change_history_id IS NULL AND op.abort_request_id IS NULL
 AND r.route='REAUTHORIZE' AND r.admission_state='ACCEPTED' AND r.execution_state IN ('QUEUED','RECOVERY_PENDING','REVIEW_REQUIRED')
 AND r.closed_at IS NULL AND r.applied_operation_id IS NULL
 AND c.active_attempt_id IS NULL AND c.lease_until IS NULL AND c.automatic_until<=clock_timestamp()
 AND f125_runtime_available()
 AND EXISTS(SELECT 1 FROM order_edit_runtime_control rc WHERE rc.id=1 AND rc.generation_id=r.db_generation_id)
 AND p.provider='PAYJP' AND p.payment_method='CARD' AND p.status='AUTHORIZED' AND p.amount=r.after_amount
 AND p.provider_payment_id ~ '^pfw_[A-Za-z0-9_-]{1,251}$'
 AND x.expires_at<=timezone('Asia/Tokyo',clock_timestamp())
 AND oldp.id=r.base_payment_id AND oldp.status='AUTHORIZED' AND oldp.amount=r.authorized_amount
 AND t.purpose='ORDER_CHANGE_AUTHORIZATION' AND t.transaction_type='AUTHORIZE' AND t.status='SUCCEEDED' AND t.amount=r.after_amount
 AND t.provider_transaction_id=p.provider_payment_id
 AND (SELECT count(*) FROM payment_transactions y WHERE y.payment_id=p.id OR y.order_change_operation_id=op.id)=1
 AND NOT EXISTS(SELECT 1 FROM payment_transactions y JOIN payments yp ON yp.id=y.payment_id WHERE yp.order_id=op.order_id AND y.status='PENDING')
 AND (SELECT count(*) FROM order_change_financial_requests y WHERE y.operation_id=op.id)=1
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests y LEFT JOIN order_change_request_dispatches z ON z.request_id=y.id
   WHERE y.order_id=op.order_id AND z.state IS DISTINCT FROM 'OBSERVED')
 AND NOT EXISTS(SELECT 1 FROM order_edit_failure_closure_controls y WHERE y.admission_id=r.id)
 AND NOT EXISTS(SELECT 1 FROM order_change_expired_closures y WHERE y.operation_id=op.id OR y.admission_id=r.id)
 AND NOT EXISTS(SELECT 1 FROM order_change_flow_closures y WHERE y.operation_id=op.id AND y.status IN ('REQUESTED','SENDING'))
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=r.id
   AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results y WHERE y.attempt_id=a.id))
 AND EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0)
 AND NOT EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0
   AND (ip.reservation_state IS DISTINCT FROM 'HELD' OR ip.operation_id IS DISTINCT FROM op.id))
 AND f143_reauth_current(r.id));
$$;

CREATE FUNCTION f144_expired_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; p payments%ROWTYPE;
 q order_change_financial_requests%ROWTYPE; resolved JSONB; doc JSONB; o JSONB;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Expired change closure proof is append only'; END IF;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=NEW.operation_id;
 PERFORM f125_reauth_lock(op.order_id,op.id);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE order_id=op.order_id AND submission_key=op.submission_key;
 PERFORM f125_closure_lock_related(r.id);
 PERFORM f125_execution_check_runtime(r.id);
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=NEW.operation_id;
 SELECT * INTO STRICT p FROM payments WHERE id=op.new_payment_id;
 SELECT * INTO STRICT q FROM order_change_financial_requests WHERE operation_id=op.id AND request_kind='CREATE';
 o=NEW.observation;
 IF NOT f144_change_closable(op.id)
 OR ROW(NEW.admission_id,NEW.order_id,NEW.new_payment_id) IS DISTINCT FROM ROW(r.id,op.order_id,op.new_payment_id)
 OR NEW.recording_xid<>pg_current_xact_id() OR NEW.recorded_at>clock_timestamp() THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Expired change closure requires a not-adopted change whose new authorization expired'; END IF;
 -- The Payment Flow was read as canceled by its expiry with nothing received, for exactly this order and change.
 IF jsonb_typeof(o) IS DISTINCT FROM 'object'
 OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(o) k) IS DISTINCT FROM
  ARRAY['amount','amountCapturable','amountReceived','canceledAt','cancellationReason','captureMethod','currency','expiredAt','flow','livemode','operation','order','status']
 OR o->>'flow' IS DISTINCT FROM p.provider_payment_id OR o->>'status' IS DISTINCT FROM 'canceled'
 OR o->>'cancellationReason' IS DISTINCT FROM 'expired'
 OR o->'amount' IS DISTINCT FROM to_jsonb(r.after_amount) OR o->>'currency' IS DISTINCT FROM 'jpy' OR o->>'captureMethod' IS DISTINCT FROM 'manual'
 OR o->'livemode' IS DISTINCT FROM to_jsonb(q.mode='LIVE')
 OR o->>'order' IS DISTINCT FROM op.order_id::text OR o->>'operation' IS DISTINCT FROM op.id::text
 OR coalesce(o->>'amountReceived','0')<>'0' OR coalesce(o->>'amountCapturable','0')<>'0'
 OR jsonb_typeof(o->'expiredAt') IS DISTINCT FROM 'string' OR jsonb_typeof(o->'canceledAt') IS DISTINCT FROM 'string'
 OR (o->>'expiredAt')::timestamptz>(o->>'canceledAt')::timestamptz OR (o->>'canceledAt')::timestamptz>clock_timestamp() THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Expired change closure requires the expired Payment Flow of this change'; END IF;
 SELECT coalesce(jsonb_agg(a.id::text ORDER BY a.id::text),'[]'::jsonb) INTO resolved FROM order_edit_execution_attempts a
 WHERE a.admission_id=r.id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('REVIEW','UNKNOWN'));
 doc=jsonb_build_object('domain','f144/expired/closure/v1','admission',r.id,'order',r.order_id,'operation',op.id,
  'operationState',op.state,'operationVersion',op.version,'newPayment',op.new_payment_id,'oldPayment',r.base_payment_id,
  'requestedBy',NEW.requested_by_account_id,'observation',o,'resolvedAttempts',resolved,'xid',NEW.recording_xid::text,
  'recorded',to_char(NEW.recorded_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
 NEW.document=doc;NEW.canonical=f125_closure_encode(doc);NEW.digest=sha256(NEW.canonical);
 RETURN NEW;
END;
$$;
CREATE TRIGGER expired_insert BEFORE INSERT OR UPDATE OR DELETE ON order_change_expired_closures
    FOR EACH ROW EXECUTE FUNCTION f144_expired_insert();
CREATE TRIGGER expired_truncate BEFORE TRUNCATE ON order_change_expired_closures EXECUTE FUNCTION f125_failure_no_truncate();

CREATE FUNCTION f144_expired_closed(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_change_expired_closures n
 JOIN order_edit_admissions r ON r.id=n.admission_id
 JOIN order_edit_unapplied_evidences e ON e.admission_id=n.admission_id
 JOIN order_change_operations op ON op.id=n.operation_id
 JOIN payments p ON p.id=n.new_payment_id
 JOIN payment_transactions auth ON auth.payment_id=p.id AND auth.purpose='ORDER_CHANGE_AUTHORIZATION'
 JOIN payment_transactions t ON t.payment_id=p.id AND t.purpose='ORDER_CHANGE_NEW_AUTH_ABORT'
 WHERE n.admission_id=target AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical)
 AND n.document->>'admission'=n.admission_id::text AND n.document->>'operation'=n.operation_id::text
 AND n.document->>'xid'=n.recording_xid::text
 AND e.recording_xid=n.recording_xid AND e.closure_request_id=n.operation_id
 AND e.terminal_state='CLOSED_UNAPPLIED' AND e.reason_code='NEW_AUTH_EXPIRED'
 AND e.canonical=f125_closure_canonical(e.evidence_document::text) AND e.evidence_digest=sha256(e.canonical)
 AND r.execution_state='CLOSED_UNAPPLIED' AND r.reason_code='NEW_AUTH_EXPIRED' AND r.closed_at=e.recorded_at
 AND r.applied_operation_id IS NULL AND op.state='ABORTED' AND op.switch_decided_at IS NULL AND op.new_payment_id=p.id
 AND p.status='CANCELLED' AND auth.status='SUCCEEDED' AND auth.order_change_operation_id=op.id
 AND t.status='SUCCEEDED' AND t.transaction_type='CANCEL' AND t.order_change_operation_id=op.id AND t.order_change_order_id=op.order_id
 AND t.amount=p.amount AND t.provider_transaction_id=p.provider_payment_id AND t.completed_at IS NOT NULL
 AND t.order_content_revision=r.base_content_revision
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests y WHERE y.operation_id=op.id AND y.request_kind<>'CREATE')
 AND (SELECT count(*) FROM payment_transactions y WHERE y.payment_id=p.id OR y.order_change_operation_id=op.id)=2
 AND NOT EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0
   AND (ip.reservation_state IS DISTINCT FROM 'RELEASED' OR ip.release_movement_id IS NULL)));
$$;
CREATE FUNCTION f144_commit_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'payment_transactions' THEN
        -- A cancel of a new authorization is a sent request, or the expiry recorded by this Feature.
        IF NOT EXISTS (SELECT 1 FROM order_change_financial_requests q WHERE q.transaction_id = NEW.id AND q.operation_id = NEW.order_change_operation_id)
            AND NOT f144_expired_cancel(NEW.order_change_operation_id, NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A new authorization cancel needs its request or its expiry proof';
        END IF;
        RETURN NULL;
    END IF;
    IF NOT f144_expired_closed(NEW.admission_id) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Incomplete atomic expired change closure';
    END IF;
    PERFORM f125_closure_assert_final(NEW.admission_id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER expired_final AFTER INSERT ON order_change_expired_closures
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f144_commit_check();
CREATE CONSTRAINT TRIGGER f144_abort_cancel_commit AFTER INSERT ON payment_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.purpose = 'ORDER_CHANGE_NEW_AUTH_ABORT') EXECUTE FUNCTION f144_commit_check();

-- Afterwards the cancelled new payment, its transactions and the aborted change never change again.
CREATE FUNCTION f144_closed_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE closed BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'payments' THEN
        closed := OLD.status = 'CANCELLED' AND EXISTS (SELECT 1 FROM order_change_expired_closures WHERE new_payment_id = OLD.id);
    ELSIF TG_TABLE_NAME = 'payment_transactions' THEN
        closed := OLD.status = 'SUCCEEDED' AND EXISTS (SELECT 1 FROM order_change_expired_closures WHERE new_payment_id = OLD.payment_id);
    ELSE
        closed := OLD.state = 'ABORTED' AND EXISTS (SELECT 1 FROM order_change_expired_closures WHERE operation_id = OLD.id);
    END IF;
    IF closed THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An order change closed after its new authorization expired cannot change';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f144_closed_payment BEFORE UPDATE OF status ON payments
    FOR EACH ROW WHEN (NEW.status IS DISTINCT FROM OLD.status) EXECUTE FUNCTION f144_closed_guard();
CREATE TRIGGER f144_closed_transaction BEFORE UPDATE OF status ON payment_transactions
    FOR EACH ROW WHEN (NEW.status IS DISTINCT FROM OLD.status) EXECUTE FUNCTION f144_closed_guard();
CREATE TRIGGER f144_closed_operation BEFORE UPDATE OF state ON order_change_operations
    FOR EACH ROW WHEN (NEW.state IS DISTINCT FROM OLD.state) EXECUTE FUNCTION f144_closed_guard();

-- The only writer of the proof. Replays return false. Nothing here contacts the provider.
CREATE FUNCTION f144_close_expired(target UUID, observed JSONB, account BIGINT, username TEXT, note TEXT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE op order_change_operations%ROWTYPE; r order_edit_admissions%ROWTYPE; p payments%ROWTYPE;
 ec order_edit_execution_controls%ROWTYPE; x order_edit_inventory_plans%ROWTYPE;
 product products%ROWTYPE; movement BIGINT; db_now TIMESTAMP;
BEGIN
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=target;
 PERFORM f125_reauth_lock(op.order_id,op.id);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE order_id=op.order_id AND submission_key=op.submission_key;
 PERFORM f125_closure_lock_related(r.id);
 IF EXISTS(SELECT 1 FROM order_change_expired_closures WHERE operation_id=target) THEN RETURN false; END IF;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=target;
 SELECT * INTO STRICT p FROM payments WHERE id=op.new_payment_id;
 SELECT * INTO STRICT ec FROM order_edit_execution_controls WHERE admission_id=r.id;
 PERFORM pr.id FROM products pr JOIN order_edit_inventory_plans ip ON ip.product_id=pr.id
  WHERE ip.admission_id=r.id AND ip.reserve_quantity>0 ORDER BY pr.id FOR UPDATE OF pr;
 INSERT INTO order_change_expired_closures(admission_id,order_id,operation_id,new_payment_id,requested_by_account_id,
  requested_by_username,internal_note,observation,document,canonical,digest)
 VALUES(r.id,op.order_id,op.id,p.id,account,username,note,observed,'{}'::jsonb,''::bytea,sha256(''::bytea));
 db_now=timezone('Asia/Tokyo',clock_timestamp());
 INSERT INTO payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,
  provider_transaction_id,created_at,completed_at,initiator_type,initiator_id,initiator_username,internal_note,
  purpose,order_change_operation_id,order_change_order_id)
 VALUES(p.id,'CANCEL','SUCCEEDED',r.after_amount,r.base_content_revision,'f144-'||gen_random_uuid()::text,
  p.provider_payment_id,db_now,db_now,'ADMIN',account,username,note,'ORDER_CHANGE_NEW_AUTH_ABORT',op.id,op.order_id);
 UPDATE payments SET status='CANCELLED',updated_at=db_now WHERE id=p.id AND status='AUTHORIZED';
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Payment changed before the expired change closure'; END IF;
 UPDATE order_change_operations SET state='ABORTED',financial_review_reason=NULL,version=version+1,updated_at=db_now WHERE id=op.id;
 FOR x IN SELECT * FROM order_edit_inventory_plans WHERE admission_id=r.id AND reserve_quantity>0 ORDER BY product_id FOR UPDATE LOOP
  SELECT * INTO STRICT product FROM products WHERE id=x.product_id FOR UPDATE;
  UPDATE products SET stock=stock+x.reserve_quantity WHERE id=x.product_id;
  INSERT INTO stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,changed_by_type,changed_by_username,reason,order_id)
  VALUES(x.product_id,product.name,'ORDER_CHANGE_RELEASE',x.reserve_quantity,product.stock,product.stock+x.reserve_quantity,
   'SYSTEM','SYSTEM','order_change='||op.id::text,op.order_id) RETURNING id INTO movement;
  UPDATE order_edit_inventory_plans SET reservation_state='RELEASED',release_movement_id=movement,released_at=db_now
  WHERE admission_id=x.admission_id AND product_id=x.product_id;
 END LOOP;
 INSERT INTO order_edit_unapplied_evidences(admission_id,order_id,db_generation_id,invalidated_token,control_version,terminal_state,reason_code,closure_request_id)
 VALUES(r.id,ec.order_id,ec.db_generation_id,ec.fencing_token+1,ec.version+1,'CLOSED_UNAPPLIED','NEW_AUTH_EXPIRED',op.id);
 UPDATE order_edit_execution_controls SET fencing_token=fencing_token+1,version=version+1,updated_at=clock_timestamp() WHERE admission_id=r.id;
 UPDATE order_edit_admissions SET execution_state='CLOSED_UNAPPLIED',reason_code='NEW_AUTH_EXPIRED',version=version+1 WHERE id=r.id;
 RETURN true;
END;
$$;

-- V112 with only the expired cancel accepted in place of a sent request.
CREATE OR REPLACE FUNCTION f125_reauth_assert_current(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; c order_edit_execution_controls%ROWTYPE;
 baseline JSONB; actual JSONB; k TEXT; kept JSONB; old_payment JSONB;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 PERFORM f125_execution_check_runtime(target);PERFORM f125_financial_assert_saved(target);
 baseline=r.unapplied_baseline;actual=f143_legacy_order_local(baseline,f125_execution_local(r.order_id));
 SELECT * INTO c FROM order_edit_execution_controls WHERE admission_id=target;
 IF baseline IS NULL OR (c.admission_id IS NOT NULL AND c.baseline IS DISTINCT FROM baseline) OR r.route<>'REAUTHORIZE' THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Reauthorization immutable baseline unavailable'; END IF;
 SELECT * INTO op FROM order_change_operations WHERE order_id=r.order_id AND submission_key=r.submission_key;
 IF NOT FOUND THEN
 IF actual IS DISTINCT FROM baseline THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Reauthorization original basis changed'; END IF;
 RETURN; END IF;
 IF NOT f125_reauth_is_owned(r.order_id,op.id) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unowned reauthorization operation'; END IF;
 -- New payment and transactions must be the exact requested financial legs, never generic exclusions.
 IF op.new_payment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM payments p WHERE p.id=op.new_payment_id
 AND p.order_id=r.order_id AND p.provider='PAYJP' AND p.payment_method='CARD' AND p.amount=r.after_amount) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unproven new payment'; END IF;
 IF EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=op.id AND
 (t.order_change_order_id IS DISTINCT FROM r.order_id OR NOT (
 (t.purpose='ORDER_CHANGE_AUTHORIZATION' AND t.transaction_type='AUTHORIZE' AND t.payment_id=op.new_payment_id AND t.amount=r.after_amount AND t.order_content_revision=r.base_content_revision+1)
 OR (t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL' AND t.transaction_type='CANCEL' AND t.payment_id=r.base_payment_id AND t.amount=r.authorized_amount AND t.order_content_revision=r.base_content_revision)
 OR (t.purpose='ORDER_CHANGE_NEW_AUTH_ABORT' AND t.transaction_type='CANCEL' AND t.payment_id=op.new_payment_id AND t.amount=r.after_amount AND t.order_content_revision=r.base_content_revision))
 OR (NOT EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.operation_id=op.id AND q.transaction_id=t.id
 AND q.payment_id=t.payment_id AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type AND q.amount=t.amount)
 AND NOT f144_expired_cancel(op.id,t.id)))) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unproven owned transaction'; END IF;
 IF EXISTS(SELECT 1 FROM order_change_financial_requests q LEFT JOIN payment_transactions t ON t.id=q.transaction_id
 WHERE q.operation_id=op.id AND (q.order_id<>r.order_id OR t.order_change_operation_id IS DISTINCT FROM op.id
 OR q.payment_id<>t.payment_id OR q.purpose<>t.purpose OR q.transaction_type<>t.transaction_type OR q.amount<>t.amount)) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unproven owned request'; END IF;
 SELECT x INTO STRICT old_payment FROM jsonb_array_elements(baseline->'payments') x WHERE (x->>'id')::bigint=r.base_payment_id;
 IF NOT EXISTS(SELECT 1 FROM payments p WHERE p.id=r.base_payment_id AND
 (to_jsonb(p)-ARRAY['status','updated_at'])=(old_payment-ARRAY['status','updated_at']) AND
 (p.status='AUTHORIZED' OR (p.status='CANCELLED' AND EXISTS(SELECT 1 FROM payment_transactions t
 WHERE t.payment_id=p.id AND t.order_change_operation_id=op.id AND t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL'
 AND t.transaction_type='CANCEL' AND t.status='SUCCEEDED' AND t.completed_at IS NOT NULL
 AND t.amount=r.authorized_amount AND t.provider_transaction_id=p.provider_payment_id)))) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Old payment changed without owned successful cancellation'; END IF;
 IF EXISTS(SELECT 1 FROM order_edit_inventory_plans p LEFT JOIN stock_movements m ON m.id=p.hold_movement_id
 WHERE p.admission_id=target AND p.reservation_state IS NOT NULL AND
 (p.operation_id IS DISTINCT FROM op.id OR p.reserve_quantity<=0 OR m.id IS NULL
 OR ROW(m.product_id,m.order_id,m.movement_type,m.quantity,m.reason,m.stock_after)
 IS DISTINCT FROM ROW(p.product_id,r.order_id,'ORDER_CHANGE_RESERVATION'::varchar,-p.reserve_quantity,'order_change='||op.id::text,m.stock_before-p.reserve_quantity)
 OR (p.reservation_state='RELEASED' AND NOT EXISTS(SELECT 1 FROM stock_movements z WHERE z.id=p.release_movement_id
 AND z.product_id=p.product_id AND z.order_id=r.order_id AND z.movement_type='ORDER_CHANGE_RELEASE'
 AND z.quantity=p.reserve_quantity AND z.reason='order_change='||op.id::text AND z.stock_after=z.stock_before+p.reserve_quantity)))) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Owned reservation movement proof changed'; END IF;
 SELECT coalesce(jsonb_agg(CASE WHEN (x->>'id')::bigint=r.base_payment_id THEN old_payment ELSE x END ORDER BY (x->>'id')::bigint),'[]') INTO kept
 FROM jsonb_array_elements(actual->'payments') x WHERE op.new_payment_id IS NULL OR (x->>'id')::bigint<>op.new_payment_id;
 actual=jsonb_set(actual,'{payments}',kept);
 FOREACH k IN ARRAY ARRAY['operations','transactions','requests','dispatches','movements'] LOOP
 SELECT coalesce(jsonb_agg(x ORDER BY ord),'[]') INTO kept FROM jsonb_array_elements(actual->k) WITH ORDINALITY a(x,ord)
 WHERE NOT CASE k
 WHEN 'operations' THEN x->>'id'=op.id::text
 WHEN 'transactions' THEN x->>'order_change_operation_id'=op.id::text
 WHEN 'requests' THEN x->>'operation_id'=op.id::text
 WHEN 'dispatches' THEN EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.id=(x->>'request_id')::bigint AND q.operation_id=op.id)
 WHEN 'movements' THEN EXISTS(SELECT 1 FROM order_edit_inventory_plans p WHERE p.admission_id=target AND p.operation_id=op.id
 AND (x->>'id')::bigint IN (p.hold_movement_id,p.release_movement_id)) OR (op.application_xid=pg_current_xact_id() AND op.state='COMPLETED' AND x->>'reason'='order_change='||op.id::text AND EXISTS(SELECT 1 FROM order_edit_inventory_plans p WHERE p.admission_id=target AND p.release_quantity>0 AND p.product_id=(x->>'product_id')::bigint AND (x->>'quantity')::integer=p.release_quantity AND x->>'movement_type'='ORDER_CHANGE_RELEASE')) ELSE false END IS TRUE;
 actual=jsonb_set(actual,ARRAY[k],kept);
 END LOOP;
 IF op.application_xid=pg_current_xact_id() AND op.state='COMPLETED' THEN
 -- Content equivalence is separately proven by f125_reauth_application_snapshot; normalize
 -- only the exact fields written by the common atomic writer, preserving all other Order fields.
 actual=jsonb_set(actual,'{order}',((actual->'order')-ARRAY['content_revision','current_evidence_epoch_id','evidence_head_history_id','active_payment_id','total_amount','item_subtotal','charge_total','tax_amount','updated_at'])
 || (SELECT jsonb_object_agg(key,value) FROM jsonb_each(baseline->'order') WHERE key=ANY(ARRAY['content_revision','current_evidence_epoch_id','evidence_head_history_id','active_payment_id','total_amount','item_subtotal','charge_total','tax_amount','updated_at'])));
 actual=jsonb_set(jsonb_set(actual,'{items}',baseline->'items'),'{charges}',baseline->'charges');
 FOREACH k IN ARRAY ARRAY['histories','historyItems','historyCharges','epochs'] LOOP
 SELECT coalesce(jsonb_agg(x ORDER BY ord),'[]') INTO kept FROM jsonb_array_elements(actual->k) WITH ORDINALITY a(x,ord)
 WHERE NOT CASE k WHEN 'histories' THEN (x->>'id')::bigint=op.content_change_history_id
 WHEN 'historyItems' THEN (x->>'history_id')::bigint=op.content_change_history_id
 WHEN 'historyCharges' THEN (x->>'history_id')::bigint=op.content_change_history_id
 WHEN 'epochs' THEN x->>'origin_operation_id'=op.id::text ELSE false END IS TRUE;
 actual=jsonb_set(actual,ARRAY[k],kept);END LOOP;
 END IF;
 IF actual IS DISTINCT FROM baseline THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unexpected business or financial change outside owned reauthorization'; END IF;
END;
$$;

-- V80 with only the expired proof alternative added.
CREATE OR REPLACE FUNCTION f125_reauth_inventory_update() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; m stock_movements%ROWTYPE;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
 PERFORM f125_execution_check_runtime(r.id);PERFORM f125_financial_assert_saved(r.id);
 IF r.route<>'REAUTHORIZE' OR r.closed_at IS NOT NULL OR (NEW.after_quantity-NEW.before_quantity)<=0
 OR (to_jsonb(NEW)-ARRAY['operation_id','reservation_state','hold_movement_id','release_movement_id','held_at','consumed_at','released_at','reserve_quantity','release_quantity'])
 IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['operation_id','reservation_state','hold_movement_id','release_movement_id','held_at','consumed_at','released_at','reserve_quantity','release_quantity'])
 OR NOT f125_reauth_is_owned(r.order_id,NEW.operation_id) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inventory plan context immutable or unowned'; END IF;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=NEW.operation_id;
 IF OLD.reservation_state IS NULL AND NEW.reservation_state='HELD' THEN
 IF r.execution_state<>'OBSERVING' OR op.state<>'RESERVED' OR op.new_payment_id IS NOT NULL
 OR EXISTS(SELECT 1 FROM order_change_financial_requests WHERE operation_id=op.id) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Hold requires reserved operation before financial POST'; END IF;
 PERFORM f125_reauth_require_owner(r.id,c.active_attempt_id,c.fencing_token,c.version,op.id) FROM order_edit_execution_controls c WHERE c.admission_id=r.id;
 SELECT * INTO STRICT m FROM stock_movements WHERE id=NEW.hold_movement_id;
 IF ROW(m.product_id,m.order_id,m.movement_type,m.quantity,m.reason,m.stock_after)
 IS DISTINCT FROM ROW(NEW.product_id,r.order_id,'ORDER_CHANGE_RESERVATION'::varchar,-(NEW.after_quantity-NEW.before_quantity),'order_change='||op.id::text,m.stock_before-(NEW.after_quantity-NEW.before_quantity))
 OR NEW.held_at IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Hold movement proof mismatch'; END IF;
 ELSIF OLD.reservation_state='HELD' AND NEW.reservation_state IN ('CONSUMED','RELEASED') THEN
 IF ROW(NEW.operation_id,NEW.hold_movement_id,NEW.held_at) IS DISTINCT FROM ROW(OLD.operation_id,OLD.hold_movement_id,OLD.held_at) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Hold proof immutable'; END IF;
 IF NEW.reservation_state='CONSUMED' THEN
 IF r.execution_state<>'APPLYING' OR op.application_xid IS DISTINCT FROM pg_current_xact_id() OR op.state NOT IN ('COMMIT_PENDING','COMPLETED') THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Consume requires atomic application'; END IF;
 ELSE
 IF op.switch_decided_at IS NOT NULL OR NOT (
 (f125_failure_business_proof(r.id) AND op.state='ABORTED' AND NEW.released_at IS NOT NULL) OR
 (f144_expired_proof(r.id) AND op.state='ABORTED' AND NEW.released_at IS NOT NULL) OR
 (op.state='RESERVED' AND op.new_payment_id IS NULL
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.operation_id=op.id)
 AND NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=op.id))
 OR (op.state IN ('ABORTING','ABORTED')
 AND EXISTS(SELECT 1 FROM payments oldp WHERE oldp.id=r.base_payment_id AND oldp.order_id=r.order_id
 AND oldp.status='AUTHORIZED' AND oldp.amount=r.authorized_amount)
 AND EXISTS(SELECT 1 FROM payments p JOIN payment_transactions t ON t.payment_id=p.id
 JOIN order_change_financial_requests q ON q.transaction_id=t.id AND q.operation_id=op.id
 JOIN order_change_request_dispatches d ON d.request_id=q.id
 WHERE p.id=op.new_payment_id AND p.order_id=r.order_id AND p.status='CANCELLED' AND p.amount=r.after_amount
 AND p.provider='PAYJP' AND p.payment_method='CARD' AND p.provider_payment_id IS NOT NULL
 AND t.order_change_operation_id=op.id AND t.order_change_order_id=r.order_id
 AND t.purpose='ORDER_CHANGE_NEW_AUTH_ABORT' AND t.transaction_type='CANCEL' AND t.status='SUCCEEDED'
 AND t.completed_at IS NOT NULL AND t.provider_transaction_id=p.provider_payment_id AND t.amount=r.after_amount
 AND t.order_content_revision=r.base_content_revision AND q.order_id=r.order_id AND q.payment_id=p.id
 AND q.request_kind='NEW_ABORT' AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type
 AND q.amount=t.amount AND q.provider_flow_id=p.provider_payment_id AND d.state='OBSERVED'
 AND EXISTS(SELECT 1 FROM payment_transactions auth JOIN order_change_financial_requests create_request
 ON create_request.transaction_id=auth.id AND create_request.operation_id=op.id
 JOIN order_change_request_dispatches create_dispatch ON create_dispatch.request_id=create_request.id
 WHERE auth.order_change_operation_id=op.id AND auth.payment_id=p.id AND auth.purpose='ORDER_CHANGE_AUTHORIZATION'
 AND auth.transaction_type='AUTHORIZE' AND auth.status='SUCCEEDED' AND auth.completed_at IS NOT NULL
 AND auth.provider_transaction_id=p.provider_payment_id AND auth.amount=r.after_amount
 AND auth.order_content_revision=r.base_content_revision+1 AND create_request.request_kind='CREATE'
 AND create_dispatch.state='OBSERVED'))
 AND NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=op.id
 AND (t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL' OR t.status='PENDING'))
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.operation_id=op.id AND q.request_kind='OLD_CANCEL'))
 OR (op.state IN ('AUTHORIZING','AUTH_UNKNOWN','REQUIRES_ACTION','ABORTED') AND op.abort_request_id IS NULL
 AND EXISTS(SELECT 1 FROM payments oldp WHERE oldp.id=r.base_payment_id AND oldp.order_id=r.order_id
 AND oldp.status='AUTHORIZED' AND oldp.amount=r.authorized_amount)
 AND EXISTS(SELECT 1 FROM payments p JOIN payment_transactions t ON t.payment_id=p.id
 JOIN order_change_financial_requests q ON q.transaction_id=t.id AND q.operation_id=op.id
 JOIN order_change_request_dispatches d ON d.request_id=q.id
 WHERE p.id=op.new_payment_id AND p.order_id=r.order_id AND p.status='FAILED' AND p.amount=r.after_amount
 AND p.provider='PAYJP' AND p.payment_method='CARD' AND p.provider_payment_id IS NOT NULL
 AND t.order_change_operation_id=op.id AND t.order_change_order_id=r.order_id
 AND t.purpose='ORDER_CHANGE_AUTHORIZATION' AND t.transaction_type='AUTHORIZE'
 AND t.status='FAILED' AND t.failure_code='FLOW_CANCELED' AND t.completed_at IS NOT NULL
 AND t.provider_transaction_id=p.provider_payment_id AND t.amount=r.after_amount
 AND t.order_content_revision=r.base_content_revision+1
 AND q.order_id=r.order_id AND q.payment_id=p.id AND q.request_kind='CREATE'
 AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type AND q.amount=t.amount AND d.state='OBSERVED')
 AND NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=op.id
 AND (t.purpose<>'ORDER_CHANGE_AUTHORIZATION' OR t.status<>'FAILED' OR t.failure_code IS DISTINCT FROM 'FLOW_CANCELED'))
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.operation_id=op.id AND q.request_kind<>'CREATE'))
 ) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Release requires unchanged reserved operation or conclusive owned failed authorization'; END IF;
 SELECT * INTO STRICT m FROM stock_movements WHERE id=NEW.release_movement_id;
 IF ROW(m.product_id,m.order_id,m.movement_type,m.quantity,m.reason,m.stock_after)
 IS DISTINCT FROM ROW(NEW.product_id,r.order_id,'ORDER_CHANGE_RELEASE'::varchar,(NEW.after_quantity-NEW.before_quantity),'order_change='||op.id::text,m.stock_before+(NEW.after_quantity-NEW.before_quantity)) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Release movement proof mismatch'; END IF;
 END IF;
 ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Illegal inventory reservation transition'; END IF;
 RETURN NEW;
END;
$$;

-- V111 with only the NEW_AUTH_EXPIRED alternative added.
CREATE OR REPLACE FUNCTION f125_runtime_other_work(target UUID DEFAULT NULL) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_admissions a WHERE a.closed_at IS NULL AND (target IS NULL OR a.db_generation_id<>target))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE (target IS NULL OR a.db_generation_id<>target)
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=a.id AND r.disposition NOT IN ('UNKNOWN','REVIEW'))
 AND NOT f125_failure_waiting_resolved(a.id) AND NOT f125_not_adopted_attempt_resolved(a.id) AND NOT f143_unfinished_attempt_resolved(a.id) AND NOT f144_expired_attempt_resolved(a.id))
 OR EXISTS(SELECT 1 FROM order_change_request_dispatches d JOIN order_change_financial_requests q ON q.id=d.request_id
 JOIN order_edit_admissions a ON a.order_id=q.order_id JOIN order_change_operations op ON op.id=q.operation_id AND op.submission_key=a.submission_key
 WHERE d.state<>'OBSERVED' AND (target IS NULL OR a.db_generation_id<>target))
$$;

-- V111 with only the NEW_AUTH_EXPIRED alternative added.
CREATE OR REPLACE FUNCTION f125_execution_close_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
    PERFORM f125_execution_assert_unapplied(NEW.admission_id);
    IF c.active_attempt_id IS NOT NULL OR NEW.order_id<>c.order_id OR NEW.db_generation_id<>c.db_generation_id
        OR NEW.invalidated_token<>c.fencing_token+1 OR NEW.control_version<>c.version+1 OR NEW.recording_xid<>pg_current_xact_id()
        OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=c.admission_id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id) OR f143_unfinished_attempt_resolved(a.id) OR f144_expired_attempt_resolved(a.id)))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved work or invalid closure fence';
    END IF;
    SELECT count(*) INTO NEW.resolved_attempt_count FROM order_edit_execution_attempts WHERE admission_id=c.admission_id;
    NEW.local_digest=c.baseline_digest;NEW.actor_name=session_user;NEW.recorded_at=clock_timestamp();RETURN NEW;
END;
$$;

-- V111 with only the NEW_AUTH_EXPIRED alternative added.
CREATE OR REPLACE FUNCTION f125_execution_final_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; c order_edit_execution_controls%ROWTYPE; r order_edit_admissions%ROWTYPE; na BIGINT; nr BIGINT; ne BIGINT; unresolved BIGINT;
BEGIN
    IF TG_TABLE_NAME='order_edit_admissions' THEN target=NEW.id;ELSE target=NEW.admission_id;END IF;
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
    IF r.execution_state='COMPLETED' THEN
        PERFORM f125_edit_application_assert_final(target);RETURN NULL;
    ELSIF r.execution_state='APPLYING' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Applying cannot commit without atomic completion';
    END IF;
    IF EXISTS(SELECT 1 FROM order_edit_execution_attempt_results WHERE admission_id=target AND disposition='APPLIED')
        OR EXISTS(SELECT 1 FROM order_change_operations op WHERE op.order_id=r.order_id
            AND op.submission_key=r.submission_key AND op.application_xid IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application work requires completed receipt'; END IF;
    SELECT * INTO c FROM order_edit_execution_controls WHERE admission_id=target;
    IF NOT FOUND THEN
        -- Pre-V72 rows have NULL unapplied_baseline and are not backfilled. They may
        -- remain untouched, but no newly written OBSERVING state can evade ownership.
        IF r.execution_state='OBSERVING' OR (r.unapplied_baseline IS NOT NULL
            AND r.admission_state='ACCEPTED' AND r.execution_state<>'QUEUED') THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Execution state requires control and ownership evidence';
        END IF;
        RETURN NULL;
    END IF;
    SELECT count(*) INTO na FROM order_edit_execution_attempts WHERE admission_id=target;
    SELECT count(*) INTO nr FROM order_edit_execution_attempt_results WHERE admission_id=target;
    SELECT count(*) INTO ne FROM order_edit_unapplied_evidences WHERE admission_id=target;
    SELECT count(*) INTO unresolved FROM order_edit_execution_attempts a WHERE admission_id=target
        AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id) OR f143_unfinished_attempt_resolved(a.id) OR f144_expired_attempt_resolved(a.id)));
    IF c.get_attempt_count<>na OR c.version<>na+nr+ne OR c.fencing_token<>c.version OR unresolved>1
        OR r.admission_state<>'ACCEPTED' OR r.execution_state IN ('NOT_READY','APPLYING','COMPLETED') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Incomplete execution ownership/evidence sequence';
    END IF;
    IF c.active_attempt_id IS NOT NULL THEN
        IF r.execution_state<>'OBSERVING' OR unresolved<>1 OR NOT EXISTS(SELECT 1 FROM order_edit_execution_attempts a
            WHERE a.id=c.active_attempt_id AND a.admission_id=target AND a.fencing_token=c.fencing_token AND a.control_version=c.version AND a.lease_until=c.lease_until
              AND a.order_id=c.order_id AND c.order_id=r.order_id
              AND a.db_generation_id=c.db_generation_id AND c.db_generation_id=r.db_generation_id
              AND a.baseline_digest=c.baseline_digest
              AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id)) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Active worker/state mismatch';
        END IF;
    ELSIF r.execution_state='OBSERVING' OR (r.execution_state='QUEUED' AND unresolved<>0) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Queued or observing work lacks matching ownership';
    END IF;
    IF (r.closed_at IS NOT NULL)<>(ne=1) OR (r.closed_at IS NOT NULL AND (unresolved<>0 OR c.active_attempt_id IS NOT NULL)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Gate release requires closure proof and resolved/fenced work';
    END IF;
    RETURN NULL;
END;
$$;

-- V111 with only the NEW_AUTH_EXPIRED alternative added.
CREATE OR REPLACE FUNCTION f125_closure_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE receipt order_edit_admissions%ROWTYPE; proof order_edit_admission_evidences%ROWTYPE; control order_edit_execution_controls%ROWTYPE;
BEGIN
 SELECT * INTO STRICT receipt FROM order_edit_admissions WHERE id=NEW.admission_id;
 SELECT * INTO STRICT proof FROM order_edit_admission_evidences WHERE admission_id=NEW.admission_id;
 SELECT * INTO STRICT control FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
 IF (NOT f125_failure_business_proof(NEW.admission_id) AND NOT f125_not_adopted_proof(NEW.admission_id) AND NOT f143_unfinished_proof(NEW.admission_id) AND NOT f144_expired_proof(NEW.admission_id)
     AND receipt.execution_state IN ('REVIEW_REQUIRED','RECOVERY_PENDING','APPLYING','COMPLETED')) OR receipt.applied_operation_id IS NOT NULL
  OR receipt.unapplied_baseline IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Uncertain or legacy work cannot close'; END IF;
 -- The not-adopted proof authorizes exactly one closure shape.
 IF f125_not_adopted_proof(NEW.admission_id) AND (receipt.execution_state<>'REVIEW_REQUIRED'
  OR NEW.terminal_state<>'CLOSED_UNAPPLIED' OR NEW.reason_code<>'CHANGE_NOT_ADOPTED'
  OR NOT EXISTS(SELECT 1 FROM order_edit_not_adopted_closures n WHERE n.admission_id=NEW.admission_id AND n.operation_id=NEW.closure_request_id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Not-adopted proof permits only its own closure'; END IF;
 -- Feature143: the unfinished proof authorizes exactly one closure shape.
 IF f143_unfinished_proof(NEW.admission_id) AND (receipt.execution_state<>'RECOVERY_PENDING'
  OR NEW.terminal_state<>'CLOSED_UNAPPLIED' OR NEW.reason_code<>'REAUTH_UNFINISHED'
  OR NOT EXISTS(SELECT 1 FROM order_edit_unfinished_closures n WHERE n.admission_id=NEW.admission_id AND n.operation_id=NEW.closure_request_id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished proof permits only its own closure'; END IF;
 -- Feature144: the expired proof authorizes exactly one closure shape.
 IF f144_expired_proof(NEW.admission_id) AND (receipt.execution_state NOT IN ('QUEUED','RECOVERY_PENDING','REVIEW_REQUIRED')
  OR NEW.terminal_state<>'CLOSED_UNAPPLIED' OR NEW.reason_code<>'NEW_AUTH_EXPIRED'
  OR NOT EXISTS(SELECT 1 FROM order_change_expired_closures n WHERE n.admission_id=NEW.admission_id AND n.operation_id=NEW.closure_request_id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Expired proof permits only its own closure'; END IF;
 -- Old close guard already verified original binding, runtime, no active/unresolved attempts and current basis.
 NEW.request_version=control.version;NEW.original_xid=receipt.original_xid;
 NEW.financial_digest=receipt.financial_digest;NEW.binding_digest=proof.financial_binding_digest;
 NEW.final_snapshot=f125_execution_local(NEW.order_id);
 NEW.evidence_document=f125_closure_document(NEW);NEW.canonical=f125_closure_canonical(NEW.evidence_document::text);
 NEW.evidence_digest=sha256(NEW.canonical);RETURN NEW;
END;
$$;

-- V111 with only the NEW_AUTH_EXPIRED alternative added.
CREATE OR REPLACE FUNCTION f125_admission_final_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; r order_edit_admissions%ROWTYPE; p order_edit_admission_evidences%ROWTYPE; event order_edit_admission_events%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME='order_edit_admissions' THEN target=NEW.id;ELSE target=NEW.admission_id;END IF;
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
    SELECT * INTO p FROM order_edit_admission_evidences WHERE admission_id=target;
    IF r.admission_state IN ('ACCEPTED','REJECTED_LATE') THEN
        IF p.admission_id IS NULL OR ROW(p.original_xid,p.deadline_at,p.db_generation_id,p.request_digest,p.candidate_digest,p.decision)
            IS DISTINCT FROM ROW(r.original_xid,r.deadline_at,r.db_generation_id,r.request_digest,r.candidate_digest,r.admission_state)
            OR NOT EXISTS(SELECT 1 FROM order_edit_admission_events WHERE admission_id=target AND event_type='ADMISSION_DECIDED'
                AND recording_xid=p.verification_xid AND after_admission=p.decision) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Admission decision requires matching immutable database proof/event';
        END IF;
    ELSIF p.admission_id IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Evidence cannot commit without its decision';
    END IF;
    IF (r.admission_state='RECORDED_UNVERIFIED' AND r.execution_state NOT IN ('NOT_READY','CLOSED_UNAPPLIED','FAILED_UNAPPLIED'))
        OR (r.admission_state='UNPROVEN' AND r.execution_state NOT IN ('REVIEW_REQUIRED','CLOSED_UNAPPLIED','FAILED_UNAPPLIED'))
        OR (r.admission_state='REJECTED_LATE' AND r.execution_state<>'NOT_READY')
        OR (r.admission_state='ACCEPTED' AND r.execution_state='NOT_READY') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Admission/execution state mismatch';
    END IF;
    IF (SELECT count(*) FROM order_edit_admission_events WHERE admission_id=target)<>r.version+1
        OR NOT EXISTS(SELECT 1 FROM order_edit_admission_events WHERE admission_id=target AND event_no=0 AND event_type='RECORDED'
            AND before_admission IS NULL AND before_execution IS NULL AND after_admission='RECORDED_UNVERIFIED'
            AND after_execution='NOT_READY' AND recording_xid=r.original_xid) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Incomplete admission event sequence';
    END IF;
    SELECT * INTO STRICT event FROM order_edit_admission_events WHERE admission_id=target AND event_no=r.version;
    IF ROW(event.after_admission,event.after_execution,event.reason_code) IS DISTINCT FROM ROW(r.admission_state,r.execution_state,r.reason_code)
        OR EXISTS(SELECT 1 FROM order_edit_admission_events n LEFT JOIN order_edit_admission_events previous
            ON previous.admission_id=n.admission_id AND previous.event_no=n.event_no-1
            WHERE n.admission_id=target AND n.event_no>0 AND (previous.admission_id IS NULL
                OR ROW(n.before_admission,n.before_execution) IS DISTINCT FROM ROW(previous.after_admission,previous.after_execution))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Broken admission event chain';
    END IF;
    IF r.execution_state IN ('CLOSED_UNAPPLIED','FAILED_UNAPPLIED') THEN
        IF NOT EXISTS(SELECT 1 FROM order_edit_unapplied_evidences e JOIN order_edit_execution_controls c ON c.admission_id=e.admission_id
                WHERE e.admission_id=target AND e.terminal_state=r.execution_state AND e.reason_code=r.reason_code
                  AND e.invalidated_token=c.fencing_token AND e.control_version=c.version AND c.active_attempt_id IS NULL)
            OR EXISTS(SELECT 1 FROM order_change_operations op WHERE order_id=r.order_id AND submission_key=r.submission_key
                AND NOT EXISTS(SELECT 1 FROM order_edit_failure_business_closures b JOIN order_edit_failure_closure_controls fc ON fc.id=b.control_id WHERE b.admission_id=target AND fc.operation_id=op.id AND op.state='ABORTED')
                AND NOT EXISTS(SELECT 1 FROM order_edit_not_adopted_closures n WHERE n.admission_id=target AND n.operation_id=op.id AND op.state='ABORTED' AND r.reason_code='CHANGE_NOT_ADOPTED')
                AND NOT EXISTS(SELECT 1 FROM order_edit_unfinished_closures n WHERE n.admission_id=target AND n.operation_id=op.id AND op.state='ABORTED' AND r.reason_code='REAUTH_UNFINISHED')
                AND NOT EXISTS(SELECT 1 FROM order_change_expired_closures n WHERE n.admission_id=target AND n.operation_id=op.id AND op.state='ABORTED' AND r.reason_code='NEW_AUTH_EXPIRED')) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Gate cannot close with application work or unproven attempts';
        END IF;
    ELSIF r.execution_state='COMPLETED' THEN
        IF NOT EXISTS(SELECT 1 FROM order_change_operations c WHERE c.id=r.applied_operation_id AND c.order_id=r.order_id
            AND c.state='COMPLETED' AND c.execution_version=1 AND c.execution_route=r.route
            AND c.submission_key=r.submission_key AND c.request_digest=encode(r.request_digest,'hex')
            AND c.base_content_revision=r.base_content_revision AND c.base_evidence_epoch_id=r.base_evidence_epoch_id
            AND c.base_evidence_history_id IS NOT DISTINCT FROM r.base_evidence_history_id
            AND c.old_payment_id=r.base_payment_id AND c.old_authorization_transaction_id=r.base_authorization_id
            AND c.old_authorized_amount=r.authorized_amount AND c.old_authorization_revision=r.authorization_revision
            AND c.before_amount=r.before_amount AND c.after_amount=r.after_amount AND c.content_change_history_id IS NOT NULL) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Completion requires matching retained operation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

-- V111 with only the NEW_AUTH_EXPIRED alternative added.
CREATE OR REPLACE FUNCTION f125_closure_assert_final(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE e order_edit_unapplied_evidences%ROWTYPE; r order_edit_admissions%ROWTYPE; c order_edit_execution_controls%ROWTYPE; owned BOOLEAN;
BEGIN
 SELECT * INTO e FROM order_edit_unapplied_evidences WHERE admission_id=target AND recording_xid=pg_current_xact_id();
 IF NOT FOUND THEN RETURN; END IF; -- A later business transaction is not constrained to the old state.
 PERFORM f125_execution_check_runtime(target);
 owned=f125_failure_business_proof(target) OR f125_not_adopted_proof(target) OR f143_unfinished_proof(target) OR f144_expired_proof(target);
 IF owned THEN PERFORM f125_reauth_assert_current(target); ELSE PERFORM f125_financial_assert_current(target); END IF;
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 IF e.final_snapshot IS DISTINCT FROM f125_execution_local(e.order_id)
  OR (NOT owned AND e.final_snapshot IS DISTINCT FROM r.unapplied_baseline)
  OR e.evidence_document IS DISTINCT FROM f125_closure_document(e)
  OR e.canonical IS DISTINCT FROM f125_closure_canonical(e.evidence_document::text) OR e.evidence_digest IS DISTINCT FROM sha256(e.canonical)
  OR ROW(r.execution_state,r.reason_code,r.closed_at,c.fencing_token,c.version)
   IS DISTINCT FROM ROW(e.terminal_state,e.reason_code,e.recorded_at,e.invalidated_token,e.control_version)
  OR r.applied_operation_id IS NOT NULL OR c.active_attempt_id IS NOT NULL OR c.lease_until IS NOT NULL
  OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=target AND NOT EXISTS(
   SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id) OR f143_unfinished_attempt_resolved(a.id) OR f144_expired_attempt_resolved(a.id))))
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure final business/control/evidence state changed'; END IF;
END;
$$;

DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND (proname LIKE 'f144\_%' OR proname IN (
  'f125_reauth_assert_current','f125_reauth_inventory_update','f125_execution_close_guard','f125_closure_capture',
  'f125_execution_final_check','f125_admission_final_check','f125_closure_assert_final','f125_runtime_other_work')) LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END $$;
