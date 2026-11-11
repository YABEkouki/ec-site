-- Storage/evidence foundation only. No business writer, shared service gate or production activation.
-- Test scope is explicit and immutable; it is NEVER an approved production writer identity.
CREATE TABLE order_edit_runtime_generations (
    id UUID PRIMARY KEY,
    scope TEXT NOT NULL DEFAULT 'ISOLATED_TEST' CHECK(scope='ISOLATED_TEST'),
    identity JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE order_edit_runtime_control (
    id INTEGER PRIMARY KEY CHECK(id=1),
    state TEXT NOT NULL CHECK(state IN ('STOPPED','ISOLATED_TEST')),
    generation_id UUID REFERENCES order_edit_runtime_generations(id),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK((state='STOPPED' AND generation_id IS NULL) OR (state='ISOLATED_TEST' AND generation_id IS NOT NULL))
);
INSERT INTO order_edit_runtime_control(id,state) VALUES(1,'STOPPED');

CREATE TABLE order_edit_admissions (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    actor_username VARCHAR(100) NOT NULL,
    submission_key UUID NOT NULL,
    request_version SMALLINT NOT NULL CHECK(request_version=1),
    request_payload BYTEA NOT NULL CHECK(octet_length(request_payload) BETWEEN 1 AND 2097152),
    request_digest BYTEA NOT NULL CHECK(octet_length(request_digest)=32 AND request_digest=sha256(request_payload)),
    candidate_version SMALLINT NOT NULL CHECK(candidate_version=1),
    candidate_payload BYTEA NOT NULL CHECK(octet_length(candidate_payload) BETWEEN 1 AND 2097152),
    candidate_digest BYTEA NOT NULL CHECK(octet_length(candidate_digest)=32 AND candidate_digest=sha256(candidate_payload)),
    base_content_revision INTEGER NOT NULL CHECK(base_content_revision>=0),
    base_evidence_epoch_id UUID NOT NULL,
    base_evidence_history_id BIGINT,
    base_fingerprint BYTEA NOT NULL CHECK(octet_length(base_fingerprint)=32),
    base_payment_id BIGINT NOT NULL,
    base_authorization_id BIGINT NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    authorized_amount INTEGER NOT NULL CHECK(authorized_amount>0),
    authorization_revision INTEGER NOT NULL CHECK(authorization_revision>=0),
    before_amount INTEGER NOT NULL CHECK(before_amount>0 AND before_amount<=authorized_amount),
    after_amount INTEGER NOT NULL CHECK(after_amount>0 AND after_amount<=authorized_amount),
    route TEXT NOT NULL DEFAULT 'RETAIN_AUTH' CHECK(route='RETAIN_AUTH'),
    deadline_at TIMESTAMPTZ NOT NULL,
    original_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    db_generation_id UUID NOT NULL REFERENCES order_edit_runtime_generations(id) ON DELETE RESTRICT,
    scope TEXT NOT NULL DEFAULT 'ISOLATED_TEST' CHECK(scope='ISOLATED_TEST'),
    admission_state TEXT NOT NULL DEFAULT 'RECORDED_UNVERIFIED'
        CHECK(admission_state IN ('RECORDED_UNVERIFIED','UNPROVEN','ACCEPTED','REJECTED_LATE')),
    execution_state TEXT NOT NULL DEFAULT 'NOT_READY'
        CHECK(execution_state IN ('NOT_READY','QUEUED','OBSERVING','APPLYING','RECOVERY_PENDING','REVIEW_REQUIRED',
            'COMPLETED','FAILED_UNAPPLIED','CLOSED_UNAPPLIED')),
    applied_operation_id UUID UNIQUE,
    reason_code VARCHAR(80) NOT NULL DEFAULT 'RECORDED',
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(order_id,submission_key),
    UNIQUE(order_id,id),
    UNIQUE(db_generation_id,original_xid),
    FOREIGN KEY(order_id,customer_id) REFERENCES orders(id,user_id) ON DELETE RESTRICT,
    FOREIGN KEY(order_id,base_payment_id) REFERENCES payments(order_id,id) ON DELETE RESTRICT,
    FOREIGN KEY(order_id,base_evidence_epoch_id) REFERENCES order_payment_evidence_epochs(order_id,id) ON DELETE RESTRICT,
    FOREIGN KEY(order_id,base_evidence_epoch_id,base_evidence_history_id)
        REFERENCES order_content_change_histories(order_id,evidence_epoch_id,id) ON DELETE RESTRICT,
    FOREIGN KEY(order_id,applied_operation_id) REFERENCES order_change_operations(order_id,id) ON DELETE RESTRICT,
    CHECK((execution_state IN ('COMPLETED','FAILED_UNAPPLIED','CLOSED_UNAPPLIED') OR admission_state='REJECTED_LATE')=(closed_at IS NOT NULL)),
    CHECK((execution_state='COMPLETED')=(applied_operation_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_order_edit_admission_open ON order_edit_admissions(order_id) WHERE closed_at IS NULL;

CREATE TABLE order_edit_admission_evidences (
    admission_id UUID PRIMARY KEY REFERENCES order_edit_admissions(id) ON DELETE RESTRICT,
    original_xid XID8 NOT NULL,
    verification_xid XID8 NOT NULL,
    original_commit_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    db_generation_id UUID NOT NULL REFERENCES order_edit_runtime_generations(id) ON DELETE RESTRICT,
    scope TEXT NOT NULL CHECK(scope='ISOLATED_TEST'),
    request_digest BYTEA NOT NULL CHECK(octet_length(request_digest)=32),
    candidate_digest BYTEA NOT NULL CHECK(octet_length(candidate_digest)=32),
    status_before TEXT NOT NULL CHECK(status_before='committed'),
    status_after TEXT NOT NULL CHECK(status_after='committed'),
    decision TEXT NOT NULL CHECK(decision IN ('ACCEPTED','REJECTED_LATE')),
    guard_version SMALLINT NOT NULL CHECK(guard_version=1),
    verified_at TIMESTAMPTZ NOT NULL,
    CHECK(original_xid<>verification_xid),
    CHECK((decision='ACCEPTED' AND original_commit_at<deadline_at)
        OR (decision='REJECTED_LATE' AND original_commit_at>=deadline_at))
);
CREATE TABLE order_edit_admission_events (
    admission_id UUID NOT NULL REFERENCES order_edit_admissions(id) ON DELETE RESTRICT,
    event_no BIGINT NOT NULL CHECK(event_no>=0),
    event_type TEXT NOT NULL CHECK(event_type IN ('RECORDED','ADMISSION_DECIDED','STATE_CHANGED')),
    before_admission TEXT,
    before_execution TEXT,
    after_admission TEXT NOT NULL,
    after_execution TEXT NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(admission_id,event_no)
);
CREATE UNIQUE INDEX uq_order_edit_admission_decision_event ON order_edit_admission_events(admission_id) WHERE event_type='ADMISSION_DECIDED';

CREATE FUNCTION f125_admission_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Admission evidence is append-only'; END;
$$;
CREATE TRIGGER order_edit_generation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_edit_runtime_generations
    FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE TRIGGER order_edit_evidence_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_edit_admission_evidences
    FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE TRIGGER order_edit_event_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_edit_admission_events
    FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE TRIGGER order_edit_admission_preserve BEFORE DELETE OR TRUNCATE ON order_edit_admissions
    FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE TRIGGER order_edit_runtime_preserve BEFORE DELETE OR TRUNCATE ON order_edit_runtime_control
    FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();

CREATE FUNCTION f125_admission_test_environment() RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF current_database() !~ '^isolated_f125_admission_[0-9a-f]{32}$'
        OR current_setting('server_version_num')::INTEGER NOT BETWEEN 170000 AND 179999
        OR pg_is_in_recovery() OR current_setting('track_commit_timestamp')<>'on'
        OR current_setting('fsync')<>'on' OR current_setting('synchronous_commit')<>'on' THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Only explicitly isolated PostgreSQL 17 test execution is implemented';
    END IF;
END;
$$;
CREATE FUNCTION f125_admission_identity() RETURNS JSONB LANGUAGE SQL VOLATILE AS $$
    SELECT jsonb_build_object('cluster',s.system_identifier::TEXT,'database',current_database(),
        'databaseOid',d.oid::TEXT,'timeline',c.timeline_id::TEXT,'boot',pg_postmaster_start_time()::TEXT)
    FROM pg_control_system() s CROSS JOIN pg_control_checkpoint() c
    JOIN pg_database d ON d.datname=current_database()
$$;
CREATE FUNCTION f125_admission_generation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f125_admission_test_environment();
    IF NEW.identity IS DISTINCT FROM f125_admission_identity() OR NEW.scope<>'ISOLATED_TEST' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Test generation identity mismatch';
    END IF;
    NEW.created_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE TRIGGER order_edit_generation_guard BEFORE INSERT ON order_edit_runtime_generations
    FOR EACH ROW EXECUTE FUNCTION f125_admission_generation_guard();
CREATE FUNCTION f125_admission_control_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id<>OLD.id OR NEW.version<>OLD.version+1 THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Runtime version conflict';
    END IF;
    IF NEW.state='ISOLATED_TEST' THEN
        PERFORM f125_admission_test_environment();
        IF NOT EXISTS(SELECT 1 FROM order_edit_runtime_generations WHERE id=NEW.generation_id
            AND scope='ISOLATED_TEST' AND identity=f125_admission_identity()) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Test runtime generation mismatch';
        END IF;
    END IF;
    NEW.updated_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE TRIGGER order_edit_control_guard BEFORE UPDATE ON order_edit_runtime_control
    FOR EACH ROW EXECUTE FUNCTION f125_admission_control_guard();
CREATE FUNCTION f125_admission_require_runtime(expected UUID DEFAULT NULL) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE control order_edit_runtime_control%ROWTYPE;
BEGIN
    SELECT * INTO STRICT control FROM order_edit_runtime_control WHERE id=1 FOR SHARE;
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
-- Pure strict comparison; real admission verification NEVER accepts a caller-supplied timestamp.
CREATE FUNCTION f125_admission_before_deadline(stamp TIMESTAMPTZ, deadline TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF stamp IS NULL OR deadline IS NULL OR NOT isfinite(stamp) OR NOT isfinite(deadline) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Finite commit evidence required';
    END IF;
    RETURN stamp<deadline;
END;
$$;
CREATE FUNCTION f125_admission_lock(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE owner_id BIGINT; generation UUID;
BEGIN
    SELECT order_id,db_generation_id INTO STRICT owner_id,generation FROM order_edit_admissions WHERE id=target;
    PERFORM f125_admission_require_runtime(generation);
    PERFORM id FROM orders WHERE id=owner_id FOR UPDATE;
    PERFORM id FROM order_change_operations WHERE order_id=owner_id ORDER BY id FOR UPDATE;
    PERFORM id FROM payments WHERE order_id=owner_id ORDER BY id FOR UPDATE;
    PERFORM id FROM payment_transactions WHERE payment_id IN(SELECT id FROM payments WHERE order_id=owner_id) ORDER BY payment_id,id FOR UPDATE;
    PERFORM id FROM order_edit_admissions WHERE id=target FOR UPDATE;
END;
$$;

CREATE FUNCTION f125_admission_row_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o orders%ROWTYPE; e order_payment_evidence_epochs%ROWTYPE; a payment_transactions%ROWTYPE; fp BYTEA;
BEGIN
    PERFORM f125_require_read_committed();
    PERFORM f125_admission_require_runtime(NEW.db_generation_id);
    IF TG_OP='INSERT' THEN
        SELECT * INTO STRICT o FROM orders WHERE id=NEW.order_id FOR UPDATE;
        PERFORM id FROM order_change_operations WHERE order_id=o.id ORDER BY id FOR UPDATE;
        PERFORM id FROM payments WHERE order_id=o.id ORDER BY id FOR UPDATE;
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
                AND OLD.execution_state IN ('NOT_READY','QUEUED','REVIEW_REQUIRED'))
            OR (OLD.admission_state='ACCEPTED' AND NEW.admission_state='ACCEPTED' AND (
                (OLD.execution_state='QUEUED' AND NEW.execution_state='OBSERVING')
                OR (OLD.execution_state='OBSERVING' AND NEW.execution_state='APPLYING')
                OR (OLD.execution_state='APPLYING' AND NEW.execution_state IN ('COMPLETED','RECOVERY_PENDING','REVIEW_REQUIRED'))
                OR (OLD.execution_state='RECOVERY_PENDING' AND NEW.execution_state='REVIEW_REQUIRED')))
        ) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Illegal admission state transition'; END IF;
        NEW.updated_at=clock_timestamp();
        NEW.closed_at=CASE WHEN NEW.execution_state IN ('COMPLETED','FAILED_UNAPPLIED','CLOSED_UNAPPLIED') OR NEW.admission_state='REJECTED_LATE' THEN NEW.updated_at ELSE NULL END;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER order_edit_admission_guard BEFORE INSERT OR UPDATE ON order_edit_admissions
    FOR EACH ROW EXECUTE FUNCTION f125_admission_row_guard();

CREATE FUNCTION f125_admission_event_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.recording_xid IS DISTINCT FROM pg_current_xact_id() THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Event must identify its actual transaction';
    END IF;
    NEW.recorded_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE TRIGGER order_edit_event_guard BEFORE INSERT ON order_edit_admission_events
    FOR EACH ROW EXECUTE FUNCTION f125_admission_event_guard();
CREATE FUNCTION f125_admission_record_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO order_edit_admission_events(admission_id,event_no,event_type,before_admission,before_execution,
        after_admission,after_execution,reason_code)
    VALUES(NEW.id,NEW.version,CASE WHEN TG_OP='INSERT' THEN 'RECORDED'
        WHEN OLD.admission_state IS DISTINCT FROM NEW.admission_state AND NEW.admission_state IN ('ACCEPTED','REJECTED_LATE') THEN 'ADMISSION_DECIDED'
        ELSE 'STATE_CHANGED' END,CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.admission_state END,
        CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.execution_state END,NEW.admission_state,NEW.execution_state,NEW.reason_code);
    RETURN NULL;
END;
$$;
CREATE TRIGGER order_edit_admission_event AFTER INSERT OR UPDATE ON order_edit_admissions
    FOR EACH ROW EXECUTE FUNCTION f125_admission_record_event();

CREATE FUNCTION f125_admission_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; verification XID8; original NUMERIC; current_id NUMERIC;
    observed TIMESTAMPTZ; state_before TEXT; state_after TEXT;
BEGIN
    PERFORM f125_require_read_committed();
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=NEW.admission_id;
    PERFORM f125_admission_require_runtime(r.db_generation_id);
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
CREATE TRIGGER order_edit_evidence_guard BEFORE INSERT ON order_edit_admission_evidences
    FOR EACH ROW EXECUTE FUNCTION f125_admission_evidence_guard();

CREATE FUNCTION f125_admission_verify(target UUID) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; outcome TEXT;
BEGIN
    PERFORM f125_admission_lock(target);
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
    IF r.admission_state IN ('ACCEPTED','REJECTED_LATE') THEN RETURN r.admission_state; END IF;
    IF r.closed_at IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Admission is closed'; END IF;
    INSERT INTO order_edit_admission_evidences(admission_id) VALUES(target) RETURNING decision INTO outcome;
    UPDATE order_edit_admissions SET admission_state=outcome,
        execution_state=CASE WHEN outcome='ACCEPTED' THEN 'QUEUED' ELSE 'NOT_READY' END,
        reason_code=CASE WHEN outcome='ACCEPTED' THEN 'COMMIT_BEFORE_DEADLINE' ELSE 'COMMIT_NOT_BEFORE_DEADLINE' END,
        version=version+1 WHERE id=target;
    RETURN outcome;
END;
$$;
CREATE FUNCTION f125_admission_hold(target UUID, reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f125_admission_lock(target);
    UPDATE order_edit_admissions SET admission_state='UNPROVEN',execution_state='REVIEW_REQUIRED',reason_code=reason,version=version+1
        WHERE id=target AND admission_state IN ('RECORDED_UNVERIFIED','UNPROVEN') AND closed_at IS NULL;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Cannot hold decided admission'; END IF;
END;
$$;
CREATE FUNCTION f125_admission_close_unapplied(target UUID, reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f125_admission_lock(target);
    UPDATE order_edit_admissions SET execution_state='CLOSED_UNAPPLIED',reason_code=reason,version=version+1 WHERE id=target AND closed_at IS NULL;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Admission is not open'; END IF;
END;
$$;

CREATE FUNCTION f125_admission_final_check() RETURNS trigger LANGUAGE plpgsql AS $$
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
        IF EXISTS(SELECT 1 FROM order_change_operations WHERE order_id=r.order_id AND submission_key=r.submission_key)
            OR EXISTS(SELECT 1 FROM order_edit_admission_events WHERE admission_id=target AND after_execution IN ('OBSERVING','APPLYING','RECOVERY_PENDING')) THEN
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
CREATE CONSTRAINT TRIGGER order_edit_admission_final AFTER INSERT OR UPDATE ON order_edit_admissions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_admission_final_check();
CREATE CONSTRAINT TRIGGER order_edit_evidence_final AFTER INSERT ON order_edit_admission_evidences
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_admission_final_check();
CREATE CONSTRAINT TRIGGER order_edit_event_final AFTER INSERT ON order_edit_admission_events
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_admission_final_check();

-- Schema-local, invoker-rights functions. No production/runtime grants or activation function.
-- Pin the migration schema ahead of pg_temp; caller search_path cannot substitute proof tables.
DO $$
DECLARE routine RECORD; schema_name TEXT=current_schema();
BEGIN
    FOR routine IN SELECT oid::regprocedure AS signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
        AND proname LIKE 'f125_admission_%' LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',routine.signature,schema_name);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',routine.signature);
    END LOOP;
END;
$$;
