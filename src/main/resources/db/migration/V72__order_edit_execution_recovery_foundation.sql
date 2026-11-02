-- Internal execution and proven unapplied closure only. No GET, worker, order writer or activation.
-- Ordinary ecuser writers are trusted to use canonical locking entry points; triggers do not certify locks.
-- Trusted application writers must enter through the canonical locking functions before DML.
-- Row triggers validate facts but do not certify that those locks were acquired.
-- All entry points require V71's isolated runtime and its lock order before execution-control locks.
CREATE TABLE order_edit_execution_controls (
    admission_id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL,
    db_generation_id UUID NOT NULL REFERENCES order_edit_runtime_generations(id),
    policy_version SMALLINT NOT NULL DEFAULT 1 CHECK(policy_version=1),
    automatic_until TIMESTAMPTZ NOT NULL,
    max_get_attempts INTEGER NOT NULL CHECK(max_get_attempts BETWEEN 1 AND 20),
    lease_duration INTERVAL NOT NULL CHECK(lease_duration>interval '0' AND lease_duration<=interval '5 minutes'),
    retry_delay INTERVAL NOT NULL CHECK(retry_delay>=interval '0' AND retry_delay<=interval '5 minutes'),
    baseline JSONB NOT NULL,
    baseline_digest BYTEA NOT NULL CHECK(baseline_digest=sha256(convert_to(baseline::text,'UTF8'))),
    fencing_token BIGINT NOT NULL DEFAULT 0 CHECK(fencing_token>=0),
    active_attempt_id UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    get_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(get_attempt_count>=0 AND get_attempt_count<=max_get_attempts),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(order_id,admission_id),
    FOREIGN KEY(order_id,admission_id) REFERENCES order_edit_admissions(order_id,id),
    CHECK((active_attempt_id IS NULL)=(lease_until IS NULL))
);
CREATE TABLE order_edit_execution_attempts (
    id UUID PRIMARY KEY,
    admission_id UUID NOT NULL,
    order_id BIGINT NOT NULL,
    db_generation_id UUID NOT NULL REFERENCES order_edit_runtime_generations(id),
    fencing_token BIGINT NOT NULL CHECK(fencing_token>0),
    control_version BIGINT NOT NULL CHECK(control_version>0),
    baseline_digest BYTEA NOT NULL CHECK(octet_length(baseline_digest)=32),
    claimed_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL CHECK(lease_until>claimed_at),
    recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(admission_id,id), UNIQUE(admission_id,fencing_token),
    FOREIGN KEY(order_id,admission_id) REFERENCES order_edit_execution_controls(order_id,admission_id)
);
ALTER TABLE order_edit_execution_controls ADD CONSTRAINT fk_execution_active_attempt
    FOREIGN KEY(admission_id,active_attempt_id) REFERENCES order_edit_execution_attempts(admission_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE order_edit_execution_attempt_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    order_id BIGINT NOT NULL,
    fencing_token BIGINT NOT NULL,
    control_version BIGINT NOT NULL,
    recovery BOOLEAN NOT NULL,
    disposition TEXT NOT NULL CHECK(disposition IN ('REQUEUE','CLOSE','FAIL','REVIEW','UNKNOWN')),
    reason_code VARCHAR(80) NOT NULL CHECK(length(reason_code)>0),
    local_digest BYTEA NOT NULL CHECK(octet_length(local_digest)=32),
    recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(admission_id,attempt_id) REFERENCES order_edit_execution_attempts(admission_id,id),
    FOREIGN KEY(order_id,admission_id) REFERENCES order_edit_execution_controls(order_id,admission_id)
);
CREATE TABLE order_edit_unapplied_evidences (
    admission_id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL,
    db_generation_id UUID NOT NULL REFERENCES order_edit_runtime_generations(id),
    invalidated_token BIGINT NOT NULL,
    control_version BIGINT NOT NULL,
    terminal_state TEXT NOT NULL CHECK(terminal_state IN ('CLOSED_UNAPPLIED','FAILED_UNAPPLIED')),
    reason_code VARCHAR(80) NOT NULL CHECK(length(reason_code)>0),
    local_digest BYTEA NOT NULL CHECK(octet_length(local_digest)=32),
    resolved_attempt_count INTEGER NOT NULL CHECK(resolved_attempt_count>=0),
    actor_name TEXT NOT NULL,
    recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(order_id,admission_id) REFERENCES order_edit_execution_controls(order_id,admission_id)
);
-- Generation, identity, deadline and admission proof stay immutable in V71.
-- Non-locking runtime validation for row triggers. Only canonical entry points acquire
-- runtime FOR SHARE before Order; a trigger must never go back upstream after UPDATE.
CREATE FUNCTION f125_admission_check_runtime(expected UUID DEFAULT NULL) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE control order_edit_runtime_control%ROWTYPE;
BEGIN
    SELECT * INTO STRICT control FROM order_edit_runtime_control WHERE id=1;
    IF control.state<>'ISOLATED_TEST' OR (expected IS NOT NULL AND expected<>control.generation_id) THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Admission runtime stopped or generation changed';
    END IF;
    PERFORM f125_admission_test_environment();
    IF NOT EXISTS(SELECT 1 FROM order_edit_runtime_generations WHERE id=control.generation_id
        AND scope='ISOLATED_TEST' AND identity=f125_admission_identity()) THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Admission runtime lineage unproven';
    END IF;
    RETURN control.generation_id;
END;
$$;
CREATE FUNCTION f125_execution_check_runtime(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_generation UUID;
BEGIN
    PERFORM f125_require_read_committed();
    SELECT db_generation_id INTO STRICT v_generation FROM order_edit_admissions WHERE id=target;
    PERFORM f125_admission_check_runtime(v_generation);
END;
$$;

CREATE FUNCTION f125_execution_local(owner_id BIGINT) RETURNS JSONB LANGUAGE SQL STABLE AS $$
    SELECT jsonb_build_object(
      'order',(SELECT to_jsonb(o) FROM orders o WHERE o.id=owner_id),
      'items',coalesce((SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM order_items i WHERE order_id=owner_id),'[]'),
      'charges',coalesce((SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM order_charges c WHERE order_id=owner_id),'[]'),
      'payments',coalesce((SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payments p WHERE order_id=owner_id),'[]'),
      'transactions',coalesce((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.payment_id,t.id) FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.order_id=owner_id),'[]'),
      'operations',coalesce((SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM order_change_operations c WHERE order_id=owner_id),'[]'),
      'histories',coalesce((SELECT jsonb_agg(to_jsonb(h) ORDER BY id) FROM order_content_change_histories h WHERE order_id=owner_id),'[]'),
      'requests',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM order_change_financial_requests r WHERE order_id=owner_id),'[]'),
      'dispatches',coalesce((SELECT jsonb_agg(to_jsonb(d) ORDER BY request_id) FROM order_change_request_dispatches d JOIN order_change_financial_requests r ON r.id=d.request_id WHERE r.order_id=owner_id),'[]'),
      'captures',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM capture_requests x JOIN payments p ON p.id=x.payment_id WHERE p.order_id=owner_id),'[]'),
      'movements',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM stock_movements x WHERE x.order_id=owner_id OR EXISTS(SELECT 1 FROM inventory_reservations ir JOIN order_change_operations op ON op.id=ir.operation_id WHERE op.order_id=owner_id AND x.id IN(ir.hold_movement_id,ir.release_movement_id))),'[]'),
      'historyItems',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM order_content_change_items x JOIN order_content_change_histories h ON h.id=x.history_id WHERE h.order_id=owner_id),'[]'),
      'historyCharges',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM order_content_change_charges x JOIN order_content_change_histories h ON h.id=x.history_id WHERE h.order_id=owner_id),'[]'),
      'changeItems',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.operation_id,x.order_item_id) FROM order_change_items x WHERE x.order_id=owner_id),'[]'),
      'epochs',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM order_payment_evidence_epochs x WHERE x.order_id=owner_id),'[]'),
      'reservations',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.operation_id,r.order_item_id) FROM inventory_reservations r JOIN order_change_operations c ON c.id=r.operation_id WHERE c.order_id=owner_id),'[]'))
$$;
CREATE FUNCTION f125_execution_lock(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f125_require_read_committed();
    PERFORM f125_admission_lock(target);
    PERFORM admission_id FROM order_edit_execution_controls WHERE admission_id=target FOR UPDATE;
END;
$$;
CREATE FUNCTION f125_execution_assert_basis(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; o orders%ROWTYPE; v_proof order_edit_admission_evidences%ROWTYPE; fp BYTEA;
BEGIN
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
    SELECT * INTO STRICT o FROM orders WHERE id=r.order_id;
    SELECT * INTO STRICT v_proof FROM order_edit_admission_evidences WHERE admission_id=target;
    SELECT CASE WHEN r.base_evidence_history_id IS NULL THEN e.start_fingerprint ELSE h.after_fingerprint END INTO fp
        FROM order_payment_evidence_epochs e LEFT JOIN order_content_change_histories h ON h.id=r.base_evidence_history_id AND h.evidence_epoch_id=e.id WHERE e.id=r.base_evidence_epoch_id AND e.order_id=r.order_id;
    PERFORM f125_financial_assert_current(target);
    IF r.admission_state<>'ACCEPTED' OR r.closed_at IS NOT NULL OR r.route<>'RETAIN_AUTH'
        OR ROW(v_proof.decision,v_proof.original_xid,v_proof.db_generation_id,v_proof.request_digest,v_proof.candidate_digest,v_proof.deadline_at)
           IS DISTINCT FROM ROW('ACCEPTED'::text,r.original_xid,r.db_generation_id,r.request_digest,r.candidate_digest,r.deadline_at)
        OR NOT v_proof.original_commit_at<r.deadline_at OR r.request_digest<>sha256(r.request_payload) OR r.candidate_digest<>sha256(r.candidate_payload)
        OR ROW(o.content_revision,o.current_evidence_epoch_id,o.evidence_head_history_id,o.active_payment_id,o.total_amount,fp)
           IS DISTINCT FROM ROW(r.base_content_revision,r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_payment_id,r.before_amount,r.base_fingerprint)
        OR o.status<>'ORDERED' OR o.shipping_preparation_started_at IS NOT NULL OR o.shipment_capture_payment_id IS NOT NULL
        OR o.shipping_preparation_started_by_account_id IS NOT NULL OR o.shipping_preparation_started_by_username IS NOT NULL
        OR EXISTS(SELECT 1 FROM order_change_operations WHERE order_id=r.order_id AND (submission_key=r.submission_key OR state NOT IN ('COMPLETED','ABORTED')))
        OR EXISTS(SELECT 1 FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.order_id=r.order_id AND t.status='PENDING') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Execution basis or unapplied status is unproven';
    END IF;
END;
$$;
CREATE FUNCTION f125_execution_assert_unapplied(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE;
BEGIN
    PERFORM f125_execution_assert_basis(target);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
    IF c.baseline IS DISTINCT FROM f125_execution_local(c.order_id)
        OR (SELECT unapplied_baseline FROM order_edit_admissions WHERE id=target) IS DISTINCT FROM c.baseline THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Local state changed; no unapplied proof may be inferred';
    END IF;
END;
$$;
CREATE FUNCTION f125_execution_initialize(target UUID, automatic_window INTERVAL DEFAULT interval '10 minutes',
    max_attempts INTEGER DEFAULT 5, lease_window INTERVAL DEFAULT interval '60 seconds', retry_window INTERVAL DEFAULT interval '5 seconds')
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; committed TIMESTAMPTZ;
BEGIN
    PERFORM f125_execution_lock(target);
    PERFORM f125_execution_assert_basis(target);
    IF EXISTS(SELECT 1 FROM order_edit_execution_controls WHERE admission_id=target) THEN RETURN; END IF;
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
    IF r.execution_state<>'QUEUED' OR EXISTS(SELECT 1 FROM order_edit_admission_events WHERE admission_id=target AND after_execution IN ('OBSERVING','APPLYING','RECOVERY_PENDING'))
        OR automatic_window IS NULL OR automatic_window<=interval '0' OR automatic_window>interval '1 hour' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Legacy work cannot be enrolled or automatic window is invalid';
    END IF;
    SELECT original_commit_at INTO STRICT committed FROM order_edit_admission_evidences WHERE admission_id=target;
    INSERT INTO order_edit_execution_controls(admission_id,order_id,db_generation_id,automatic_until,max_get_attempts,lease_duration,retry_delay,
        baseline,baseline_digest,next_attempt_at,created_at,updated_at)
    VALUES(target,r.order_id,r.db_generation_id,committed+automatic_window,max_attempts,lease_window,retry_window,
        f125_execution_local(r.order_id),sha256(convert_to(f125_execution_local(r.order_id)::text,'UTF8')),clock_timestamp(),clock_timestamp(),clock_timestamp());
END;
$$;
CREATE FUNCTION f125_execution_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE; r order_edit_admissions%ROWTYPE;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
    PERFORM f125_execution_assert_unapplied(NEW.admission_id);
    IF r.execution_state<>'QUEUED' OR c.active_attempt_id IS NOT NULL OR c.get_attempt_count>=c.max_get_attempts
        OR clock_timestamp()>=c.automatic_until OR clock_timestamp()<c.next_attempt_at
        OR NEW.order_id<>c.order_id OR NEW.db_generation_id<>c.db_generation_id OR NEW.fencing_token<>c.fencing_token+1
        OR NEW.control_version<>c.version+1 OR NEW.recording_xid<>pg_current_xact_id()
        OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=c.admission_id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('REVIEW','UNKNOWN'))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Claim state, ownership, deadline or token conflict';
    END IF;
    NEW.claimed_at=clock_timestamp();NEW.lease_until=NEW.claimed_at+c.lease_duration;NEW.baseline_digest=c.baseline_digest;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_attempt_guard BEFORE INSERT ON order_edit_execution_attempts FOR EACH ROW EXECUTE FUNCTION f125_execution_attempt_guard();
CREATE FUNCTION f125_execution_claim(target UUID, attempt UUID, expected_version BIGINT) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE; a order_edit_execution_attempts%ROWTYPE;
BEGIN
    PERFORM f125_execution_lock(target);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
    IF attempt IS NULL OR expected_version IS NULL OR expected_version<0 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid claim identity'; END IF;
    SELECT * INTO a FROM order_edit_execution_attempts WHERE id=attempt;
    IF FOUND THEN
        IF a.admission_id<>target OR a.control_version-1<>expected_version OR a.db_generation_id<>c.db_generation_id THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Claim identity reused with different request'; END IF;
        PERFORM f125_execution_assert_basis(target);
        RETURN a.id;
    END IF;
    IF c.version<>expected_version THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Execution CAS conflict'; END IF;
    INSERT INTO order_edit_execution_attempts(id,admission_id,order_id,db_generation_id,fencing_token,control_version)
        VALUES(attempt,target,c.order_id,c.db_generation_id,c.fencing_token+1,c.version+1) RETURNING * INTO a;
    UPDATE order_edit_execution_controls SET fencing_token=a.fencing_token,active_attempt_id=a.id,lease_until=a.lease_until,
        get_attempt_count=get_attempt_count+1,version=version+1,updated_at=clock_timestamp() WHERE admission_id=target;
    UPDATE order_edit_admissions SET execution_state='OBSERVING',reason_code='WORKER_CLAIMED',version=version+1 WHERE id=target;
    RETURN a.id;
END;
$$;
CREATE FUNCTION f125_execution_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE; a order_edit_execution_attempts%ROWTYPE; state TEXT;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
    SELECT * INTO STRICT a FROM order_edit_execution_attempts WHERE id=NEW.attempt_id AND admission_id=NEW.admission_id;
    SELECT execution_state INTO STRICT state FROM order_edit_admissions WHERE id=NEW.admission_id;
    PERFORM f125_financial_assert_saved(NEW.admission_id);
    IF NEW.disposition NOT IN ('REVIEW','UNKNOWN') THEN PERFORM f125_execution_assert_unapplied(NEW.admission_id); END IF;
    IF NEW.order_id<>c.order_id OR NEW.control_version<>c.version OR NEW.fencing_token<>a.fencing_token
        OR NEW.recording_xid<>pg_current_xact_id() OR NEW.reason_code IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Attempt result identity/CAS conflict';
    END IF;
    IF NEW.recovery THEN
        IF NOT ((state='OBSERVING' AND NEW.disposition='UNKNOWN' AND c.active_attempt_id=a.id AND c.lease_until<=clock_timestamp())
            OR (state='RECOVERY_PENDING' AND NEW.disposition='REVIEW' AND c.active_attempt_id IS NULL AND c.fencing_token>a.fencing_token)) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Recovery must fence expired/revoked work';
        END IF;
    ELSIF state<>'OBSERVING' OR c.active_attempt_id IS DISTINCT FROM a.id OR c.fencing_token<>a.fencing_token OR c.lease_until<=clock_timestamp() THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worker token/lease expired';
    END IF;
    NEW.local_digest=sha256(convert_to(f125_execution_local(c.order_id)::text,'UTF8'));NEW.recorded_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE TRIGGER execution_result_guard BEFORE INSERT ON order_edit_execution_attempt_results FOR EACH ROW EXECUTE FUNCTION f125_execution_result_guard();
CREATE FUNCTION f125_execution_close_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
    PERFORM f125_execution_assert_unapplied(NEW.admission_id);
    IF c.active_attempt_id IS NOT NULL OR NEW.order_id<>c.order_id OR NEW.db_generation_id<>c.db_generation_id
        OR NEW.invalidated_token<>c.fencing_token+1 OR NEW.control_version<>c.version+1 OR NEW.recording_xid<>pg_current_xact_id()
        OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=c.admission_id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('REVIEW','UNKNOWN'))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved work or invalid closure fence';
    END IF;
    SELECT count(*) INTO NEW.resolved_attempt_count FROM order_edit_execution_attempts WHERE admission_id=c.admission_id;
    NEW.local_digest=c.baseline_digest;NEW.actor_name=session_user;NEW.recorded_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE TRIGGER execution_close_guard BEFORE INSERT ON order_edit_unapplied_evidences FOR EACH ROW EXECUTE FUNCTION f125_execution_close_guard();
CREATE FUNCTION f125_execution_close(target UUID, terminal TEXT, reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE;
BEGIN
    PERFORM f125_execution_lock(target);
 PERFORM f125_closure_lock_related(target);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
    INSERT INTO order_edit_unapplied_evidences(admission_id,order_id,db_generation_id,invalidated_token,control_version,terminal_state,reason_code)
        VALUES(target,c.order_id,c.db_generation_id,c.fencing_token+1,c.version+1,terminal,reason);
    UPDATE order_edit_execution_controls SET fencing_token=fencing_token+1,version=version+1,updated_at=clock_timestamp() WHERE admission_id=target;
    UPDATE order_edit_admissions SET execution_state=terminal,reason_code=reason,version=version+1 WHERE id=target;
END;
$$;
CREATE OR REPLACE FUNCTION f125_admission_close_unapplied(target UUID, reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f125_execution_initialize(target);
    PERFORM f125_execution_close(target,'CLOSED_UNAPPLIED',reason);
END;
$$;
CREATE FUNCTION f125_execution_resolve(target UUID, attempt UUID, token BIGINT, expected_version BIGINT,
    disposition TEXT, reason TEXT, recovering BOOLEAN DEFAULT false, result_id UUID DEFAULT gen_random_uuid()) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE; prior order_edit_execution_attempt_results%ROWTYPE;
BEGIN
    PERFORM f125_execution_lock(target);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
    IF result_id IS NULL OR expected_version IS NULL OR expected_version<0 OR token IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid result identity'; END IF;
    SELECT * INTO prior FROM order_edit_execution_attempt_results WHERE id=result_id;
    IF FOUND THEN
        IF ROW(prior.admission_id,prior.attempt_id,prior.fencing_token,prior.control_version,prior.disposition,prior.reason_code,prior.recovery)
          IS DISTINCT FROM ROW(target,attempt,token,expected_version,disposition,reason,recovering) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Result identity reused with different request'; END IF;
        PERFORM f125_financial_assert_saved(target);
        RETURN;
    END IF;
    IF c.version<>expected_version THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Execution result CAS conflict'; END IF;
    IF disposition='REQUEUE' AND (clock_timestamp()>=c.automatic_until OR c.get_attempt_count>=c.max_get_attempts) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Retry budget exhausted';
    END IF;
    INSERT INTO order_edit_execution_attempt_results(id,attempt_id,admission_id,order_id,fencing_token,control_version,recovery,disposition,reason_code)
        VALUES(result_id,attempt,target,c.order_id,token,c.version,recovering,disposition,reason);
    UPDATE order_edit_execution_controls SET fencing_token=fencing_token+1,active_attempt_id=null,lease_until=null,
        next_attempt_at=clock_timestamp()+retry_delay,version=version+1,updated_at=clock_timestamp() WHERE admission_id=target;
    IF disposition='UNKNOWN' THEN
        UPDATE order_edit_admissions SET execution_state='RECOVERY_PENDING',reason_code=reason,version=version+1 WHERE id=target;
    ELSIF disposition='REVIEW' THEN
        UPDATE order_edit_admissions SET execution_state='REVIEW_REQUIRED',reason_code=reason,version=version+1 WHERE id=target;
    ELSIF disposition='REQUEUE' THEN
        UPDATE order_edit_admissions SET execution_state='QUEUED',reason_code=reason,version=version+1 WHERE id=target;
    ELSE
        PERFORM f125_execution_close(target,CASE WHEN disposition='FAIL' THEN 'FAILED_UNAPPLIED' ELSE 'CLOSED_UNAPPLIED' END,reason);
    END IF;
END;
$$;
CREATE FUNCTION f125_execution_recover(target UUID, expected_version BIGINT, disposition TEXT, reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE a order_edit_execution_attempts%ROWTYPE;
BEGIN
    PERFORM f125_execution_lock(target);
    SELECT * INTO STRICT a FROM order_edit_execution_attempts x WHERE admission_id=target AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=x.id AND r.disposition NOT IN ('REVIEW','UNKNOWN'));
    PERFORM f125_execution_resolve(target,a.id,a.fencing_token,expected_version,disposition,reason,true);
END;
$$;
-- Guard all table paths, including direct SQL. Evidence facts are recomputed by DB, not accepted from JSON/flags.
CREATE FUNCTION f125_execution_control_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; p order_edit_admission_evidences%ROWTYPE;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
    IF TG_OP='INSERT' THEN
        PERFORM f125_execution_assert_basis(NEW.admission_id);
        SELECT * INTO STRICT p FROM order_edit_admission_evidences WHERE admission_id=NEW.admission_id;
        IF NEW.order_id<>r.order_id OR NEW.db_generation_id<>r.db_generation_id OR r.execution_state<>'QUEUED'
            OR NEW.automatic_until<=p.original_commit_at OR NEW.automatic_until>p.original_commit_at+interval '1 hour'
            OR NEW.fencing_token<>0 OR NEW.version<>0 OR NEW.get_attempt_count<>0 OR NEW.active_attempt_id IS NOT NULL
            OR EXISTS(SELECT 1 FROM order_edit_admission_events WHERE admission_id=r.id AND after_execution IN ('OBSERVING','APPLYING','RECOVERY_PENDING')) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid execution enrollment';
        END IF;
        NEW.baseline=f125_execution_local(r.order_id);NEW.baseline_digest=sha256(convert_to(NEW.baseline::text,'UTF8'));
        NEW.next_attempt_at=clock_timestamp();NEW.created_at=clock_timestamp();NEW.updated_at=NEW.created_at;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['fencing_token','active_attempt_id','lease_until','next_attempt_at','get_attempt_count','version','updated_at'])
            IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['fencing_token','active_attempt_id','lease_until','next_attempt_at','get_attempt_count','version','updated_at'])
            OR NEW.version<>OLD.version+1 OR NEW.fencing_token<>OLD.fencing_token+1 OR r.closed_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Immutable policy or invalid control CAS/token';
        END IF;
        IF NEW.active_attempt_id IS NOT NULL THEN
            IF OLD.active_attempt_id IS NOT NULL OR NEW.get_attempt_count<>OLD.get_attempt_count+1
                OR NOT EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.id=NEW.active_attempt_id AND a.admission_id=NEW.admission_id
                    AND a.fencing_token=NEW.fencing_token AND a.control_version=NEW.version AND a.lease_until=NEW.lease_until AND a.recording_xid=pg_current_xact_id()) THEN
                RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Control claim requires its actual attempt';
            END IF;
            NEW.next_attempt_at=OLD.next_attempt_at;
        ELSE
            IF NEW.get_attempt_count<>OLD.get_attempt_count OR NOT (
                EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.admission_id=NEW.admission_id AND x.control_version=OLD.version AND x.recording_xid=pg_current_xact_id())
                OR EXISTS(SELECT 1 FROM order_edit_unapplied_evidences e WHERE e.admission_id=NEW.admission_id AND e.control_version=NEW.version AND e.recording_xid=pg_current_xact_id())) THEN
                RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Token invalidation requires atomic resolution/closure';
            END IF;
            IF EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.admission_id=NEW.admission_id AND x.control_version=OLD.version AND x.recording_xid=pg_current_xact_id()) THEN
                NEW.next_attempt_at=clock_timestamp()+NEW.retry_delay;
            ELSE NEW.next_attempt_at=OLD.next_attempt_at;END IF;
        END IF;
        NEW.updated_at=clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_control_guard BEFORE INSERT OR UPDATE ON order_edit_execution_controls FOR EACH ROW EXECUTE FUNCTION f125_execution_control_guard();
CREATE TRIGGER execution_control_preserve BEFORE DELETE OR TRUNCATE ON order_edit_execution_controls FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE TRIGGER execution_attempt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_edit_execution_attempts FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE TRIGGER execution_result_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_edit_execution_attempt_results FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE TRIGGER execution_close_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_edit_unapplied_evidences FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();

CREATE UNIQUE INDEX uq_execution_attempt_resolution ON order_edit_execution_attempt_results(attempt_id) WHERE disposition NOT IN ('REVIEW','UNKNOWN');
CREATE UNIQUE INDEX uq_execution_attempt_review ON order_edit_execution_attempt_results(attempt_id) WHERE disposition='REVIEW';
CREATE UNIQUE INDEX uq_execution_attempt_unknown ON order_edit_execution_attempt_results(attempt_id) WHERE disposition='UNKNOWN';
CREATE UNIQUE INDEX uq_execution_attempt_version ON order_edit_execution_attempts(admission_id,control_version);
CREATE UNIQUE INDEX uq_execution_result_version ON order_edit_execution_attempt_results(admission_id,control_version);

CREATE OR REPLACE FUNCTION f125_admission_row_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE; e order_payment_evidence_epochs%ROWTYPE; a payment_transactions%ROWTYPE; fp BYTEA;
BEGIN
    PERFORM f125_require_read_committed();
    PERFORM f125_admission_check_runtime(NEW.db_generation_id);
    IF TG_OP='INSERT' THEN
        SELECT * INTO STRICT o FROM orders WHERE id=NEW.order_id;
        SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE id=NEW.base_evidence_epoch_id AND order_id=o.id;
        SELECT * INTO STRICT a FROM payment_transactions WHERE id=NEW.base_authorization_id;
        IF o.evidence_head_history_id IS NULL THEN fp=e.start_fingerprint;
        ELSE SELECT after_fingerprint INTO STRICT fp FROM order_content_change_histories WHERE id=o.evidence_head_history_id AND evidence_epoch_id=e.id; END IF;
        IF ROW(o.user_id,o.content_revision,o.current_evidence_epoch_id,o.evidence_head_history_id,o.active_payment_id,o.total_amount,fp)
            IS DISTINCT FROM ROW(NEW.customer_id,NEW.base_content_revision,NEW.base_evidence_epoch_id,NEW.base_evidence_history_id,NEW.base_payment_id,NEW.before_amount,NEW.base_fingerprint)
            OR ROW(e.payment_id,e.authorization_transaction_id,e.authorized_amount,e.authorization_revision)
            IS DISTINCT FROM ROW(NEW.base_payment_id,NEW.base_authorization_id,NEW.authorized_amount,NEW.authorization_revision)
            OR o.status<>'ORDERED' OR o.shipping_preparation_started_at IS NOT NULL
            OR o.shipping_preparation_started_by_account_id IS NOT NULL OR o.shipping_preparation_started_by_username IS NOT NULL
            OR o.shipment_capture_payment_id IS NOT NULL OR o.change_deadline_at::TIME<>TIME '14:00'
            OR NEW.deadline_at IS DISTINCT FROM (o.change_deadline_at AT TIME ZONE 'Asia/Tokyo')
            OR NEW.original_xid IS DISTINCT FROM pg_current_xact_id()
            OR NEW.admission_state<>'RECORDED_UNVERIFIED' OR NEW.execution_state<>'NOT_READY'
            OR NEW.version<>0 OR NEW.applied_operation_id IS NOT NULL OR NEW.closed_at IS NOT NULL
            OR NEW.reason_code<>'RECORDED' OR a.payment_id<>e.payment_id OR a.transaction_type<>'AUTHORIZE'
            OR a.status<>'SUCCEEDED' OR a.completed_at IS NULL
            OR NOT EXISTS(SELECT 1 FROM payments WHERE id=e.payment_id AND status='AUTHORIZED' AND provider='PAYJP'
                AND payment_method='CARD' AND provider_payment_id=e.provider_flow_id AND amount=e.authorized_amount)
            OR (SELECT count(*) FROM payments WHERE order_id=o.id AND status NOT IN ('FAILED','CANCELLED'))<>1
            OR EXISTS(SELECT 1 FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.order_id=o.id AND t.status='PENDING')
            OR EXISTS(SELECT 1 FROM order_change_operations WHERE order_id=o.id AND state NOT IN ('COMPLETED','ABORTED')) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid or stale admission recording context';
        END IF;
        NEW.actor_username=(SELECT username FROM users WHERE id=o.user_id);
        NEW.created_at=clock_timestamp();NEW.updated_at=NEW.created_at;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['admission_state','execution_state','applied_operation_id','reason_code','version','closed_at','updated_at'])
            IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['admission_state','execution_state','applied_operation_id','reason_code','version','closed_at','updated_at'])
            OR OLD.closed_at IS NOT NULL OR NEW.version<>OLD.version+1 THEN
            RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Admission context or terminal outcome is immutable';
        END IF;
        IF NOT (
            (OLD.admission_state IN ('RECORDED_UNVERIFIED','UNPROVEN') AND NEW.admission_state IN ('ACCEPTED','REJECTED_LATE')
                AND NEW.execution_state=CASE WHEN NEW.admission_state='ACCEPTED' THEN 'QUEUED' ELSE 'NOT_READY' END)
            OR (OLD.admission_state IN ('RECORDED_UNVERIFIED','UNPROVEN') AND NEW.admission_state='UNPROVEN' AND NEW.execution_state='REVIEW_REQUIRED')
            OR (NEW.admission_state=OLD.admission_state AND NEW.execution_state IN ('CLOSED_UNAPPLIED','FAILED_UNAPPLIED')
                AND OLD.execution_state IN ('NOT_READY','QUEUED','OBSERVING','RECOVERY_PENDING','REVIEW_REQUIRED'))
            OR (OLD.admission_state='ACCEPTED' AND NEW.admission_state='ACCEPTED' AND (
                (OLD.execution_state='QUEUED' AND NEW.execution_state='OBSERVING')
                OR (OLD.execution_state IN ('OBSERVING','RECOVERY_PENDING','REVIEW_REQUIRED') AND NEW.execution_state='QUEUED')
                OR (OLD.execution_state='OBSERVING' AND NEW.execution_state IN ('RECOVERY_PENDING','REVIEW_REQUIRED'))
                OR (OLD.execution_state='OBSERVING' AND NEW.execution_state='APPLYING')
                OR (OLD.execution_state='APPLYING' AND NEW.execution_state IN ('COMPLETED','RECOVERY_PENDING','REVIEW_REQUIRED'))
                OR (OLD.execution_state='RECOVERY_PENDING' AND NEW.execution_state='REVIEW_REQUIRED')))
        ) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Illegal admission state transition'; END IF;
        NEW.updated_at=clock_timestamp();
        NEW.closed_at=CASE WHEN NEW.execution_state IN ('FAILED_UNAPPLIED','CLOSED_UNAPPLIED') THEN (SELECT recorded_at FROM order_edit_unapplied_evidences WHERE admission_id=NEW.id) WHEN NEW.execution_state='COMPLETED' OR NEW.admission_state='REJECTED_LATE' THEN NEW.updated_at ELSE NULL END;
    END IF;
    RETURN NEW;
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
            OR EXISTS(SELECT 1 FROM order_change_operations WHERE order_id=r.order_id AND submission_key=r.submission_key) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Gate cannot close with application work or unproven attempts';
        END IF;
    ELSIF r.execution_state='COMPLETED' THEN
        IF NOT EXISTS(SELECT 1 FROM order_change_operations c WHERE c.id=r.applied_operation_id AND c.order_id=r.order_id
            AND c.state='COMPLETED' AND c.execution_version=1 AND c.execution_route='RETAIN_AUTH'
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
CREATE FUNCTION f125_execution_admission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.execution_state IN ('APPLYING','COMPLETED') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='This DB foundation has no application writer';
    END IF;
    -- Validate ownership after the claim's attempt and control writes, without upstream locks.
    -- The lease may later expire while OBSERVING remains persisted; only a new transition
    -- into OBSERVING must prove a live lease. Deferred checks validate stored correspondence.
    IF NEW.execution_state='OBSERVING' THEN
        IF NOT EXISTS(SELECT 1 FROM order_edit_execution_controls c
            JOIN order_edit_execution_attempts a ON a.id=c.active_attempt_id AND a.admission_id=c.admission_id
            WHERE c.admission_id=NEW.id AND c.order_id=NEW.order_id AND c.db_generation_id=NEW.db_generation_id
              AND a.order_id=c.order_id AND a.db_generation_id=c.db_generation_id
              AND a.fencing_token=c.fencing_token AND c.fencing_token>0
              AND a.control_version=c.version AND a.lease_until=c.lease_until
              AND a.baseline_digest=c.baseline_digest AND c.lease_until>clock_timestamp()
              AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id)) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Observing requires matching live execution ownership';
        END IF;
    END IF;
    IF OLD.execution_state IN ('REVIEW_REQUIRED','RECOVERY_PENDING') AND NEW.execution_state='QUEUED' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Uncertain work requires future explicit recovery evidence, not automatic requeue'; END IF;
    IF NEW.execution_state='QUEUED' AND OLD.execution_state='OBSERVING' THEN
        IF NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.admission_id=NEW.id AND x.disposition='REQUEUE' AND x.recording_xid=pg_current_xact_id()) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Requeue requires atomic verified resolution';
        END IF;
    END IF;
    IF OLD.execution_state IN ('OBSERVING','RECOVERY_PENDING') AND NEW.execution_state IN ('REVIEW_REQUIRED','RECOVERY_PENDING') THEN
        IF NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.admission_id=NEW.id AND x.disposition=CASE WHEN NEW.execution_state='RECOVERY_PENDING' THEN 'UNKNOWN' ELSE 'REVIEW' END AND x.reason_code=NEW.reason_code AND x.recording_xid=pg_current_xact_id()) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Suspension requires atomic revocation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_admission_guard BEFORE UPDATE ON order_edit_admissions FOR EACH ROW EXECUTE FUNCTION f125_execution_admission_guard();
CREATE FUNCTION f125_execution_final_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; c order_edit_execution_controls%ROWTYPE; r order_edit_admissions%ROWTYPE; na BIGINT; nr BIGINT; ne BIGINT; unresolved BIGINT;
BEGIN
    IF TG_TABLE_NAME='order_edit_admissions' THEN target=NEW.id;ELSE target=NEW.admission_id;END IF;
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
    IF r.execution_state IN ('APPLYING','COMPLETED') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='This DB foundation has no application writer';
    END IF;
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
        AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('REVIEW','UNKNOWN'));
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
CREATE CONSTRAINT TRIGGER execution_admission_final AFTER INSERT OR UPDATE ON order_edit_admissions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_execution_final_check();
CREATE CONSTRAINT TRIGGER execution_control_final AFTER INSERT OR UPDATE ON order_edit_execution_controls
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_execution_final_check();
CREATE CONSTRAINT TRIGGER execution_attempt_final AFTER INSERT ON order_edit_execution_attempts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_execution_final_check();
CREATE CONSTRAINT TRIGGER execution_result_final AFTER INSERT ON order_edit_execution_attempt_results
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_execution_final_check();
CREATE CONSTRAINT TRIGGER execution_close_final AFTER INSERT ON order_edit_unapplied_evidences
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_execution_final_check();
CREATE OR REPLACE FUNCTION f125_admission_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; verification XID8; original NUMERIC; current_id NUMERIC;
    observed TIMESTAMPTZ; state_before TEXT; state_after TEXT;
BEGIN
    PERFORM f125_require_read_committed();
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
    PERFORM f125_admission_check_runtime(r.db_generation_id);
    IF r.admission_state NOT IN ('RECORDED_UNVERIFIED','UNPROVEN') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Admission already decided';
    END IF;
    verification=pg_current_xact_id();original=r.original_xid::TEXT::NUMERIC;current_id=verification::TEXT::NUMERIC;
    -- Separate procedural guard BEFORE any xid8 -> xid narrowing, never a reorderable SQL AND.
    IF mod(original,4294967296)<3 OR mod(current_id,4294967296)<3
        OR trunc(original/4294967296)<>trunc(current_id/4294967296)
        OR original>=current_id OR current_id-original>=2147483648 THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unsafe full transaction generation/order/distance';
    END IF;
    state_before=pg_xact_status(r.original_xid);
    IF state_before IS DISTINCT FROM 'committed' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Original transaction commit is unproven';
    END IF;
    observed=pg_xact_commit_timestamp(r.original_xid::XID);
    state_after=pg_xact_status(r.original_xid);
    IF observed IS NULL OR NOT isfinite(observed) OR state_after IS DISTINCT FROM 'committed' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Original commit timestamp is unproven';
    END IF;
    IF NEW.original_commit_at IS NOT NULL AND NEW.original_commit_at IS DISTINCT FROM observed THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Caller timestamp does not match database evidence';
    END IF;
    NEW.original_xid=r.original_xid;NEW.verification_xid=verification;NEW.original_commit_at=observed;
    NEW.deadline_at=r.deadline_at;NEW.db_generation_id=r.db_generation_id;NEW.scope=r.scope;
    NEW.request_digest=r.request_digest;NEW.candidate_digest=r.candidate_digest;
    NEW.status_before=state_before;NEW.status_after=state_after;NEW.guard_version=1;NEW.verified_at=clock_timestamp();
    NEW.decision=CASE WHEN f125_admission_before_deadline(observed,r.deadline_at) THEN 'ACCEPTED' ELSE 'REJECTED_LATE' END;
    RETURN NEW;
END;
$$;

-- Admission-time financial snapshot V1. No historical backfill; new INSERTs only.
ALTER TABLE order_edit_admissions
 ADD COLUMN financial_snapshot_version SMALLINT,
 ADD COLUMN financial_snapshot JSONB,
 ADD COLUMN financial_canonical BYTEA,
 ADD COLUMN financial_digest BYTEA;
ALTER TABLE order_edit_admission_evidences
 ADD COLUMN financial_binding JSONB,
 ADD COLUMN financial_binding_canonical BYTEA,
 ADD COLUMN financial_binding_digest BYTEA;

-- Framing: 00 for null, 01 + int32 length + bytes otherwise. No JSON serialization input.
CREATE FUNCTION f125_financial_frame(value BYTEA) RETURNS BYTEA LANGUAGE SQL IMMUTABLE AS $$
 SELECT CASE WHEN value IS NULL THEN decode('00','hex') ELSE decode('01','hex')||int4send(octet_length(value))||value END
$$;
CREATE FUNCTION f125_financial_micros(value TIMESTAMPTZ) RETURNS BIGINT LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT (extract(epoch FROM value)*1000000)::BIGINT
$$;
CREATE FUNCTION f125_financial_encode(doc JSONB, kind TEXT DEFAULT 'financial') RETURNS BYTEA LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE spec TEXT; item TEXT; field TEXT; typ TEXT; optional BOOLEAN; val JSONB; raw BYTEA; result BYTEA='';
 element JSONB; count_rows INTEGER; number NUMERIC; previous BYTEA; sort_key BYTEA; scalar TEXT;
BEGIN
 CASE kind
 WHEN 'financial' THEN spec='domain:s version:i admissionId:u orderId:l customerId:l dbGeneration:u originalXid:x requestDigest:h candidateDigest:h revision:i status:s deadline:t epoch:u head:l? fingerprint:h epochDigest:h headDigest:h activePayment:l orderAmount:i payment:l authorization:l authorizedAmount:i authorizationRevision:i payments:[payment] transactions:[transaction] operations:[operation] requests:[request] dispatches:[dispatch] captures:[capture]';
 WHEN 'payment' THEN spec='id:l orderId:l provider:s method:s status:s amount:i providerId:s?';
 WHEN 'transaction' THEN spec='id:l paymentId:l type:s status:s amount:i revision:i purpose:s? operation:u? providerId:s? completed:n?';
 WHEN 'operation' THEN spec='id:u orderId:l state:s version:l baseRevision:i oldPayment:l newPayment:l? beforeAmount:i afterAmount:i switchDecided:n? history:l? executionVersion:i? route:s?';
 WHEN 'request' THEN spec='id:l operation:u orderId:l payment:l transaction:l purpose:s kind:s type:s amount:i flow:s? idempotency:s connection:s account:s mode:s origin:s method:s path:s contentType:s bodyVersion:i bodyDigest:h fingerprintVersion:i fingerprint:h created:t replayUntil:t';
 WHEN 'dispatch' THEN spec='request:l state:s created:t started:t? observed:t?';
 WHEN 'capture' THEN spec='id:l payment:l transaction:l type:s provider:s method:s flow:s connection:s account:s mode:s origin:s httpMethod:s path:s contentType:s amount:i revision:i idempotency:s bodyVersion:i bodyDigest:h fingerprintVersion:i fingerprint:h created:t replayUntil:t';
 WHEN 'binding' THEN spec='domain:s version:i admissionId:u orderId:l dbGeneration:u originalXid:x verificationXid:x requestDigest:h candidateDigest:h snapshotVersion:i snapshotDigest:h originalCommit:t deadline:t statusBefore:s statusAfter:s decision:s guardVersion:i';
 ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unknown financial record kind'; END CASE;
 -- JSON text is used only for a bounded audit-size check, never canonical bytes.
 IF doc IS NULL OR octet_length(convert_to(doc::text,'UTF8'))>4000000 OR jsonb_typeof(doc)<>'object' OR
   (SELECT count(*) FROM jsonb_object_keys(doc))<>array_length(string_to_array(spec,' '),1) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid financial record shape'; END IF;
 FOREACH item IN ARRAY string_to_array(spec,' ') LOOP
   field=split_part(item,':',1);typ=split_part(item,':',2);optional=right(typ,1)='?';
   IF optional THEN typ=left(typ,length(typ)-1); END IF;
   IF NOT doc ? field THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing financial field'; END IF;
   val=doc->field;raw=NULL;
   IF val='null'::jsonb THEN
     IF NOT optional THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Required financial field is null'; END IF;
   ELSIF left(typ,1)='[' THEN
     IF jsonb_typeof(val)<>'array' OR jsonb_array_length(val)>1000 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid financial array'; END IF;
     count_rows=jsonb_array_length(val);raw=int4send(count_rows);previous=NULL;
     FOR element IN SELECT value FROM jsonb_array_elements(val) LOOP
       IF typ='[operation]' THEN sort_key=uuid_send((element->>'id')::uuid);
       ELSIF typ='[transaction]' THEN sort_key=int8send((element->>'paymentId')::bigint)||int8send((element->>'id')::bigint);
       ELSIF typ='[dispatch]' THEN sort_key=int8send((element->>'request')::bigint);
       ELSE sort_key=int8send((element->>'id')::bigint); END IF;
       IF previous IS NOT NULL AND previous>=sort_key THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial array order or duplicate invalid'; END IF;
       previous=sort_key;
       raw=raw||f125_financial_frame(f125_financial_encode(element,substring(typ,2,length(typ)-2)));
     END LOOP;
   ELSE
     scalar=doc->>field;
     IF typ IN ('i','l','t') THEN
       IF jsonb_typeof(val)<>'number' OR scalar !~ '^-?(0|[1-9][0-9]*)$' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial integer required'; END IF;
       IF typ='i' THEN raw=int4send(scalar::integer); ELSE raw=int8send(scalar::bigint); END IF;
     ELSE
       IF jsonb_typeof(val)<>'string' OR octet_length(convert_to(scalar,'UTF8'))>8000 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial string required'; END IF;
       CASE typ
       WHEN 'u' THEN
         IF scalar !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid UUID'; END IF;
         raw=uuid_send(scalar::uuid);
       WHEN 'x' THEN
         IF scalar !~ '^(0|[1-9][0-9]{0,19})$' OR scalar::numeric>18446744073709551615 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid full XID'; END IF;
         number=scalar::numeric;
         raw=int4send((div(number,4294967296)-CASE WHEN div(number,4294967296)>=2147483648 THEN 4294967296 ELSE 0 END)::integer)
          ||int4send((mod(number,4294967296)-CASE WHEN mod(number,4294967296)>=2147483648 THEN 4294967296 ELSE 0 END)::integer);
       WHEN 'h' THEN
         IF scalar !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid financial hash'; END IF;raw=decode(scalar,'hex');
       WHEN 'n' THEN
         IF scalar !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}$'
            OR to_char(scalar::timestamp,'YYYY-MM-DD"T"HH24:MI:SS.US')<>scalar THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid saved local timestamp'; END IF;
         raw=convert_to(scalar,'UTF8');
       WHEN 's' THEN raw=convert_to(scalar,'UTF8');
       ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unknown financial type'; END CASE;
     END IF;
   END IF;
   result=result||f125_financial_frame(raw);
 END LOOP;
 IF kind IN ('financial','binding') AND (doc->>'version'<>'1' OR doc->>'domain'<>
    CASE WHEN kind='financial' THEN 'ec-site/order-admission/financial/v1' ELSE 'ec-site/order-admission/commit-binding/v1' END) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unknown financial domain/version'; END IF;
 RETURN result;
END;
$$;

-- Explicit saved facts only. No row-wide to_jsonb and no catalog-price reconstruction.
CREATE FUNCTION f125_financial_build(r order_edit_admissions) RETURNS JSONB LANGUAGE SQL STABLE AS $$
 SELECT jsonb_build_object(
 'domain','ec-site/order-admission/financial/v1','version',1,
 'admissionId',r.id,'orderId',r.order_id,'customerId',r.customer_id,'dbGeneration',r.db_generation_id,'originalXid',r.original_xid::text,
 'requestDigest',encode(r.request_digest,'hex'),'candidateDigest',encode(r.candidate_digest,'hex'),
 'revision',o.content_revision,'status',o.status,'deadline',f125_financial_micros(o.change_deadline_at AT TIME ZONE 'Asia/Tokyo'),
 'epoch',o.current_evidence_epoch_id,'head',o.evidence_head_history_id,
 'fingerprint',encode(CASE WHEN o.evidence_head_history_id IS NULL THEN e.start_fingerprint ELSE h.after_fingerprint END,'hex'),
 'epochDigest',encode(e.evidence_digest,'hex'),'headDigest',encode(CASE WHEN o.evidence_head_history_id IS NULL THEN e.evidence_digest ELSE h.evidence_digest END,'hex'),
 'activePayment',o.active_payment_id,'orderAmount',o.total_amount,
 'payment',e.payment_id,'authorization',e.authorization_transaction_id,'authorizedAmount',e.authorized_amount,'authorizationRevision',e.authorization_revision,
 'payments',coalesce((SELECT jsonb_agg(jsonb_build_object(
 'id',p.id,'orderId',p.order_id,'provider',p.provider,'method',p.payment_method,'status',p.status,'amount',p.amount,'providerId',p.provider_payment_id) ORDER BY p.id)
 FROM payments p WHERE p.order_id=o.id),'[]'::jsonb),
 'transactions',coalesce((SELECT jsonb_agg(jsonb_build_object(
 'id',t.id,'paymentId',t.payment_id,'type',t.transaction_type,'status',t.status,'amount',t.amount,'revision',t.order_content_revision,
 'purpose',t.purpose,'operation',t.order_change_operation_id,'providerId',t.provider_transaction_id,'completed',to_char(t.completed_at,'YYYY-MM-DD"T"HH24:MI:SS.US')) ORDER BY t.payment_id,t.id)
 FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.order_id=o.id),'[]'::jsonb),
 'operations',coalesce((SELECT jsonb_agg(jsonb_build_object(
 'id',c.id,'orderId',c.order_id,'state',c.state,'version',c.version,'baseRevision',c.base_content_revision,'oldPayment',c.old_payment_id,'newPayment',c.new_payment_id,
 'beforeAmount',c.before_amount,'afterAmount',c.after_amount,'switchDecided',to_char(c.switch_decided_at,'YYYY-MM-DD"T"HH24:MI:SS.US'),
 'history',c.content_change_history_id,'executionVersion',c.execution_version,'route',c.execution_route) ORDER BY c.id)
 FROM order_change_operations c WHERE c.order_id=o.id),'[]'::jsonb),
 'requests',coalesce((SELECT jsonb_agg(jsonb_build_object(
 'id',q.id,'operation',q.operation_id,'orderId',q.order_id,'payment',q.payment_id,'transaction',q.transaction_id,'purpose',q.purpose,
 'kind',q.request_kind,'type',q.transaction_type,'amount',q.amount,'flow',q.provider_flow_id,'idempotency',q.idempotency_key,
 'connection',q.connection_id,'account',q.provider_account_ref,'mode',q.mode,'origin',q.api_origin,'method',q.http_method,'path',q.api_path,
 'contentType',q.content_type,'bodyVersion',q.body_format_version,'bodyDigest',encode(sha256(q.request_body),'hex'),
 'fingerprintVersion',q.fingerprint_version,'fingerprint',encode(q.request_fingerprint,'hex'),
 'created',f125_financial_micros(q.created_at),'replayUntil',f125_financial_micros(q.replay_not_after)) ORDER BY q.id)
 FROM order_change_financial_requests q WHERE q.order_id=o.id),'[]'::jsonb),
 'dispatches',coalesce((SELECT jsonb_agg(jsonb_build_object(
 'request',d.request_id,'state',d.state,'created',f125_financial_micros(d.created_at),'started',f125_financial_micros(d.started_at),'observed',f125_financial_micros(d.observed_at)) ORDER BY d.request_id)
 FROM order_change_request_dispatches d JOIN order_change_financial_requests q ON q.id=d.request_id WHERE q.order_id=o.id),'[]'::jsonb),
 'captures',coalesce((SELECT jsonb_agg(jsonb_build_object(
 'id',q.id,'payment',q.payment_id,'transaction',q.capture_transaction_id,'type',q.transaction_type,'provider',q.provider,'method',q.payment_method,
 'flow',q.provider_flow_id,'connection',q.connection_id,'account',q.provider_account_ref,'mode',q.mode,'origin',q.api_origin,'httpMethod',q.http_method,'path',q.api_path,
 'contentType',q.content_type,'amount',q.amount_to_capture,'revision',q.order_content_revision,'idempotency',q.idempotency_key,
 'bodyVersion',q.body_format_version,'bodyDigest',encode(sha256(q.request_body),'hex'),'fingerprintVersion',q.fingerprint_version,
 'fingerprint',encode(q.request_fingerprint,'hex'),'created',f125_financial_micros(q.created_at),'replayUntil',f125_financial_micros(q.replay_not_after)) ORDER BY q.id)
 FROM capture_requests q JOIN payments p ON p.id=q.payment_id WHERE p.order_id=o.id),'[]'::jsonb))
 FROM orders o JOIN order_payment_evidence_epochs e ON e.id=o.current_evidence_epoch_id AND e.order_id=o.id
 LEFT JOIN order_content_change_histories h ON h.id=o.evidence_head_history_id AND h.evidence_epoch_id=e.id AND h.order_id=o.id WHERE o.id=r.order_id
$$;
CREATE FUNCTION f125_financial_capture() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.financial_snapshot_version IS NOT NULL OR NEW.financial_snapshot IS NOT NULL OR NEW.financial_canonical IS NOT NULL OR NEW.financial_digest IS NOT NULL THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial snapshot must be generated by database'; END IF;
 NEW.financial_snapshot_version=1;NEW.financial_snapshot=f125_financial_build(NEW);
 NEW.financial_canonical=f125_financial_encode(NEW.financial_snapshot);NEW.financial_digest=sha256(NEW.financial_canonical);
 RETURN NEW;
END;
$$;
CREATE TRIGGER zz_f125_financial_capture BEFORE INSERT ON order_edit_admissions FOR EACH ROW EXECUTE FUNCTION f125_financial_capture();
ALTER TABLE order_edit_admissions ADD CONSTRAINT ck_f125_financial_shape CHECK (
 (financial_snapshot_version IS NULL AND financial_snapshot IS NULL AND financial_canonical IS NULL AND financial_digest IS NULL) OR
 (financial_snapshot_version IS NOT NULL AND financial_snapshot_version=1 AND financial_snapshot IS NOT NULL AND financial_canonical IS NOT NULL AND financial_digest IS NOT NULL
 AND financial_canonical=f125_financial_encode(financial_snapshot) AND financial_digest=sha256(financial_canonical)));

CREATE FUNCTION f125_financial_binding_build(r order_edit_admissions,p order_edit_admission_evidences) RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $$
 SELECT jsonb_build_object('domain','ec-site/order-admission/commit-binding/v1','version',1,
 'admissionId',r.id,'orderId',r.order_id,'dbGeneration',r.db_generation_id,'originalXid',r.original_xid::text,'verificationXid',p.verification_xid::text,
 'requestDigest',encode(r.request_digest,'hex'),'candidateDigest',encode(r.candidate_digest,'hex'),
 'snapshotVersion',r.financial_snapshot_version,'snapshotDigest',encode(r.financial_digest,'hex'),
 'originalCommit',f125_financial_micros(p.original_commit_at),'deadline',f125_financial_micros(p.deadline_at),
 'statusBefore',p.status_before,'statusAfter',p.status_after,'decision',p.decision,'guardVersion',p.guard_version)
$$;
CREATE FUNCTION f125_financial_bind() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
 IF NEW.financial_binding IS NOT NULL OR NEW.financial_binding_canonical IS NOT NULL OR NEW.financial_binding_digest IS NOT NULL THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial binding must be generated by database'; END IF;
 IF r.financial_snapshot_version IS NOT NULL THEN
  NEW.financial_binding=f125_financial_binding_build(r,NEW);
  NEW.financial_binding_canonical=f125_financial_encode(NEW.financial_binding,'binding');
  NEW.financial_binding_digest=sha256(NEW.financial_binding_canonical);
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER zz_f125_financial_bind BEFORE INSERT ON order_edit_admission_evidences FOR EACH ROW EXECUTE FUNCTION f125_financial_bind();
ALTER TABLE order_edit_admission_evidences ADD CONSTRAINT ck_f125_financial_binding_shape CHECK (
 (financial_binding IS NULL AND financial_binding_canonical IS NULL AND financial_binding_digest IS NULL) OR
 (financial_binding IS NOT NULL AND financial_binding_canonical IS NOT NULL AND financial_binding_digest IS NOT NULL
 AND financial_binding_canonical=f125_financial_encode(financial_binding,'binding') AND financial_binding_digest=sha256(financial_binding_canonical)));

-- Never substitutes today's state for an old receipt without this explicit V1 basis.
CREATE FUNCTION f125_financial_assert_current(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 PERFORM f125_financial_assert_saved(target);
 IF r.financial_snapshot_version IS DISTINCT FROM 1 OR r.financial_snapshot IS DISTINCT FROM f125_financial_build(r)
  OR r.financial_canonical IS DISTINCT FROM f125_financial_encode(r.financial_snapshot)
  OR r.financial_digest IS DISTINCT FROM sha256(r.financial_canonical) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Admission financial basis missing or changed'; END IF;
END;
$$;
CREATE FUNCTION f125_financial_final_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; p order_edit_admission_evidences%ROWTYPE; target UUID;
BEGIN
 IF TG_TABLE_NAME='order_edit_admissions' THEN target=NEW.id; ELSE target=NEW.admission_id; END IF;
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO p FROM order_edit_admission_evidences WHERE admission_id=target;
 IF r.financial_snapshot_version IS NOT NULL THEN
   IF (r.financial_snapshot->>'admissionId')::uuid IS DISTINCT FROM r.id
     OR (r.financial_snapshot->>'orderId')::bigint IS DISTINCT FROM r.order_id
     OR (r.financial_snapshot->>'customerId')::bigint IS DISTINCT FROM r.customer_id
     OR (r.financial_snapshot->>'dbGeneration')::uuid IS DISTINCT FROM r.db_generation_id
     OR (r.financial_snapshot->>'originalXid')::xid8 IS DISTINCT FROM r.original_xid
     OR r.financial_snapshot->>'requestDigest' IS DISTINCT FROM encode(r.request_digest,'hex')
     OR r.financial_snapshot->>'candidateDigest' IS DISTINCT FROM encode(r.candidate_digest,'hex')
     OR (r.financial_snapshot->>'revision')::int IS DISTINCT FROM r.base_content_revision
     OR (r.financial_snapshot->>'epoch')::uuid IS DISTINCT FROM r.base_evidence_epoch_id
     OR (r.financial_snapshot->>'head')::bigint IS DISTINCT FROM r.base_evidence_history_id
     OR (r.financial_snapshot->>'payment')::bigint IS DISTINCT FROM r.base_payment_id
     OR (r.financial_snapshot->>'authorization')::bigint IS DISTINCT FROM r.base_authorization_id
     OR (r.financial_snapshot->>'authorizedAmount')::int IS DISTINCT FROM r.authorized_amount
     OR (r.financial_snapshot->>'authorizationRevision')::int IS DISTINCT FROM r.authorization_revision
     OR (r.financial_snapshot->>'orderAmount')::int IS DISTINCT FROM r.before_amount
     OR r.financial_snapshot->>'fingerprint' IS DISTINCT FROM encode(r.base_fingerprint,'hex')
     OR (r.financial_snapshot->>'deadline')::bigint IS DISTINCT FROM f125_financial_micros(r.deadline_at) THEN
       RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial snapshot receipt binding mismatch'; END IF;
   IF p.admission_id IS NOT NULL AND (p.financial_binding IS DISTINCT FROM f125_financial_binding_build(r,p)) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Financial snapshot commit binding missing or invalid'; END IF;
 ELSE
   IF p.financial_binding IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Legacy receipt cannot invent financial binding'; END IF;
 END IF;
 RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f125_financial_receipt_check AFTER INSERT OR UPDATE ON order_edit_admissions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_financial_final_check();
CREATE CONSTRAINT TRIGGER f125_financial_proof_check AFTER INSERT ON order_edit_admission_evidences DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_financial_final_check();

-- Recovery only fences an expired worker and parks its unresolved attempt. It never retries work.
CREATE FUNCTION f125_execution_suspend_expired(target UUID,result_id UUID,expected_version BIGINT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE a order_edit_execution_attempts%ROWTYPE;
BEGIN
    PERFORM f125_execution_lock(target);
    SELECT * INTO STRICT a FROM order_edit_execution_attempts x WHERE admission_id=target AND
        NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=x.id AND r.disposition NOT IN ('REVIEW','UNKNOWN'));
    PERFORM f125_execution_resolve(target,a.id,a.fencing_token,expected_version,'UNKNOWN','EXPIRED_WORKER_UNCERTAIN',true,result_id);
END;
$$;

-- The saved snapshot and its real Tx-B binding are a prerequisite for execution, not a current-state baseline.
CREATE FUNCTION f125_financial_assert_saved(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; p order_edit_admission_evidences%ROWTYPE;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO p FROM order_edit_admission_evidences WHERE admission_id=target;
 IF r.financial_snapshot_version IS DISTINCT FROM 1 OR p.admission_id IS NULL THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Execution requires complete admission snapshot and commit binding'; END IF;
 IF r.financial_canonical IS DISTINCT FROM f125_financial_encode(r.financial_snapshot)
  OR r.financial_digest IS DISTINCT FROM sha256(r.financial_canonical)
  OR p.financial_binding IS DISTINCT FROM f125_financial_binding_build(r,p)
  OR p.financial_binding_canonical IS DISTINCT FROM f125_financial_encode(p.financial_binding,'binding')
  OR p.financial_binding_digest IS DISTINCT FROM sha256(p.financial_binding_canonical)
  OR ROW(p.original_xid,p.db_generation_id,p.request_digest,p.candidate_digest,p.deadline_at,p.decision,p.guard_version,p.status_before,p.status_after)
   IS DISTINCT FROM ROW(r.original_xid,r.db_generation_id,r.request_digest,r.candidate_digest,r.deadline_at,'ACCEPTED'::text,1::smallint,'committed'::text,'committed'::text)
  OR p.verification_xid IS NULL OR p.verification_xid=r.original_xid OR NOT p.original_commit_at<r.deadline_at THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Execution admission snapshot or commit binding is inconsistent'; END IF;
END;
$$;
-- Same schema pinning and invoker-rights boundary as V71. No activation or owner impersonation.
DO $$
DECLARE routine RECORD; schema_name TEXT=current_schema();
BEGIN
    FOR routine IN SELECT oid::regprocedure AS signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
        AND (proname LIKE 'f125_execution_%' OR proname LIKE 'f125_financial_%' OR proname IN ('f125_admission_row_guard','f125_admission_evidence_guard','f125_admission_check_runtime','f125_admission_final_check','f125_admission_close_unapplied')) LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',routine.signature,schema_name);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',routine.signature);
    END LOOP;
END;
$$;
REVOKE ALL ON order_edit_execution_controls,order_edit_execution_attempts,order_edit_execution_attempt_results,order_edit_unapplied_evidences FROM PUBLIC;

-- V1 closure canonical encoding: typed recursive values, not JSONB textual serialization.
-- null=00, boolean=01+00/01, string=02+int32 length+UTF8, decimal=03+length+plain decimal,
-- array=04+int32 count+values, object=05+int32 count+(framed UTF8 key,value), keys UTF8/C sorted.
-- Closure V1 limits shared with OrderUnappliedClosureCodec: raw audit UTF-8 <=4 MiB,
-- canonical <=2 MiB; at most 1000 raw and expanded decimal digits, exponent 1..3 digits.
-- Depth counts array/object containers only: root container=1, max=64 (scalar root=0).
-- TEXT validation precedes JSONB conversion so duplicate keys and numeric spelling are not lost.
CREATE FUNCTION f125_closure_audit_guard(doc JSON, depth INTEGER DEFAULT 0) RETURNS VOID LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE kind TEXT; entry RECORD; child JSON; token TEXT; exponent TEXT; plain TEXT;
BEGIN
 IF doc IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing closure audit'; END IF;
 kind=json_typeof(doc);
 IF kind IN ('array','object') AND depth>=64 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure container depth exceeded'; END IF;
 CASE kind
 WHEN 'array' THEN
  FOR child IN SELECT x.v FROM json_array_elements(doc) x(v) LOOP PERFORM f125_closure_audit_guard(child,depth+1); END LOOP;
 WHEN 'object' THEN
  IF (SELECT count(*)<>count(DISTINCT x.key COLLATE "C") FROM json_each(doc) x) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Duplicate closure audit key'; END IF;
  FOR entry IN SELECT x.value FROM json_each(doc) x LOOP PERFORM f125_closure_audit_guard(entry.value,depth+1); END LOOP;
 WHEN 'number' THEN
  token=btrim(doc::text,E' \t\r\n');
  exponent=substring(token FROM '[eE](.*)$');
  IF length(regexp_replace(token,'[^0-9]','','g'))>1000 OR (exponent IS NOT NULL AND exponent !~ '^[+-]?[0-9]{1,3}$') THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure raw numeric limit exceeded'; END IF;
  plain=trim_scale(token::numeric)::text;
  IF length(regexp_replace(plain,'[^0-9]','','g'))>1000 THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure expanded numeric limit exceeded'; END IF;
 ELSE NULL;
 END CASE;
END;
$$;
CREATE FUNCTION f125_closure_encode(doc JSONB, depth INTEGER DEFAULT 0) RETURNS BYTEA LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result BYTEA; entry RECORD; child JSONB; txt TEXT;
BEGIN
 IF doc IS NULL OR (jsonb_typeof(doc) IN ('array','object') AND depth>=64) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid closure value or depth'; END IF;
 CASE jsonb_typeof(doc)
 WHEN 'null' THEN result=decode('00','hex');
 WHEN 'boolean' THEN result=decode(CASE WHEN doc='true'::jsonb THEN '0101' ELSE '0100' END,'hex');
 WHEN 'string' THEN txt=doc#>>'{}';result=decode('02','hex')||int4send(octet_length(convert_to(txt,'UTF8')))||convert_to(txt,'UTF8');
 WHEN 'number' THEN
  txt=trim_scale((doc#>>'{}')::numeric)::text;
  IF length(regexp_replace(txt,'[^0-9]','','g'))>1000 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure expanded numeric limit exceeded'; END IF;
  result=decode('03','hex')||int4send(octet_length(txt))||convert_to(txt,'UTF8');
 WHEN 'array' THEN
  result=decode('04','hex')||int4send(jsonb_array_length(doc));
  FOR child IN SELECT x.v FROM jsonb_array_elements(doc) x(v) LOOP
   result=result||f125_closure_encode(child,depth+1);
   IF octet_length(result)>2097152 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure canonical exceeds 2 MiB'; END IF;
  END LOOP;
 WHEN 'object' THEN
  result=decode('05','hex')||int4send((SELECT count(*)::int FROM jsonb_object_keys(doc)));
  FOR entry IN SELECT j.key,j.value FROM jsonb_each(doc) j ORDER BY j.key COLLATE "C" LOOP
   result=result||int4send(octet_length(convert_to(entry.key,'UTF8')))||convert_to(entry.key,'UTF8')||f125_closure_encode(entry.value,depth+1);
   IF octet_length(result)>2097152 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure canonical exceeds 2 MiB'; END IF;
  END LOOP;
 ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unsupported closure value';
 END CASE;
 -- Includes scalars, which previously returned before the limit check.
 IF octet_length(result)>2097152 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure canonical exceeds 2 MiB'; END IF;
 RETURN result;
END;
$$;
CREATE FUNCTION f125_closure_canonical(audit TEXT) RETURNS BYTEA LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
 IF audit IS NULL OR octet_length(convert_to(audit,'UTF8'))>4194304 THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure audit exceeds 4 MiB or is missing'; END IF;
 PERFORM f125_closure_audit_guard(audit::json);
 RETURN f125_closure_encode(audit::jsonb);
EXCEPTION WHEN data_exception THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid closure audit JSON or number';
END;
$$;
-- Compatibility entry for an already normalized JSONB document. Raw TEXT callers use
-- f125_closure_canonical; JSONB cannot retain original spelling or duplicate keys.
CREATE FUNCTION f125_closure_value(doc JSONB, depth INTEGER DEFAULT 0) RETURNS BYTEA LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
 IF depth IS DISTINCT FROM 0 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure encoding must start at root'; END IF;
 RETURN f125_closure_canonical(doc::text);
END;
$$;
ALTER FUNCTION f125_execution_local(BIGINT) SET timezone='UTC';
ALTER FUNCTION f125_execution_local(BIGINT) SET datestyle='ISO, YMD';
ALTER TABLE order_edit_admissions ADD COLUMN unapplied_baseline JSONB;
CREATE FUNCTION f125_closure_admission_baseline() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.unapplied_baseline IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Admission business baseline is DB generated'; END IF;
 NEW.unapplied_baseline=f125_execution_local(NEW.order_id);RETURN NEW;
END;
$$;
CREATE TRIGGER zzz_f125_closure_baseline BEFORE INSERT ON order_edit_admissions FOR EACH ROW EXECUTE FUNCTION f125_closure_admission_baseline();
ALTER TABLE order_edit_unapplied_evidences
 ADD COLUMN closure_request_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
 ADD COLUMN request_version BIGINT NOT NULL DEFAULT 0 CHECK(request_version>=0),
 ADD COLUMN evidence_version SMALLINT NOT NULL DEFAULT 1 CHECK(evidence_version=1),
 ADD COLUMN original_xid XID8 NOT NULL,
 ADD COLUMN financial_digest BYTEA NOT NULL CHECK(octet_length(financial_digest)=32),
 ADD COLUMN binding_digest BYTEA NOT NULL CHECK(octet_length(binding_digest)=32),
 ADD COLUMN final_snapshot JSONB NOT NULL,
 ADD COLUMN evidence_document JSONB NOT NULL,
 ADD COLUMN canonical BYTEA NOT NULL,
 ADD COLUMN evidence_digest BYTEA NOT NULL CHECK(evidence_digest=sha256(canonical));
CREATE FUNCTION f125_closure_document(e order_edit_unapplied_evidences) RETURNS JSONB LANGUAGE SQL STABLE SET timezone='UTC' AS $$
 SELECT jsonb_build_object('domain','ec-site/order-edit/unapplied/v1','version',1,
 'admissionId',e.admission_id,'orderId',e.order_id,'generation',e.db_generation_id,
 'requestId',e.closure_request_id,'requestVersion',e.request_version,'originalXid',e.original_xid::text,
 'closureXid',e.recording_xid::text,'financialDigest',encode(e.financial_digest,'hex'),'bindingDigest',encode(e.binding_digest,'hex'),
 'business',e.final_snapshot,'terminal',e.terminal_state,'reason',e.reason_code,'closedAt',to_char(e.recorded_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'invalidatedToken',e.invalidated_token,'controlVersion',e.control_version,'actor',e.actor_name,
 'attempts',coalesce((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.control_version,a.id) FROM order_edit_execution_attempts a WHERE a.admission_id=e.admission_id),'[]'),
 'results',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.control_version,r.id) FROM order_edit_execution_attempt_results r WHERE r.admission_id=e.admission_id),'[]'))
$$;
CREATE FUNCTION f125_closure_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE receipt order_edit_admissions%ROWTYPE; proof order_edit_admission_evidences%ROWTYPE; control order_edit_execution_controls%ROWTYPE;
BEGIN
 SELECT * INTO STRICT receipt FROM order_edit_admissions WHERE id=NEW.admission_id;
 SELECT * INTO STRICT proof FROM order_edit_admission_evidences WHERE admission_id=NEW.admission_id;
 SELECT * INTO STRICT control FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
 IF receipt.execution_state IN ('REVIEW_REQUIRED','RECOVERY_PENDING','APPLYING','COMPLETED') OR receipt.applied_operation_id IS NOT NULL
  OR receipt.unapplied_baseline IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Uncertain or legacy work cannot close'; END IF;
 -- Old close guard already verified original binding, runtime, no active/unresolved attempts and current basis.
 NEW.request_version=control.version;NEW.original_xid=receipt.original_xid;
 NEW.financial_digest=receipt.financial_digest;NEW.binding_digest=proof.financial_binding_digest;
 NEW.final_snapshot=f125_execution_local(NEW.order_id);
 NEW.evidence_document=f125_closure_document(NEW);NEW.canonical=f125_closure_canonical(NEW.evidence_document::text);
 NEW.evidence_digest=sha256(NEW.canonical);RETURN NEW;
END;
$$;
CREATE TRIGGER zz_execution_closure_capture BEFORE INSERT ON order_edit_unapplied_evidences FOR EACH ROW EXECUTE FUNCTION f125_closure_capture();
-- The public internal entry acquires canonical upstream locks before touching a closure/control row.
CREATE FUNCTION f125_execution_close_request(target UUID, request_id UUID, expected_version BIGINT, terminal TEXT, reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE prior order_edit_unapplied_evidences%ROWTYPE; c order_edit_execution_controls%ROWTYPE;
BEGIN
 PERFORM f125_execution_lock(target);
 PERFORM f125_closure_lock_related(target);
 IF request_id IS NULL OR expected_version IS NULL OR expected_version<0 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing closure identity'; END IF;
 SELECT * INTO prior FROM order_edit_unapplied_evidences WHERE admission_id=target OR closure_request_id=request_id;
 IF FOUND THEN
  IF ROW(prior.admission_id,prior.closure_request_id,prior.request_version,prior.terminal_state,prior.reason_code)
   IS DISTINCT FROM ROW(target,request_id,expected_version,terminal,reason) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure identity conflict'; END IF;
  PERFORM f125_financial_assert_saved(target);
  IF prior.canonical IS DISTINCT FROM f125_closure_canonical(prior.evidence_document::text) OR prior.evidence_digest IS DISTINCT FROM sha256(prior.canonical) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Incomplete saved closure'; END IF;
  RETURN;
 END IF;
 PERFORM f125_execution_initialize(target);
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 IF c.version<>expected_version THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure CAS conflict'; END IF;
 INSERT INTO order_edit_unapplied_evidences(admission_id,order_id,db_generation_id,invalidated_token,control_version,terminal_state,reason_code,closure_request_id)
 VALUES(target,c.order_id,c.db_generation_id,c.fencing_token+1,c.version+1,terminal,reason,request_id);
 UPDATE order_edit_execution_controls SET fencing_token=fencing_token+1,version=version+1,updated_at=clock_timestamp() WHERE admission_id=target;
 UPDATE order_edit_admissions SET execution_state=terminal,reason_code=reason,version=version+1 WHERE id=target;
END;
$$;
CREATE FUNCTION f125_closure_assert_final(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE e order_edit_unapplied_evidences%ROWTYPE; r order_edit_admissions%ROWTYPE; c order_edit_execution_controls%ROWTYPE;
BEGIN
 SELECT * INTO e FROM order_edit_unapplied_evidences WHERE admission_id=target AND recording_xid=pg_current_xact_id();
 IF NOT FOUND THEN RETURN; END IF; -- A later business transaction is not constrained to the old state.
 PERFORM f125_execution_check_runtime(target);PERFORM f125_financial_assert_current(target);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 IF e.final_snapshot IS DISTINCT FROM f125_execution_local(e.order_id)
  OR e.final_snapshot IS DISTINCT FROM r.unapplied_baseline
  OR e.evidence_document IS DISTINCT FROM f125_closure_document(e)
  OR e.canonical IS DISTINCT FROM f125_closure_canonical(e.evidence_document::text) OR e.evidence_digest IS DISTINCT FROM sha256(e.canonical)
  OR ROW(r.execution_state,r.reason_code,r.closed_at,c.fencing_token,c.version)
   IS DISTINCT FROM ROW(e.terminal_state,e.reason_code,e.recorded_at,e.invalidated_token,e.control_version)
  OR r.applied_operation_id IS NOT NULL OR c.active_attempt_id IS NOT NULL OR c.lease_until IS NOT NULL
  OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=target AND NOT EXISTS(
   SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('REVIEW','UNKNOWN')))
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure final business/control/evidence state changed'; END IF;
END;
$$;
CREATE FUNCTION f125_closure_final_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID;
BEGIN
 IF TG_TABLE_NAME='order_edit_admissions' THEN target=NEW.id; ELSE target=NEW.admission_id; END IF;
 PERFORM f125_closure_assert_final(target);RETURN NULL;
END;
$$;
DO $$
DECLARE tbl TEXT;
BEGIN
 FOREACH tbl IN ARRAY ARRAY['order_edit_unapplied_evidences','order_edit_execution_controls','order_edit_execution_attempts','order_edit_execution_attempt_results','order_edit_admissions','order_edit_admission_events'] LOOP
  EXECUTE format('CREATE CONSTRAINT TRIGGER zz_closure_final AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_closure_final_trigger()',tbl);
 END LOOP;
END;
$$;
-- No upstream row locks in mutation guards. OLD and NEW ownership are independently resolved
-- before parent deletion/reassignment; even IMMEDIATE validation cannot consume this protection.
CREATE FUNCTION f125_closure_owner(tbl TEXT, row_data JSONB) RETURNS BIGINT LANGUAGE plpgsql STABLE AS $$
BEGIN
 IF row_data IS NULL THEN RETURN NULL; END IF;
 CASE tbl
 WHEN 'orders' THEN RETURN (row_data->>'id')::bigint;
 WHEN 'payment_transactions','capture_requests' THEN RETURN (SELECT order_id FROM payments WHERE id=(row_data->>'payment_id')::bigint);
 WHEN 'order_change_request_dispatches' THEN RETURN (SELECT order_id FROM order_change_financial_requests WHERE id=(row_data->>'request_id')::bigint);
 WHEN 'inventory_reservations' THEN RETURN (SELECT order_id FROM order_change_operations WHERE id=(row_data->>'operation_id')::uuid);
 WHEN 'order_content_change_items','order_content_change_charges' THEN RETURN (SELECT order_id FROM order_content_change_histories WHERE id=(row_data->>'history_id')::bigint);
 ELSE RETURN (row_data->>'order_id')::bigint;
 END CASE;
END;
$$;
CREATE FUNCTION f125_closure_mutation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_owner BIGINT; new_owner BIGINT;
BEGIN
 IF TG_TABLE_NAME IN ('order_edit_runtime_control','order_edit_runtime_generations') THEN
  IF EXISTS(SELECT 1 FROM order_edit_unapplied_evidences WHERE recording_xid=pg_current_xact_id()) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure transaction cannot mutate runtime lineage'; END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD WHEN TG_OP='TRUNCATE' THEN NULL ELSE NEW END;
 END IF;
 IF TG_OP='TRUNCATE' THEN
  IF EXISTS(SELECT 1 FROM order_edit_unapplied_evidences WHERE recording_xid=pg_current_xact_id()) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure transaction cannot truncate business state'; END IF;RETURN NULL;
 END IF;
 IF TG_OP<>'INSERT' THEN old_owner=f125_closure_owner(TG_TABLE_NAME,to_jsonb(OLD)); END IF;
 IF TG_OP<>'DELETE' THEN new_owner=f125_closure_owner(TG_TABLE_NAME,to_jsonb(NEW)); END IF;
 IF EXISTS(SELECT 1 FROM order_edit_unapplied_evidences e WHERE e.recording_xid=pg_current_xact_id() AND (e.order_id IN (old_owner,new_owner)
  OR (TG_TABLE_NAME='stock_movements' AND EXISTS(SELECT 1 FROM inventory_reservations ir JOIN order_change_operations op ON op.id=ir.operation_id
    WHERE op.order_id=e.order_id AND (ir.hold_movement_id IN ((to_jsonb(OLD)->>'id')::bigint,(to_jsonb(NEW)->>'id')::bigint)
      OR ir.release_movement_id IN ((to_jsonb(OLD)->>'id')::bigint,(to_jsonb(NEW)->>'id')::bigint)))))) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business mutation after closure proof in same transaction'; END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;
DO $$
DECLARE tbl TEXT;
BEGIN
 FOREACH tbl IN ARRAY ARRAY['orders','order_items','order_charges','order_content_change_histories','order_content_change_items','order_content_change_charges',
  'order_payment_evidence_epochs','payments','payment_transactions','order_change_operations','order_change_items','order_change_financial_requests',
  'order_change_request_dispatches','capture_requests','inventory_reservations','stock_movements'] LOOP
  EXECUTE format('CREATE TRIGGER aa_closure_mutation BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION f125_closure_mutation_guard()',tbl);
  EXECUTE format('CREATE TRIGGER aa_closure_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION f125_closure_mutation_guard()',tbl);
 END LOOP;
END;
$$;
-- Pinned functions, no PUBLIC execution, ordinary invoker privileges. STOPPED remains default.
DO $$
DECLARE f RECORD;
BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND (proname LIKE 'f125_closure_%' OR proname='f125_execution_close_request') LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END;
$$;

CREATE FUNCTION f125_closure_lock_related(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE owner_id BIGINT;
BEGIN
 SELECT order_id INTO STRICT owner_id FROM order_edit_admissions WHERE id=target;
 -- Order already held: serialize conventional business writers. No product-wide stock lock.
 PERFORM id FROM order_change_financial_requests WHERE order_id=owner_id ORDER BY id FOR UPDATE;
 PERFORM d.request_id FROM order_change_request_dispatches d JOIN order_change_financial_requests r ON r.id=d.request_id WHERE r.order_id=owner_id ORDER BY d.request_id FOR UPDATE OF d;
 PERFORM x.id FROM capture_requests x JOIN payments p ON p.id=x.payment_id WHERE p.order_id=owner_id ORDER BY x.id FOR UPDATE OF x;
 PERFORM id FROM order_items WHERE order_id=owner_id ORDER BY id FOR UPDATE;
 PERFORM id FROM order_charges WHERE order_id=owner_id ORDER BY id FOR UPDATE;
 PERFORM id FROM order_content_change_histories WHERE order_id=owner_id ORDER BY id FOR UPDATE;
 PERFORM x.id FROM order_content_change_items x JOIN order_content_change_histories h ON h.id=x.history_id WHERE h.order_id=owner_id ORDER BY x.id FOR UPDATE OF x;
 PERFORM x.id FROM order_content_change_charges x JOIN order_content_change_histories h ON h.id=x.history_id WHERE h.order_id=owner_id ORDER BY x.id FOR UPDATE OF x;
 PERFORM order_item_id FROM order_change_items WHERE order_id=owner_id ORDER BY operation_id,order_item_id FOR UPDATE;
 PERFORM x.operation_id FROM inventory_reservations x JOIN order_change_operations o ON o.id=x.operation_id WHERE o.order_id=owner_id ORDER BY x.operation_id,x.order_item_id FOR UPDATE OF x;
 PERFORM x.id FROM stock_movements x WHERE x.order_id=owner_id OR EXISTS(
  SELECT 1 FROM inventory_reservations ir JOIN order_change_operations op ON op.id=ir.operation_id
  WHERE op.order_id=owner_id AND x.id IN(ir.hold_movement_id,ir.release_movement_id)) ORDER BY x.id FOR UPDATE OF x;
END;
$$;
DO $$ BEGIN
 EXECUTE format('ALTER FUNCTION f125_closure_lock_related(UUID) SET search_path TO pg_catalog,%I,pg_temp',current_schema());
END; $$;
REVOKE ALL ON FUNCTION f125_closure_lock_related(UUID) FROM PUBLIC;

CREATE INDEX idx_closure_recording_xid_owner ON order_edit_unapplied_evidences(recording_xid,order_id);
CREATE TRIGGER aa_closure_runtime BEFORE INSERT OR UPDATE OR DELETE ON order_edit_runtime_control FOR EACH ROW EXECUTE FUNCTION f125_closure_mutation_guard();
CREATE TRIGGER aa_closure_generation BEFORE INSERT OR UPDATE OR DELETE ON order_edit_runtime_generations FOR EACH ROW EXECUTE FUNCTION f125_closure_mutation_guard();
CREATE TRIGGER aa_closure_runtime_truncate BEFORE TRUNCATE ON order_edit_runtime_control FOR EACH STATEMENT EXECUTE FUNCTION f125_closure_mutation_guard();
CREATE TRIGGER aa_closure_generation_truncate BEFORE TRUNCATE ON order_edit_runtime_generations FOR EACH STATEMENT EXECUTE FUNCTION f125_closure_mutation_guard();
