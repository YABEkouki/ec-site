-- Feature130-B: close an owned (REAUTHORIZE) order-edit admission after a Feature128 NOT_ADOPTED
-- final decision has been fully unwound by Feature129-B (new authorization cancelled through the
-- one-time NEW_ABORT dispatch, operation ABORTED, inventory released, old authorization untouched).
-- Such an admission is REVIEW_REQUIRED (REAUTHORIZATION_ABORTED) and the generic V72 closure
-- deliberately refuses uncertain work, so shipping/capture would stay blocked forever.
--
-- Same model as the V80 3DS-failure business closure: an append-only, DB-verified proof row is a
-- transaction-bound capability. Only in the transaction that records the proof may the existing
-- closure guards accept REVIEW_REQUIRED, and only for that admission, terminal CLOSED_UNAPPLIED and
-- reason CHANGE_NOT_ADOPTED. Nothing here contacts the provider or writes business/financial rows.
CREATE TABLE order_edit_not_adopted_closures (
 admission_id UUID PRIMARY KEY REFERENCES order_edit_admissions(id),
 order_id BIGINT NOT NULL REFERENCES orders(id),
 operation_id UUID NOT NULL UNIQUE REFERENCES order_change_final_decisions(operation_id),
 new_payment_id BIGINT NOT NULL UNIQUE REFERENCES payments(id),
 expected_version BIGINT NOT NULL CHECK(expected_version>=0),
 document JSONB NOT NULL, canonical BYTEA NOT NULL,
 digest BYTEA NOT NULL CHECK(digest=sha256(canonical)),
 recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON order_edit_not_adopted_closures FROM PUBLIC;

-- Capability for the current transaction only (as f125_failure_business_proof).
CREATE FUNCTION f125_not_adopted_proof(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_not_adopted_closures n
 WHERE n.admission_id=target AND n.recording_xid=pg_current_xact_id()
 AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical));
$$;
-- Durable: an attempt left with only REVIEW/UNKNOWN results is historical once a verified proof lists it.
CREATE FUNCTION f125_not_adopted_attempt_resolved(attempt UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_execution_attempts a
 JOIN order_edit_not_adopted_closures n ON n.admission_id=a.admission_id
 WHERE a.id=attempt AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical)
 AND n.document->'resolvedAttempts' ? attempt::text);
$$;
-- Durable verification of a committed closure (idempotent replay and administrator views).
CREATE FUNCTION f125_not_adopted_closed(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_not_adopted_closures n
 JOIN order_edit_admissions r ON r.id=n.admission_id
 JOIN order_edit_unapplied_evidences e ON e.admission_id=n.admission_id
 JOIN order_change_operations op ON op.id=n.operation_id
 JOIN order_change_final_decisions d ON d.operation_id=n.operation_id
 JOIN payments p ON p.id=n.new_payment_id
 WHERE n.admission_id=target AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical)
 AND n.document->>'admission'=n.admission_id::text AND n.document->>'operation'=n.operation_id::text
 AND n.document->>'xid'=n.recording_xid::text AND n.document->'expectedVersion'=to_jsonb(n.expected_version)
 AND e.recording_xid=n.recording_xid AND e.closure_request_id=n.operation_id
 AND e.terminal_state='CLOSED_UNAPPLIED' AND e.reason_code='CHANGE_NOT_ADOPTED'
 AND e.control_version=n.expected_version+1
 AND e.canonical=f125_closure_canonical(e.evidence_document::text) AND e.evidence_digest=sha256(e.canonical)
 AND r.execution_state='CLOSED_UNAPPLIED' AND r.reason_code='CHANGE_NOT_ADOPTED' AND r.closed_at=e.recorded_at
 AND r.applied_operation_id IS NULL AND r.order_id=n.order_id
 AND op.order_id=r.order_id AND op.submission_key=r.submission_key AND op.state='ABORTED'
 AND op.switch_decided_at IS NULL AND op.new_payment_id=n.new_payment_id
 AND d.outcome='NOT_ADOPTED' AND p.status='CANCELLED');
$$;

-- No SET timezone here: the nested runtime identity check renders pg_postmaster_start_time() as text.
-- The only TIMESTAMPTZ in the document is rendered explicitly in UTC instead.
CREATE FUNCTION f125_not_adopted_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; d order_change_final_decisions%ROWTYPE;
 ec order_edit_execution_controls%ROWTYPE; latest order_edit_execution_attempt_results%ROWTYPE;
 abort_request BIGINT; resolved JSONB; doc JSONB;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Not-adopted closure proof is append only'; END IF;
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
 SELECT * INTO STRICT op FROM order_change_operations WHERE order_id=r.order_id AND submission_key=r.submission_key;
 -- A direct INSERT is itself a public SQL entry: take the canonical locks (runtime, Order,
 -- operations, Payments by id, transactions, admission, control, related rows) before checking.
 PERFORM f125_reauth_lock(r.order_id,op.id);
 PERFORM f125_closure_lock_related(r.id);
 PERFORM f125_execution_check_runtime(r.id);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=op.id;
 SELECT * INTO d FROM order_change_final_decisions WHERE operation_id=op.id;
 SELECT * INTO ec FROM order_edit_execution_controls WHERE admission_id=r.id;
 SELECT * INTO latest FROM order_edit_execution_attempt_results WHERE admission_id=r.id ORDER BY control_version DESC,recorded_at DESC LIMIT 1;
 SELECT q.id INTO abort_request FROM order_change_financial_requests q WHERE q.operation_id=op.id AND q.request_kind='NEW_ABORT';
 SELECT coalesce(jsonb_agg(a.id::text ORDER BY a.id::text),'[]'::jsonb) INTO resolved FROM order_edit_execution_attempts a
 WHERE a.admission_id=r.id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('REVIEW','UNKNOWN'));
 IF ROW(NEW.order_id,NEW.operation_id,NEW.new_payment_id) IS DISTINCT FROM ROW(r.order_id,op.id,op.new_payment_id)
 OR NEW.recording_xid<>pg_current_xact_id() OR NEW.recorded_at>clock_timestamp()
 -- The admission is the open, unapplied owned receipt that 129-B parked after the abort.
 OR r.route<>'REAUTHORIZE' OR r.admission_state<>'ACCEPTED' OR r.execution_state<>'REVIEW_REQUIRED'
 OR r.reason_code<>'REAUTHORIZATION_ABORTED' OR r.closed_at IS NOT NULL OR r.applied_operation_id IS NOT NULL
 OR NOT f125_reauth_is_owned(r.order_id,op.id)
 OR ec.admission_id IS NULL OR ec.version<>NEW.expected_version OR ec.active_attempt_id IS NOT NULL OR ec.lease_until IS NOT NULL
 OR latest.id IS NULL OR latest.disposition<>'REVIEW' OR latest.reason_code<>'REAUTHORIZATION_ABORTED'
 -- Feature128 decided NOT_ADOPTED; the order was never switched or applied.
 OR d.operation_id IS NULL OR d.outcome<>'NOT_ADOPTED' OR d.order_id<>r.order_id
 OR op.state<>'ABORTED' OR op.switch_decided_at IS NOT NULL OR op.application_xid IS NOT NULL OR op.content_change_history_id IS NOT NULL
 -- The old authorization is still the live one, never cancelled by this operation.
 OR NOT EXISTS(SELECT 1 FROM payments oldp WHERE oldp.id=r.base_payment_id AND oldp.order_id=r.order_id
   AND oldp.status='AUTHORIZED' AND oldp.amount=r.authorized_amount)
 OR EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=op.id
   AND (t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL' OR t.status='PENDING'))
 OR EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.operation_id=op.id AND q.request_kind='OLD_CANCEL')
 -- The new authorization is provably cancelled through the observed one-time NEW_ABORT dispatch.
 OR abort_request IS NULL
 OR NOT EXISTS(SELECT 1 FROM payments p JOIN payment_transactions t ON t.payment_id=p.id
   JOIN order_change_financial_requests q ON q.transaction_id=t.id AND q.operation_id=op.id
   JOIN order_change_request_dispatches x ON x.request_id=q.id
   WHERE q.id=abort_request AND p.id=op.new_payment_id AND p.order_id=r.order_id AND p.status='CANCELLED' AND p.amount=r.after_amount
   AND p.provider='PAYJP' AND p.payment_method='CARD' AND p.provider_payment_id IS NOT NULL
   AND t.order_change_operation_id=op.id AND t.order_change_order_id=r.order_id
   AND t.purpose='ORDER_CHANGE_NEW_AUTH_ABORT' AND t.transaction_type='CANCEL' AND t.status='SUCCEEDED'
   AND t.completed_at IS NOT NULL AND t.provider_transaction_id=p.provider_payment_id AND t.amount=r.after_amount
   AND q.order_id=r.order_id AND q.payment_id=p.id AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type
   AND q.amount=t.amount AND q.provider_flow_id=p.provider_payment_id AND x.state='OBSERVED')
 -- No provider request of the order is in flight and nothing is pending.
 OR EXISTS(SELECT 1 FROM order_change_financial_requests q LEFT JOIN order_change_request_dispatches x ON x.request_id=q.id
   WHERE q.order_id=r.order_id AND (x.request_id IS NULL OR x.state<>'OBSERVED'))
 OR EXISTS(SELECT 1 FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.order_id=r.order_id AND t.status='PENDING')
 -- Every reserved unit was returned to stock.
 OR EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0
   AND (ip.reservation_state IS DISTINCT FROM 'RELEASED' OR ip.release_movement_id IS NULL))
 OR EXISTS(SELECT 1 FROM inventory_reservations WHERE operation_id=op.id)
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Not-adopted closure requires a fully unwound NOT_ADOPTED owned change'; END IF;
 -- Everything outside this operation's own legs equals the admission baseline.
 PERFORM f125_reauth_assert_current(r.id);
 doc=jsonb_build_object('domain','f125/not-adopted/closure/v1','admission',r.id,'order',r.order_id,
  'operation',op.id,'operationVersion',op.version,
  'decision',jsonb_build_object('outcome',d.outcome,'disposition',d.shipping_disposition,'reason',d.reason_code,
    'finalAt',d.final_at,'decidedAt',d.decided_at,'xid',d.decision_xid::text),
  'newPayment',op.new_payment_id,'oldPayment',r.base_payment_id,'abortRequest',abort_request,
  'expectedVersion',NEW.expected_version,'latestResult',latest.id,'resolvedAttempts',resolved,
  'stocks',coalesce((SELECT jsonb_agg(jsonb_build_object('product',ip.product_id,'quantity',ip.reserve_quantity,'release',ip.release_movement_id) ORDER BY ip.product_id)
    FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0),'[]'::jsonb),
  'xid',NEW.recording_xid::text,'recorded',to_char(NEW.recorded_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
 IF NEW.document IS NOT NULL AND NEW.document IS DISTINCT FROM doc THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Not-adopted closure document conflict'; END IF;
 IF (NEW.canonical IS NOT NULL AND NEW.canonical IS DISTINCT FROM f125_closure_encode(doc)) OR (NEW.digest IS NOT NULL AND NEW.digest IS DISTINCT FROM sha256(f125_closure_encode(doc))) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Canonical not-adopted evidence conflict'; END IF;
 NEW.document=doc;NEW.canonical=f125_closure_encode(doc);NEW.digest=sha256(NEW.canonical);
 RETURN NEW;
END;
$$;
CREATE TRIGGER not_adopted_insert BEFORE INSERT OR UPDATE OR DELETE ON order_edit_not_adopted_closures FOR EACH ROW EXECUTE FUNCTION f125_not_adopted_insert();
CREATE TRIGGER not_adopted_truncate BEFORE TRUNCATE ON order_edit_not_adopted_closures EXECUTE FUNCTION f125_failure_no_truncate();

-- The proof is useless on its own: the same transaction must close exactly this admission.
CREATE FUNCTION f125_not_adopted_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.recording_xid<>pg_current_xact_id() THEN RETURN NULL; END IF;
 IF NOT f125_not_adopted_closed(NEW.admission_id) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Incomplete atomic not-adopted closure'; END IF;
 PERFORM f125_closure_assert_final(NEW.admission_id);
 RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER not_adopted_final AFTER INSERT ON order_edit_not_adopted_closures
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_not_adopted_final();

-- Canonical entry. Locks first, idempotent replay, CAS on the execution control version.
CREATE FUNCTION f125_not_adopted_close_unapplied(target UUID, expected_version BIGINT) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; operation UUID; n order_edit_not_adopted_closures%ROWTYPE; ec order_edit_execution_controls%ROWTYPE;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT id INTO STRICT operation FROM order_change_operations WHERE order_id=r.order_id AND submission_key=r.submission_key;
 PERFORM f125_reauth_lock(r.order_id,operation);
 PERFORM f125_closure_lock_related(target);
 SELECT * INTO n FROM order_edit_not_adopted_closures WHERE admission_id=target;
 IF FOUND THEN
  IF expected_version IS DISTINCT FROM n.expected_version OR NOT f125_not_adopted_closed(target) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Not-adopted closure replay conflict'; END IF;
  RETURN n.operation_id;
 END IF;
 SELECT * INTO STRICT ec FROM order_edit_execution_controls WHERE admission_id=target;
 IF expected_version IS NULL OR ec.version<>expected_version THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Not-adopted closure CAS conflict'; END IF;
 INSERT INTO order_edit_not_adopted_closures(admission_id,order_id,operation_id,new_payment_id,expected_version)
 SELECT target,r.order_id,op.id,op.new_payment_id,expected_version FROM order_change_operations op WHERE op.id=operation;
 INSERT INTO order_edit_unapplied_evidences(admission_id,order_id,db_generation_id,invalidated_token,control_version,terminal_state,reason_code,closure_request_id)
 VALUES(target,ec.order_id,ec.db_generation_id,ec.fencing_token+1,ec.version+1,'CLOSED_UNAPPLIED','CHANGE_NOT_ADOPTED',operation);
 UPDATE order_edit_execution_controls SET fencing_token=fencing_token+1,version=version+1,updated_at=clock_timestamp() WHERE admission_id=target;
 UPDATE order_edit_admissions SET execution_state='CLOSED_UNAPPLIED',reason_code='CHANGE_NOT_ADOPTED',version=version+1 WHERE id=target;
 RETURN operation;
END;
$$;

-- V80 bodies, each extended only by the not-adopted proof / durable attempt resolution.
CREATE OR REPLACE FUNCTION f125_runtime_other_work(target UUID DEFAULT NULL) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_admissions a WHERE a.closed_at IS NULL AND (target IS NULL OR a.db_generation_id<>target))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE (target IS NULL OR a.db_generation_id<>target)
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=a.id AND r.disposition NOT IN ('UNKNOWN','REVIEW'))
 AND NOT f125_failure_waiting_resolved(a.id) AND NOT f125_not_adopted_attempt_resolved(a.id))
 OR EXISTS(SELECT 1 FROM order_change_request_dispatches d JOIN order_change_financial_requests q ON q.id=d.request_id
 JOIN order_edit_admissions a ON a.order_id=q.order_id JOIN order_change_operations op ON op.id=q.operation_id AND op.submission_key=a.submission_key
 WHERE d.state<>'OBSERVED' AND (target IS NULL OR a.db_generation_id<>target))
$$;
CREATE OR REPLACE FUNCTION f125_execution_close_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
    PERFORM f125_execution_assert_unapplied(NEW.admission_id);
    IF c.active_attempt_id IS NOT NULL OR NEW.order_id<>c.order_id OR NEW.db_generation_id<>c.db_generation_id
        OR NEW.invalidated_token<>c.fencing_token+1 OR NEW.control_version<>c.version+1 OR NEW.recording_xid<>pg_current_xact_id()
        OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=c.admission_id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id)))) THEN
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
 IF (NOT f125_failure_business_proof(NEW.admission_id) AND NOT f125_not_adopted_proof(NEW.admission_id)
     AND receipt.execution_state IN ('REVIEW_REQUIRED','RECOVERY_PENDING','APPLYING','COMPLETED')) OR receipt.applied_operation_id IS NOT NULL
  OR receipt.unapplied_baseline IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Uncertain or legacy work cannot close'; END IF;
 -- The not-adopted proof authorizes exactly one closure shape.
 IF f125_not_adopted_proof(NEW.admission_id) AND (receipt.execution_state<>'REVIEW_REQUIRED'
  OR NEW.terminal_state<>'CLOSED_UNAPPLIED' OR NEW.reason_code<>'CHANGE_NOT_ADOPTED'
  OR NOT EXISTS(SELECT 1 FROM order_edit_not_adopted_closures n WHERE n.admission_id=NEW.admission_id AND n.operation_id=NEW.closure_request_id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Not-adopted proof permits only its own closure'; END IF;
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
        AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id)));
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
            OR EXISTS(SELECT 1 FROM order_change_operations op WHERE order_id=r.order_id AND submission_key=r.submission_key
                AND NOT EXISTS(SELECT 1 FROM order_edit_failure_business_closures b JOIN order_edit_failure_closure_controls fc ON fc.id=b.control_id WHERE b.admission_id=target AND fc.operation_id=op.id AND op.state='ABORTED')
                AND NOT EXISTS(SELECT 1 FROM order_edit_not_adopted_closures n WHERE n.admission_id=target AND n.operation_id=op.id AND op.state='ABORTED' AND r.reason_code='CHANGE_NOT_ADOPTED')) THEN
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
DECLARE e order_edit_unapplied_evidences%ROWTYPE; r order_edit_admissions%ROWTYPE; c order_edit_execution_controls%ROWTYPE; owned BOOLEAN;
BEGIN
 SELECT * INTO e FROM order_edit_unapplied_evidences WHERE admission_id=target AND recording_xid=pg_current_xact_id();
 IF NOT FOUND THEN RETURN; END IF; -- A later business transaction is not constrained to the old state.
 PERFORM f125_execution_check_runtime(target);
 owned=f125_failure_business_proof(target) OR f125_not_adopted_proof(target);
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
   SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id))))
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure final business/control/evidence state changed'; END IF;
END;
$$;

DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND (proname LIKE 'f125_not_adopted_%' OR proname IN (
  'f125_execution_close_guard','f125_closure_capture','f125_execution_final_check','f125_admission_final_check',
  'f125_closure_assert_final','f125_runtime_other_work')) LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END $$;
