-- A local business closure does not assert provider cancellation. V78 observations and
-- UNKNOWN results remain immutable. The certificate is a transaction-bound capability;
-- every use is checked again by deferred validation before commit.
CREATE TABLE order_edit_failure_business_closures (
 control_id UUID PRIMARY KEY REFERENCES order_edit_failure_closure_controls(id),
 admission_id UUID NOT NULL UNIQUE REFERENCES order_edit_admissions(id),
 order_id BIGINT NOT NULL REFERENCES orders(id),
 new_payment_id BIGINT NOT NULL UNIQUE REFERENCES payments(id),
 expected_version BIGINT NOT NULL CHECK(expected_version>=0),
 failure_observation_id UUID NOT NULL UNIQUE REFERENCES order_edit_failure_closure_observations(id),
 document JSONB NOT NULL, canonical BYTEA NOT NULL,
 digest BYTEA NOT NULL CHECK(digest=sha256(canonical)),
 recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE order_edit_failure_waiting_resolutions (
 control_id UUID NOT NULL REFERENCES order_edit_failure_business_closures(control_id),
 unknown_result_id UUID PRIMARY KEY REFERENCES order_edit_execution_attempt_results(id),
 failure_observation_id UUID NOT NULL REFERENCES order_edit_failure_closure_observations(id),
 document JSONB NOT NULL, canonical BYTEA NOT NULL,
 digest BYTEA NOT NULL CHECK(digest=sha256(canonical)),
 recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id()
);
CREATE FUNCTION f125_failure_business_proof(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_failure_business_closures b
 WHERE b.admission_id=target AND b.recording_xid=pg_current_xact_id()
 AND b.canonical=f125_closure_encode(b.document) AND b.digest=sha256(b.canonical));
$$;
CREATE FUNCTION f125_failure_waiting_resolved(attempt UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_failure_waiting_resolutions w
 JOIN order_edit_failure_business_closures b USING(control_id)
 JOIN order_edit_execution_attempt_results u ON u.id=w.unknown_result_id
 WHERE u.attempt_id=attempt AND u.disposition='UNKNOWN' AND u.reason_code='REAUTHORIZATION_WAITING'
 AND w.canonical=f125_closure_encode(w.document) AND w.digest=sha256(w.canonical)
 AND w.document->'unknown'=to_jsonb(u) AND w.document->>'control'=b.control_id::text
 AND w.document->>'businessDigest'=encode(b.digest,'hex') AND w.document->>'failure'=b.failure_observation_id::text
 AND w.document->>'disposition'='FAILED_UNAPPLIED' AND w.recording_xid=b.recording_xid);
$$;
-- A resolved authentication wait is historical evidence, not live financial work.
-- Other UNKNOWN results and unobserved dispatches retain V76's original STOP.
CREATE OR REPLACE FUNCTION f125_runtime_other_work(target UUID DEFAULT NULL) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_admissions a WHERE a.closed_at IS NULL AND (target IS NULL OR a.db_generation_id<>target))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE (target IS NULL OR a.db_generation_id<>target)
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=a.id AND r.disposition NOT IN ('UNKNOWN','REVIEW'))
 AND NOT f125_failure_waiting_resolved(a.id))
 OR EXISTS(SELECT 1 FROM order_change_request_dispatches d JOIN order_change_financial_requests q ON q.id=d.request_id
 JOIN order_edit_admissions a ON a.order_id=q.order_id JOIN order_change_operations op ON op.id=q.operation_id AND op.submission_key=a.submission_key
 WHERE d.state<>'OBSERVED' AND (target IS NULL OR a.db_generation_id<>target))
$$;
CREATE FUNCTION f125_failure_business_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_failure_closure_controls%ROWTYPE; o order_edit_failure_closure_observations%ROWTYPE; doc JSONB;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business closure proof is append only'; END IF;
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=NEW.control_id;
 -- Certificate INSERT is itself a public SQL entry, just like the V79 intent.
 PERFORM f125_reauth_lock(c.order_id,c.operation_id);
 PERFORM f125_closure_lock_related(c.admission_id);
 PERFORM p.id FROM products p JOIN order_edit_inventory_plans ip ON ip.product_id=p.id WHERE ip.admission_id=c.admission_id AND ip.reservation_state='HELD' ORDER BY p.id FOR UPDATE OF p;
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=NEW.control_id FOR UPDATE;
 PERFORM f125_failure_assert_owner(c);
 SELECT * INTO STRICT o FROM order_edit_failure_closure_observations WHERE id=NEW.failure_observation_id;
 IF c.state<>'FAILURE_OBSERVED' OR c.version<>NEW.expected_version OR c.cancel_request_id IS NOT NULL
 OR c.intent_digest IS DISTINCT FROM sha256(f125_failure_intent_bytes(c)) OR c.intent_digest IS NULL
 OR c.history_digest IS DISTINCT FROM f125_failure_history(c.admission_id)
 OR o.control_id<>c.id OR o.kind<>'THREE_DS_FAILURE' OR o.control_version<>c.version-1
 OR o.canonical IS DISTINCT FROM f125_failure_observation_bytes(o) OR o.digest IS DISTINCT FROM sha256(o.canonical)
 OR o.history_digest IS DISTINCT FROM c.history_digest
 OR ROW(NEW.admission_id,NEW.order_id,NEW.new_payment_id) IS DISTINCT FROM ROW(c.admission_id,c.order_id,c.new_payment_id)
 OR NEW.recording_xid<>pg_current_xact_id() OR NEW.recorded_at>clock_timestamp()
 OR EXISTS(SELECT 1 FROM order_change_operations WHERE id=c.operation_id AND abort_request_id IS NOT NULL)
 OR EXISTS(SELECT 1 FROM order_change_financial_requests q LEFT JOIN order_change_request_dispatches d ON d.request_id=q.id
 WHERE q.order_id=c.order_id AND (d.request_id IS NULL OR d.state<>'OBSERVED'))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=c.admission_id
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('UNKNOWN','REVIEW'))
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition='UNKNOWN' AND x.reason_code='REAUTHORIZATION_WAITING'))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.admission_id=c.admission_id AND x.disposition='UNKNOWN' AND x.reason_code<>'REAUTHORIZATION_WAITING')
 OR EXISTS(SELECT 1 FROM inventory_reservations WHERE operation_id=c.operation_id)
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business closure requires verified 3DS failure and resolved dispatches/WAITING only'; END IF;
 doc=jsonb_build_object('domain','f125/3ds-failure/business-closure/v1','control',c.id,
 'intentDigest',encode(c.intent_digest,'hex'),'failure',o.id,'failureDigest',encode(o.digest,'hex'),
 'expectedVersion',NEW.expected_version,'admission',c.admission_id,'order',c.order_id,'newPayment',c.new_payment_id,
 'before',f125_execution_local(c.order_id),'stocks',coalesce((SELECT jsonb_agg(jsonb_build_object('product',p.id,'stock',p.stock,'quantity',ip.reserve_quantity) ORDER BY p.id) FROM order_edit_inventory_plans ip JOIN products p ON p.id=ip.product_id WHERE ip.admission_id=c.admission_id AND ip.reservation_state='HELD'),'[]'::jsonb),'xid',NEW.recording_xid::text,'recorded',NEW.recorded_at);
 IF NEW.document IS NOT NULL AND NEW.document IS DISTINCT FROM doc THEN RAISE EXCEPTION 'Business closure document conflict'; END IF;
 IF (NEW.canonical IS NOT NULL AND NEW.canonical IS DISTINCT FROM f125_closure_encode(doc)) OR (NEW.digest IS NOT NULL AND NEW.digest IS DISTINCT FROM sha256(f125_closure_encode(doc))) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Canonical business evidence conflict'; END IF;
 NEW.document=doc;NEW.canonical=f125_closure_encode(doc);NEW.digest=sha256(NEW.canonical);
 RETURN NEW;
END;
$$;
CREATE TRIGGER failure_business_insert BEFORE INSERT OR UPDATE OR DELETE ON order_edit_failure_business_closures FOR EACH ROW EXECUTE FUNCTION f125_failure_business_insert();
CREATE TRIGGER failure_business_truncate BEFORE TRUNCATE ON order_edit_failure_business_closures EXECUTE FUNCTION f125_failure_no_truncate();
CREATE FUNCTION f125_failure_waiting_insert() RETURNS trigger LANGUAGE plpgsql SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
DECLARE b order_edit_failure_business_closures%ROWTYPE; u order_edit_execution_attempt_results%ROWTYPE; doc JSONB;
 c order_edit_failure_closure_controls%ROWTYPE; a order_edit_execution_attempts%ROWTYPE; ec order_edit_execution_controls%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='WAITING resolution is append only'; END IF;
 SELECT * INTO STRICT b FROM order_edit_failure_business_closures WHERE control_id=NEW.control_id;
 SELECT * INTO STRICT u FROM order_edit_execution_attempt_results WHERE id=NEW.unknown_result_id;
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=b.control_id;
 SELECT * INTO STRICT a FROM order_edit_execution_attempts WHERE id=u.attempt_id;
 SELECT * INTO STRICT ec FROM order_edit_execution_controls WHERE admission_id=b.admission_id;
 IF NOT f125_failure_business_proof(u.admission_id) OR b.admission_id<>u.admission_id
 OR u.disposition<>'UNKNOWN' OR u.reason_code<>'REAUTHORIZATION_WAITING'
 OR ROW(u.order_id,a.order_id,a.admission_id,a.db_generation_id,u.fencing_token) IS DISTINCT FROM ROW(c.order_id,c.order_id,c.admission_id,c.generation_id,a.fencing_token)
 OR u.control_version<a.control_version OR u.control_version>=ec.version
 OR ec.active_attempt_id IS NOT NULL OR ec.lease_until IS NOT NULL OR ec.version<>c.execution_version
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('UNKNOWN','REVIEW'))
 OR NEW.failure_observation_id<>b.failure_observation_id OR NEW.recording_xid<>pg_current_xact_id()
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Only verified WAITING UNKNOWN may resolve'; END IF;
 doc=jsonb_build_object('domain','f125/3ds-failure/waiting-resolution/v1','control',b.control_id,
 'businessDigest',encode(b.digest,'hex'),'unknown',to_jsonb(u),'attempt',to_jsonb(a),'generation',c.generation_id,'executionVersion',ec.version,'failure',b.failure_observation_id,
 'disposition','FAILED_UNAPPLIED','xid',NEW.recording_xid::text);
 IF NEW.document IS NOT NULL AND NEW.document IS DISTINCT FROM doc THEN RAISE EXCEPTION 'WAITING document conflict'; END IF;
 IF (NEW.canonical IS NOT NULL AND NEW.canonical IS DISTINCT FROM f125_closure_encode(doc)) OR (NEW.digest IS NOT NULL AND NEW.digest IS DISTINCT FROM sha256(f125_closure_encode(doc))) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Canonical business evidence conflict'; END IF;
 NEW.document=doc;NEW.canonical=f125_closure_encode(doc);NEW.digest=sha256(NEW.canonical); RETURN NEW;
END;
$$;
CREATE TRIGGER failure_waiting_insert BEFORE INSERT OR UPDATE OR DELETE ON order_edit_failure_waiting_resolutions FOR EACH ROW EXECUTE FUNCTION f125_failure_waiting_insert();
CREATE TRIGGER failure_waiting_truncate BEFORE TRUNCATE ON order_edit_failure_waiting_resolutions EXECUTE FUNCTION f125_failure_no_truncate();
CREATE OR REPLACE FUNCTION f125_failure_intent_mutation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE old_order BIGINT; new_order BIGINT;
BEGIN
 IF TG_OP<>'INSERT' THEN old_order=f125_closure_owner(TG_TABLE_NAME,to_jsonb(OLD)); END IF;
 IF TG_OP<>'DELETE' THEN new_order=f125_closure_owner(TG_TABLE_NAME,to_jsonb(NEW)); END IF;
 IF EXISTS(SELECT 1 FROM order_edit_failure_closure_controls
   WHERE (order_id=old_order OR order_id=new_order) AND NOT EXISTS(SELECT 1 FROM order_edit_failure_business_closures b WHERE b.control_id=order_edit_failure_closure_controls.id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure intent fences normal order-edit mutation';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$;
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
CREATE OR REPLACE FUNCTION f125_execution_close_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
    PERFORM f125_execution_assert_unapplied(NEW.admission_id);
    IF c.active_attempt_id IS NOT NULL OR NEW.order_id<>c.order_id OR NEW.db_generation_id<>c.db_generation_id
        OR NEW.invalidated_token<>c.fencing_token+1 OR NEW.control_version<>c.version+1 OR NEW.recording_xid<>pg_current_xact_id()
        OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=c.admission_id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id)))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved work or invalid closure fence';
    END IF;
    SELECT count(*) INTO NEW.resolved_attempt_count FROM order_edit_execution_attempts WHERE admission_id=c.admission_id;
    NEW.local_digest=c.baseline_digest;NEW.actor_name=session_user;NEW.recorded_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION f125_closure_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE receipt order_edit_admissions%ROWTYPE; proof order_edit_admission_evidences%ROWTYPE; control order_edit_execution_controls%ROWTYPE;
BEGIN
 SELECT * INTO STRICT receipt FROM order_edit_admissions WHERE id=NEW.admission_id;
 SELECT * INTO STRICT proof FROM order_edit_admission_evidences WHERE admission_id=NEW.admission_id;
 SELECT * INTO STRICT control FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
 IF (NOT f125_failure_business_proof(NEW.admission_id) AND receipt.execution_state IN ('REVIEW_REQUIRED','RECOVERY_PENDING','APPLYING','COMPLETED')) OR receipt.applied_operation_id IS NOT NULL
  OR receipt.unapplied_baseline IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Uncertain or legacy work cannot close'; END IF;
 -- Old close guard already verified original binding, runtime, no active/unresolved attempts and current basis.
 NEW.request_version=control.version;NEW.original_xid=receipt.original_xid;
 NEW.financial_digest=receipt.financial_digest;NEW.binding_digest=proof.financial_binding_digest;
 NEW.final_snapshot=f125_execution_local(NEW.order_id);
 NEW.evidence_document=f125_closure_document(NEW);NEW.canonical=f125_closure_canonical(NEW.evidence_document::text);
 NEW.evidence_digest=sha256(NEW.canonical);RETURN NEW;
END;
$$;
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
        AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id)));
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
            OR EXISTS(SELECT 1 FROM order_change_operations op WHERE order_id=r.order_id AND submission_key=r.submission_key AND NOT EXISTS(SELECT 1 FROM order_edit_failure_business_closures b JOIN order_edit_failure_closure_controls fc ON fc.id=b.control_id WHERE b.admission_id=target AND fc.operation_id=op.id AND op.state='ABORTED')) THEN
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
CREATE OR REPLACE FUNCTION f125_closure_assert_final(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE e order_edit_unapplied_evidences%ROWTYPE; r order_edit_admissions%ROWTYPE; c order_edit_execution_controls%ROWTYPE;
BEGIN
 SELECT * INTO e FROM order_edit_unapplied_evidences WHERE admission_id=target AND recording_xid=pg_current_xact_id();
 IF NOT FOUND THEN RETURN; END IF; -- A later business transaction is not constrained to the old state.
 PERFORM f125_execution_check_runtime(target);
 IF f125_failure_business_proof(target) THEN PERFORM f125_reauth_assert_current(target); ELSE PERFORM f125_financial_assert_current(target); END IF;
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 IF e.final_snapshot IS DISTINCT FROM f125_execution_local(e.order_id)
  OR (NOT f125_failure_business_proof(target) AND e.final_snapshot IS DISTINCT FROM r.unapplied_baseline)
  OR e.evidence_document IS DISTINCT FROM f125_closure_document(e)
  OR e.canonical IS DISTINCT FROM f125_closure_canonical(e.evidence_document::text) OR e.evidence_digest IS DISTINCT FROM sha256(e.canonical)
  OR ROW(r.execution_state,r.reason_code,r.closed_at,c.fencing_token,c.version)
   IS DISTINCT FROM ROW(e.terminal_state,e.reason_code,e.recorded_at,e.invalidated_token,e.control_version)
  OR r.applied_operation_id IS NOT NULL OR c.active_attempt_id IS NOT NULL OR c.lease_until IS NOT NULL
  OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=target AND NOT EXISTS(
   SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id))))
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure final business/control/evidence state changed'; END IF;
END;
$$;

-- Terminal exclusion follows the new Payment forever, rather than freezing the
-- customer's formal order after the atomic transaction has committed.
CREATE FUNCTION f125_failure_exclusion_guard() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE b order_edit_failure_business_closures%ROWTYPE; c order_edit_failure_closure_controls%ROWTYPE;
 old_row JSONB; new_row JSONB; old_pid BIGINT; new_pid BIGINT; old_op UUID; new_op UUID;
BEGIN
 IF TG_OP<>'INSERT' THEN old_row=to_jsonb(OLD); END IF;
 IF TG_OP<>'DELETE' THEN new_row=to_jsonb(NEW); END IF;
 IF TG_TABLE_NAME='orders' THEN
  IF EXISTS(SELECT 1 FROM order_edit_failure_business_closures WHERE new_payment_id IN (
   (old_row->>'active_payment_id')::bigint,(new_row->>'active_payment_id')::bigint,
   (old_row->>'shipment_capture_payment_id')::bigint,(new_row->>'shipment_capture_payment_id')::bigint)) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Failed new Payment cannot be adopted'; END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
 END IF;
 IF TG_TABLE_NAME='order_change_operations' THEN
  FOR b IN SELECT bc.* FROM order_edit_failure_business_closures bc
   JOIN order_edit_failure_closure_controls fc ON fc.id=bc.control_id
   WHERE fc.operation_id IN ((old_row->>'id')::uuid,(new_row->>'id')::uuid)
    OR bc.new_payment_id IN ((old_row->>'old_payment_id')::bigint,(old_row->>'new_payment_id')::bigint,
      (new_row->>'old_payment_id')::bigint,(new_row->>'new_payment_id')::bigint) LOOP
   SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=b.control_id;
   IF TG_OP='UPDATE' AND b.recording_xid=pg_current_xact_id() AND OLD.id=c.operation_id AND NEW.id=c.operation_id
    AND OLD.state IN ('AUTHORIZING','AUTH_UNKNOWN','REQUIRES_ACTION','ABORTING','ABORT_UNKNOWN') AND NEW.state='ABORTED'
    AND NEW.version=OLD.version+1 AND (new_row-ARRAY['state','version','updated_at'])=(old_row-ARRAY['state','version','updated_at']) THEN
    CONTINUE;
   END IF;
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business-closed operation and Payment references are durable';
  END LOOP;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
 END IF;
 IF TG_TABLE_NAME='order_change_request_dispatches' THEN
  SELECT payment_id,operation_id INTO old_pid,old_op FROM order_change_financial_requests WHERE id=(old_row->>'request_id')::bigint;
  SELECT payment_id,operation_id INTO new_pid,new_op FROM order_change_financial_requests WHERE id=(new_row->>'request_id')::bigint;
 ELSIF TG_TABLE_NAME='payments' THEN old_pid=(old_row->>'id')::bigint;new_pid=(new_row->>'id')::bigint;
 ELSE
  old_pid=(old_row->>'payment_id')::bigint;new_pid=(new_row->>'payment_id')::bigint;
  old_op=coalesce(old_row->>'operation_id',old_row->>'order_change_operation_id')::uuid;
  new_op=coalesce(new_row->>'operation_id',new_row->>'order_change_operation_id')::uuid;
 END IF;
 FOR b IN SELECT bc.* FROM order_edit_failure_business_closures bc
  JOIN order_edit_failure_closure_controls fc ON fc.id=bc.control_id
  WHERE bc.new_payment_id IN (old_pid,new_pid) OR fc.operation_id IN (old_op,new_op) LOOP
  SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=b.control_id;
  IF TG_TABLE_NAME='payments' THEN
   IF TG_OP='UPDATE' AND b.recording_xid=pg_current_xact_id()
    AND OLD.id=b.new_payment_id AND NEW.id=b.new_payment_id
    AND OLD.status IN ('PENDING','REQUIRES_ACTION') AND NEW.status='FAILED'
    AND (new_row-ARRAY['status','updated_at'])=(old_row-ARRAY['status','updated_at']) THEN CONTINUE; END IF;
  ELSIF TG_TABLE_NAME='payment_transactions' THEN
   IF TG_OP='UPDATE' AND b.recording_xid=pg_current_xact_id()
    AND OLD.id=c.authorization_id AND NEW.id=c.authorization_id AND OLD.status='PENDING' AND NEW.status='FAILED'
    AND NEW.failure_code='THREE_DS_FAILED' AND NEW.completed_at IS NOT NULL AND NEW.provider_transaction_id=c.flow_id
    AND (new_row-ARRAY['status','completed_at','failure_code','failure_message','provider_transaction_id','updated_at'])=
        (old_row-ARRAY['status','completed_at','failure_code','failure_message','provider_transaction_id','updated_at']) THEN CONTINUE; END IF;
  END IF;
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business-closed new Payment is permanently excluded from financial work';
 END LOOP;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;
DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['orders','order_change_operations','payments','payment_transactions',
  'capture_requests','order_change_request_dispatches','order_change_financial_requests'] LOOP
  EXECUTE format('CREATE TRIGGER aa_failure_exclusion BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION f125_failure_exclusion_guard()',tbl);
  -- As in V64: immediate RI_ FK checks execute before zz_. The VOLATILE
  -- recheck sees a closure committed while an INSERT/key UPDATE waited on FK locks.
  EXECUTE format('CREATE TRIGGER zz_failure_exclusion AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION f125_failure_exclusion_guard()',tbl);
 END LOOP;
END $$;

CREATE FUNCTION f125_failure_business_closed(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_failure_business_closures b
 JOIN order_edit_failure_closure_controls c ON c.id=b.control_id
 JOIN order_edit_admissions r ON r.id=b.admission_id
 JOIN order_change_operations op ON op.id=c.operation_id
 JOIN payments p ON p.id=b.new_payment_id
 JOIN payment_transactions t ON t.id=c.authorization_id
 JOIN order_edit_unapplied_evidences e ON e.admission_id=b.admission_id
 JOIN order_edit_failure_closure_observations o ON o.id=b.failure_observation_id
 JOIN order_edit_execution_controls ec ON ec.admission_id=b.admission_id
 WHERE b.control_id=target AND b.canonical=f125_closure_encode(b.document) AND b.digest=sha256(b.canonical)
 AND ROW(b.admission_id,b.order_id,b.new_payment_id,b.expected_version) IS NOT DISTINCT FROM ROW(c.admission_id,c.order_id,c.new_payment_id,c.version)
 AND c.state='FAILURE_OBSERVED' AND c.cancel_request_id IS NULL AND c.intent_digest=sha256(f125_failure_intent_bytes(c))
 AND o.control_id=c.id AND o.kind='THREE_DS_FAILURE' AND o.control_version=c.version-1
 AND o.canonical=f125_failure_observation_bytes(o) AND o.digest=sha256(o.canonical)
 AND b.document->>'control'=c.id::text AND b.document->>'failure'=o.id::text
 AND b.document->>'intentDigest'=encode(c.intent_digest,'hex') AND b.document->>'failureDigest'=encode(o.digest,'hex')
 AND b.document->>'xid'=b.recording_xid::text AND b.document->'expectedVersion'=to_jsonb(b.expected_version)
 AND op.new_payment_id=b.new_payment_id AND op.old_payment_id=c.old_payment_id
 AND p.order_id=b.order_id AND p.provider_payment_id=c.flow_id AND p.amount=r.after_amount
 AND t.payment_id=b.new_payment_id AND t.order_change_operation_id=c.operation_id AND t.transaction_type='AUTHORIZE'
 AND t.provider_transaction_id=c.flow_id AND t.completed_at IS NOT NULL AND t.amount=r.after_amount
 AND ec.active_attempt_id IS NULL AND ec.lease_until IS NULL AND e.invalidated_token=ec.fencing_token AND e.control_version=ec.version
 AND e.recording_xid=b.recording_xid AND e.reason_code='THREE_DS_FAILED' AND r.reason_code=e.reason_code AND r.closed_at=e.recorded_at
 AND r.execution_state='FAILED_UNAPPLIED' AND r.closed_at IS NOT NULL AND r.applied_operation_id IS NULL
 AND op.state='ABORTED' AND op.switch_decided_at IS NULL AND op.content_change_history_id IS NULL
 AND p.status='FAILED' AND t.status='FAILED' AND t.failure_code='THREE_DS_FAILED'
 AND e.closure_request_id=b.control_id AND e.terminal_state='FAILED_UNAPPLIED'
 AND e.canonical=f125_closure_canonical(e.evidence_document::text) AND e.evidence_digest=sha256(e.canonical));
$$;
CREATE FUNCTION f125_failure_business_assert_final(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE b order_edit_failure_business_closures%ROWTYPE; c order_edit_failure_closure_controls%ROWTYPE;
 actual JSONB; before_doc JSONB; k TEXT;
BEGIN
 SELECT * INTO STRICT b FROM order_edit_failure_business_closures WHERE control_id=target;
 IF b.recording_xid<>pg_current_xact_id() THEN RETURN; END IF;
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=b.control_id;
 IF NOT f125_failure_business_closed(target) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Incomplete atomic business closure'; END IF;
 PERFORM f125_reauth_assert_current(b.admission_id);
 actual=f125_execution_local(b.order_id);before_doc=b.document->'before';
 -- These collections include the old AUTH and formal order, and must be byte-for-byte unchanged.
 FOREACH k IN ARRAY ARRAY['order','items','charges','histories','historyItems','historyCharges','epochs','captures','requests','dispatches','changeItems','reservations'] LOOP
  IF actual->k IS DISTINCT FROM before_doc->k THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business closure changed formal order or financial requests'; END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(before_doc->'payments') old_p WHERE (old_p->>'id')::bigint<>b.new_payment_id AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(actual->'payments') new_p WHERE new_p=old_p))
 OR EXISTS(SELECT 1 FROM jsonb_array_elements(b.document->'stocks') s JOIN products p ON p.id=(s->>'product')::bigint WHERE p.stock<>(s->>'stock')::integer+(s->>'quantity')::integer)
 OR EXISTS(SELECT 1 FROM jsonb_array_elements(before_doc->'movements') old_m WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(actual->'movements') new_m WHERE new_m=old_m))
 OR EXISTS(SELECT 1 FROM order_edit_inventory_plans ip JOIN stock_movements m ON m.id=ip.release_movement_id JOIN products p ON p.id=ip.product_id WHERE ip.admission_id=b.admission_id AND p.stock<>m.stock_after)
 OR EXISTS(SELECT 1 FROM order_edit_inventory_plans p WHERE p.admission_id=b.admission_id AND p.reserve_quantity>0
   AND (p.reservation_state IS DISTINCT FROM 'RELEASED' OR p.release_movement_id IS NULL))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=b.admission_id
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=a.id AND r.disposition NOT IN ('UNKNOWN','REVIEW'))
 AND NOT f125_failure_waiting_resolved(a.id))
 OR EXISTS(SELECT 1 FROM order_change_operations op WHERE op.id=c.operation_id AND
 (op.abort_flow_closed_at IS NOT NULL OR op.abort_completed_at IS NOT NULL OR op.abort_request_id IS NOT NULL))
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business closure lacks release/resolution or fabricated provider cancellation'; END IF;
 PERFORM f125_closure_assert_final(b.admission_id);
END;
$$;
CREATE FUNCTION f125_failure_business_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM f125_failure_business_assert_final(NEW.control_id); RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER failure_business_final AFTER INSERT ON order_edit_failure_business_closures
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_failure_business_final();
CREATE CONSTRAINT TRIGGER failure_waiting_final AFTER INSERT ON order_edit_failure_waiting_resolutions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_failure_business_final();

CREATE FUNCTION f125_failure_close_unapplied(p_control_id UUID, expected_version BIGINT) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE c order_edit_failure_closure_controls%ROWTYPE; b order_edit_failure_business_closures%ROWTYPE;
 x order_edit_inventory_plans%ROWTYPE; product products%ROWTYPE; r order_edit_admissions%ROWTYPE;
 ec order_edit_execution_controls%ROWTYPE; observation UUID; movement BIGINT;
BEGIN
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=p_control_id;
 PERFORM f125_reauth_lock(c.order_id,c.operation_id);
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=p_control_id FOR UPDATE;
 SELECT * INTO b FROM order_edit_failure_business_closures WHERE order_edit_failure_business_closures.control_id=c.id;
 IF FOUND THEN
  IF expected_version IS DISTINCT FROM b.expected_version OR NOT f125_failure_business_closed(c.id) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business closure replay conflict'; END IF;
  RETURN c.id;
 END IF;
 IF expected_version IS NULL OR c.version<>expected_version THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business closure CAS conflict'; END IF;
 PERFORM f125_closure_lock_related(c.admission_id);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=c.admission_id;
 SELECT * INTO STRICT ec FROM order_edit_execution_controls WHERE admission_id=c.admission_id;
 SELECT id INTO STRICT observation FROM order_edit_failure_closure_observations WHERE order_edit_failure_closure_observations.control_id=c.id AND kind='THREE_DS_FAILURE';
 PERFORM p.id FROM products p JOIN order_edit_inventory_plans ip ON ip.product_id=p.id WHERE ip.admission_id=c.admission_id AND ip.reservation_state='HELD' ORDER BY p.id FOR UPDATE OF p;
 INSERT INTO order_edit_failure_business_closures(control_id,admission_id,order_id,new_payment_id,expected_version,failure_observation_id)
 VALUES(c.id,c.admission_id,c.order_id,c.new_payment_id,expected_version,observation);
 INSERT INTO order_edit_failure_waiting_resolutions(control_id,unknown_result_id,failure_observation_id)
 SELECT c.id,u.id,observation FROM order_edit_execution_attempt_results u
 WHERE u.admission_id=c.admission_id AND u.disposition='UNKNOWN' AND u.reason_code='REAUTHORIZATION_WAITING';
 UPDATE payment_transactions SET status='FAILED',failure_code='THREE_DS_FAILED',
 failure_message='Verified three_d_secure_failed; local business closure; provider flow not canceled',
 provider_transaction_id=c.flow_id,completed_at=timezone('Asia/Tokyo',clock_timestamp()) WHERE id=c.authorization_id AND status='PENDING';
 IF NOT FOUND THEN RAISE EXCEPTION 'Authorization changed before failure closure'; END IF;
 UPDATE payments SET status='FAILED',updated_at=timezone('Asia/Tokyo',clock_timestamp()) WHERE id=c.new_payment_id AND status IN ('PENDING','REQUIRES_ACTION');
 IF NOT FOUND THEN RAISE EXCEPTION 'Payment changed before failure closure'; END IF;
 UPDATE order_change_operations SET state='ABORTED',version=version+1,updated_at=timezone('Asia/Tokyo',clock_timestamp()) WHERE id=c.operation_id;
 FOR x IN SELECT * FROM order_edit_inventory_plans WHERE admission_id=c.admission_id AND reservation_state='HELD' ORDER BY product_id FOR UPDATE LOOP
  SELECT * INTO STRICT product FROM products WHERE id=x.product_id FOR UPDATE;
  UPDATE products SET stock=stock+x.reserve_quantity WHERE id=x.product_id;
  INSERT INTO stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,
    changed_by_type,changed_by_username,reason,order_id)
  VALUES(x.product_id,product.name,'ORDER_CHANGE_RELEASE',x.reserve_quantity,product.stock,product.stock+x.reserve_quantity,
    'SYSTEM','SYSTEM','order_change='||c.operation_id::text,c.order_id) RETURNING id INTO movement;
  UPDATE order_edit_inventory_plans SET reservation_state='RELEASED',release_movement_id=movement,released_at=timezone('Asia/Tokyo',clock_timestamp())
  WHERE admission_id=x.admission_id AND product_id=x.product_id;
 END LOOP;
 INSERT INTO order_edit_unapplied_evidences(admission_id,order_id,db_generation_id,invalidated_token,control_version,terminal_state,reason_code,closure_request_id)
 VALUES(c.admission_id,c.order_id,c.generation_id,ec.fencing_token+1,ec.version+1,'FAILED_UNAPPLIED','THREE_DS_FAILED',c.id);
 UPDATE order_edit_execution_controls SET fencing_token=fencing_token+1,version=version+1,updated_at=clock_timestamp() WHERE admission_id=c.admission_id;
 UPDATE order_edit_admissions SET execution_state='FAILED_UNAPPLIED',reason_code='THREE_DS_FAILED',version=version+1 WHERE id=c.admission_id;
 PERFORM f125_failure_business_assert_final(c.id);
 RETURN c.id;
END;
$$;
DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND (proname LIKE 'f125_failure_%' OR proname IN (
  'f125_reauth_inventory_update','f125_execution_close_guard','f125_closure_capture',
  'f125_execution_final_check','f125_admission_final_check','f125_closure_assert_final','f125_runtime_other_work')) LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END $$;
