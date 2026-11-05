-- Explicit operational runtime approval only. No activation, backfill, new role or provider call.
-- Invoker-rights: trusted application/management SQL must acquire runtime before business locks.
DO $$ BEGIN
 IF (SELECT state FROM order_edit_runtime_control WHERE id=1)<>'STOPPED' THEN
  RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Stop runtime explicitly before V76'; END IF;
END $$;
ALTER TABLE order_edit_runtime_generations DROP CONSTRAINT order_edit_runtime_generations_scope_check;
ALTER TABLE order_edit_runtime_generations ADD CONSTRAINT ck_runtime_generation_scope CHECK(scope IN ('ISOLATED_TEST','OPERATIONAL'));
ALTER TABLE order_edit_runtime_generations ADD UNIQUE(id,scope);
ALTER TABLE order_edit_admissions DROP CONSTRAINT order_edit_admissions_scope_check;
ALTER TABLE order_edit_admissions ADD CONSTRAINT ck_runtime_admission_scope CHECK(scope IN ('ISOLATED_TEST','OPERATIONAL'));
ALTER TABLE order_edit_admissions ADD UNIQUE(id,scope);
ALTER TABLE order_edit_admissions ADD FOREIGN KEY(db_generation_id,scope) REFERENCES order_edit_runtime_generations(id,scope);
ALTER TABLE order_edit_admission_evidences DROP CONSTRAINT order_edit_admission_evidences_scope_check;
ALTER TABLE order_edit_admission_evidences ADD CONSTRAINT ck_runtime_evidence_scope CHECK(scope IN ('ISOLATED_TEST','OPERATIONAL'));
ALTER TABLE order_edit_admission_evidences ADD FOREIGN KEY(admission_id,scope) REFERENCES order_edit_admissions(id,scope);
ALTER TABLE order_edit_runtime_control DROP CONSTRAINT order_edit_runtime_control_state_check;
ALTER TABLE order_edit_runtime_control DROP CONSTRAINT order_edit_runtime_control_check;
ALTER TABLE order_edit_runtime_control ADD COLUMN approved_instance_identity JSONB,
 ADD COLUMN probe_xid XID8, ADD COLUMN probe_identity JSONB, ADD COLUMN probe_commit_at TIMESTAMPTZ,
 ADD COLUMN approved_by TEXT, ADD COLUMN approval_reason TEXT;
ALTER TABLE order_edit_runtime_control ADD CONSTRAINT ck_runtime_state CHECK(state IN ('STOPPED','ISOLATED_TEST','RUNNING'));
ALTER TABLE order_edit_runtime_control ADD CONSTRAINT ck_runtime_state_generation CHECK(
 (state='STOPPED' AND generation_id IS NULL) OR (state IN ('ISOLATED_TEST','RUNNING') AND generation_id IS NOT NULL));
ALTER TABLE order_edit_runtime_control ADD CONSTRAINT ck_runtime_approval CHECK(state<>'RUNNING' OR
 (approved_instance_identity IS NOT NULL AND probe_xid IS NOT NULL AND probe_identity IS NOT NULL
 AND probe_commit_at IS NOT NULL AND approved_by IS NOT NULL AND length(btrim(approval_reason)) BETWEEN 1 AND 1000));

CREATE FUNCTION f125_runtime_environment() RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
 IF current_setting('server_version_num')::INTEGER NOT BETWEEN 170000 AND 179999
 OR pg_is_in_recovery() OR current_setting('track_commit_timestamp')<>'on'
 OR current_setting('fsync')<>'on' OR current_setting('synchronous_commit')<>'on'
 THEN
  RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Operational runtime requires durable PostgreSQL 17 primary and commit tracking'; END IF;
END;
$$;
-- New format only for OPERATIONAL. Existing ISOLATED_TEST identities remain byte-for-byte unchanged.
CREATE FUNCTION f125_operational_identity() RETURNS JSONB LANGUAGE SQL VOLATILE AS $$
 SELECT jsonb_build_object('format',1,'cluster',s.system_identifier::text,'database',current_database(),
 'databaseOid',d.oid::text,'timeline',c.timeline_id::text,
 'boot',to_char(pg_postmaster_start_time() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US')||'Z')
 FROM pg_control_system() s CROSS JOIN pg_control_checkpoint() c JOIN pg_database d ON d.datname=current_database()
$$;
-- Existence/relationship inspection only; takes no lower row locks while runtime is exclusive.
CREATE FUNCTION f125_runtime_other_work(target UUID DEFAULT NULL) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_admissions a WHERE a.closed_at IS NULL AND (target IS NULL OR a.db_generation_id<>target))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE (target IS NULL OR a.db_generation_id<>target)
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=a.id AND r.disposition NOT IN ('UNKNOWN','REVIEW')))
 OR EXISTS(SELECT 1 FROM order_change_request_dispatches d JOIN order_change_financial_requests q ON q.id=d.request_id
 JOIN order_edit_admissions a ON a.order_id=q.order_id JOIN order_change_operations op ON op.id=q.operation_id AND op.submission_key=a.submission_key
 WHERE d.state<>'OBSERVED' AND (target IS NULL OR a.db_generation_id<>target))
$$;
CREATE FUNCTION f125_runtime_probe_stamp() RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
DECLARE c order_edit_runtime_control%ROWTYPE; x XID8; n NUMERIC; p NUMERIC; stamp TIMESTAMPTZ;
BEGIN
 PERFORM f125_runtime_environment();SELECT * INTO STRICT c FROM order_edit_runtime_control WHERE id=1;
 IF c.probe_xid IS NULL OR c.probe_identity IS DISTINCT FROM f125_operational_identity() THEN
  RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='A committed probe from this DB instance is required'; END IF;
 x=pg_current_xact_id();n=x::text::numeric;p=c.probe_xid::text::numeric;
 IF p>=n OR mod(p,4294967296)<3 OR trunc(p/4294967296)<>trunc(n/4294967296) OR n-p>=2147483648 THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Probe must commit in an independent earlier full-XID transaction'; END IF;
 IF pg_xact_status(c.probe_xid) IS DISTINCT FROM 'committed' THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Probe commit is not proven'; END IF;
 stamp=pg_xact_commit_timestamp(c.probe_xid::xid);
 IF stamp IS NULL OR NOT isfinite(stamp) OR stamp<pg_postmaster_start_time() OR stamp>clock_timestamp()
 OR stamp<clock_timestamp()-interval '5 minutes' OR pg_xact_status(c.probe_xid) IS DISTINCT FROM 'committed' THEN
  RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Fresh finite database commit timestamp required'; END IF;
 RETURN stamp;
END;
$$;
CREATE FUNCTION f125_runtime_validate_work(target UUID, enforce_live_leases BOOLEAN DEFAULT true) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE receipt_row order_edit_admissions%ROWTYPE;
BEGIN
 IF f125_runtime_other_work(target) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Another generation has unresolved admission, attempt or dispatch'; END IF;
 IF enforce_live_leases AND EXISTS(SELECT 1 FROM order_edit_execution_controls WHERE active_attempt_id IS NOT NULL AND lease_until>clock_timestamp()) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Live worker leases prevent runtime approval'; END IF;
 FOR receipt_row IN SELECT * FROM order_edit_admissions WHERE closed_at IS NULL AND db_generation_id=target ORDER BY id LOOP
  IF receipt_row.financial_snapshot_version IS DISTINCT FROM 1 OR receipt_row.request_digest<>sha256(receipt_row.request_payload) OR receipt_row.candidate_digest<>sha256(receipt_row.candidate_payload)
   OR receipt_row.financial_canonical IS DISTINCT FROM f125_financial_encode(receipt_row.financial_snapshot)
   OR receipt_row.financial_digest IS DISTINCT FROM sha256(receipt_row.financial_canonical) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved receipt has incomplete immutable financial evidence'; END IF;
  IF receipt_row.admission_state='ACCEPTED' THEN PERFORM f125_financial_assert_saved(receipt_row.id); END IF;
 END LOOP;
 -- Never infer unknown requests are absent: reject malformed dispatch/transaction ownership.
 IF EXISTS(SELECT 1 FROM order_change_financial_requests q JOIN order_change_operations op ON op.id=q.operation_id
 JOIN order_edit_admissions a ON a.order_id=op.order_id AND a.submission_key=op.submission_key AND a.db_generation_id=target
 LEFT JOIN order_change_request_dispatches d ON d.request_id=q.id
 LEFT JOIN payments p ON p.id=q.payment_id AND p.order_id=a.order_id
 LEFT JOIN payment_transactions t ON t.id=q.transaction_id AND t.payment_id=p.id
 WHERE d.request_id IS NULL OR p.id IS NULL OR t.id IS NULL) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved financial dispatch ownership is incomplete'; END IF;
END;
$$;
CREATE OR REPLACE FUNCTION f125_admission_generation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM f125_require_read_committed();
 IF (SELECT state FROM order_edit_runtime_control WHERE id=1)<>'STOPPED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='New generation requires STOPPED'; END IF;
 IF f125_runtime_other_work() THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved work prevents new generation'; END IF;
 IF NEW.scope='ISOLATED_TEST' THEN
  PERFORM f125_admission_test_environment();
  IF NEW.identity IS DISTINCT FROM f125_admission_identity() THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Test generation identity mismatch'; END IF;
 ELSIF NEW.scope='OPERATIONAL' THEN
  PERFORM f125_runtime_environment();
  IF NEW.identity IS DISTINCT FROM f125_operational_identity() THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Operational generation identity mismatch'; END IF;
 ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unknown generation scope'; END IF;
 NEW.created_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION f125_admission_control_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g order_edit_runtime_generations%ROWTYPE; actual JSONB; stamp TIMESTAMPTZ;
BEGIN
 IF NEW.id<>OLD.id OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Runtime version conflict'; END IF;
 IF NEW.state='STOPPED' THEN
  IF OLD.state='STOPPED' AND NEW.probe_xid IS DISTINCT FROM OLD.probe_xid THEN
   PERFORM f125_runtime_environment();
   IF NEW.probe_xid IS DISTINCT FROM pg_current_xact_id() OR NEW.probe_identity IS DISTINCT FROM f125_operational_identity()
    OR NEW.probe_commit_at IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Probe facts must originate in this DB transaction'; END IF;
  ELSE NEW.probe_xid=NULL;NEW.probe_identity=NULL;NEW.probe_commit_at=NULL; END IF;
  NEW.approved_instance_identity=NULL;NEW.approved_by=NULL;NEW.approval_reason=NULL;
 ELSIF NEW.state IN ('ISOLATED_TEST','RUNNING') THEN
  IF OLD.state<>'STOPPED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Activation requires STOPPED'; END IF;
  SELECT * INTO STRICT g FROM order_edit_runtime_generations WHERE id=NEW.generation_id;
  PERFORM f125_runtime_validate_work(g.id,g.scope='OPERATIONAL');
  IF NEW.state='ISOLATED_TEST' THEN
   PERFORM f125_admission_test_environment();
   IF g.scope<>'ISOLATED_TEST' OR g.identity IS DISTINCT FROM f125_admission_identity() THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Test lineage mismatch'; END IF;
   NEW.approved_instance_identity=NULL;NEW.probe_xid=NULL;NEW.probe_identity=NULL;NEW.probe_commit_at=NULL;NEW.approved_by=NULL;NEW.approval_reason=NULL;
  ELSE
   PERFORM f125_runtime_environment();actual=f125_operational_identity();
   IF g.scope<>'OPERATIONAL' OR (g.identity-'boot') IS DISTINCT FROM (actual-'boot')
    OR length(btrim(coalesce(NEW.approval_reason,''))) NOT BETWEEN 1 AND 1000
    OR ROW(NEW.probe_xid,NEW.probe_identity) IS DISTINCT FROM ROW(OLD.probe_xid,OLD.probe_identity) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Operational lineage or explicit single-writer approval missing'; END IF;
   stamp=f125_runtime_probe_stamp();NEW.probe_commit_at=stamp;NEW.approved_instance_identity=actual;NEW.approved_by=session_user;
  END IF;
 ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unknown runtime state'; END IF;
 NEW.updated_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION f125_admission_check_runtime(expected UUID DEFAULT NULL) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE c order_edit_runtime_control%ROWTYPE; g order_edit_runtime_generations%ROWTYPE; actual JSONB;
BEGIN
 SELECT * INTO STRICT c FROM order_edit_runtime_control WHERE id=1;
 IF c.state NOT IN ('ISOLATED_TEST','RUNNING') OR (expected IS NOT NULL AND expected<>c.generation_id) THEN
 RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Admission runtime stopped or generation changed'; END IF;
 SELECT * INTO STRICT g FROM order_edit_runtime_generations WHERE id=c.generation_id;
 IF c.state='ISOLATED_TEST' THEN
  PERFORM f125_admission_test_environment();
  IF g.scope<>'ISOLATED_TEST' OR g.identity IS DISTINCT FROM f125_admission_identity() THEN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Test lineage mismatch'; END IF;
 ELSE
  PERFORM f125_runtime_environment();actual=f125_operational_identity();
  IF g.scope<>'OPERATIONAL' OR (g.identity-'boot') IS DISTINCT FROM (actual-'boot')
   OR c.approved_instance_identity IS DISTINCT FROM actual OR c.probe_identity IS DISTINCT FROM actual
   OR c.probe_commit_at IS NULL OR c.approved_by IS NULL OR coalesce(length(btrim(c.approval_reason)),0)=0 THEN
   RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Operational DB instance is not approved'; END IF;
 END IF;
 RETURN c.generation_id;
END;
$$;
CREATE OR REPLACE FUNCTION f125_admission_require_runtime(expected UUID DEFAULT NULL) RETURNS UUID LANGUAGE plpgsql AS $$
BEGIN
 PERFORM id FROM order_edit_runtime_control WHERE id=1 FOR SHARE;
 RETURN f125_admission_check_runtime(expected);
END;
$$;
CREATE FUNCTION f125_runtime_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 SELECT scope INTO STRICT NEW.scope FROM order_edit_runtime_generations WHERE id=NEW.db_generation_id;
 RETURN NEW;
END;
$$;
CREATE TRIGGER aaa_runtime_admission_scope BEFORE INSERT ON order_edit_admissions FOR EACH ROW EXECUTE FUNCTION f125_runtime_scope();
CREATE FUNCTION f125_runtime_available() RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN PERFORM f125_admission_check_runtime();RETURN true;
EXCEPTION WHEN OTHERS THEN RETURN false;END;
$$;
CREATE FUNCTION f125_runtime_status() RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE c order_edit_runtime_control%ROWTYPE;
BEGIN
 SELECT * INTO STRICT c FROM order_edit_runtime_control WHERE id=1;
 RETURN jsonb_build_object('storedState',c.state,'available',f125_runtime_available(),'version',c.version,'generation',c.generation_id,
 'approvedIdentity',c.approved_instance_identity,'currentIdentity',f125_operational_identity(),
 'currentIdentityDigest',encode(sha256(convert_to(f125_operational_identity()::text,'UTF8')),'hex'),
 'probeXid',c.probe_xid::text,'probeCommitAt',c.probe_commit_at,
 'activeLeases',(SELECT count(*) FROM order_edit_execution_controls WHERE active_attempt_id IS NOT NULL AND lease_until>clock_timestamp()),
 'unresolvedDispatches',(SELECT count(*) FROM order_change_request_dispatches d JOIN order_change_financial_requests q ON q.id=d.request_id
 JOIN order_change_operations op ON op.id=q.operation_id JOIN order_edit_admissions a ON a.order_id=op.order_id AND a.submission_key=op.submission_key WHERE d.state<>'OBSERVED'),
 'openAdmissions',(SELECT count(*) FROM order_edit_admissions WHERE closed_at IS NULL));
END;
$$;
-- All management calls are standalone transactions. Never call after business locks.
CREATE FUNCTION f125_runtime_new_generation(expected_version BIGINT, scope_name TEXT) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE c order_edit_runtime_control%ROWTYPE; target UUID=gen_random_uuid();actual JSONB;
BEGIN
 PERFORM f125_require_read_committed();SELECT * INTO STRICT c FROM order_edit_runtime_control WHERE id=1 FOR UPDATE;
 IF c.state<>'STOPPED' OR c.version IS DISTINCT FROM expected_version THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Stopped runtime/version required'; END IF;
 IF scope_name='ISOLATED_TEST' THEN actual=f125_admission_identity();ELSIF scope_name='OPERATIONAL' THEN actual=f125_operational_identity();
 ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unknown scope'; END IF;
 INSERT INTO order_edit_runtime_generations(id,scope,identity) VALUES(target,scope_name,actual);
 UPDATE order_edit_runtime_control SET version=version+1 WHERE id=1;
 RETURN target;
END;
$$;
CREATE FUNCTION f125_runtime_probe(expected_version BIGINT) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE c order_edit_runtime_control%ROWTYPE;
BEGIN
 PERFORM f125_require_read_committed();SELECT * INTO STRICT c FROM order_edit_runtime_control WHERE id=1 FOR UPDATE;
 IF c.state<>'STOPPED' OR c.version IS DISTINCT FROM expected_version THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Stopped runtime/version required for probe'; END IF;
 PERFORM f125_runtime_environment();
 UPDATE order_edit_runtime_control SET probe_xid=pg_current_xact_id(),probe_identity=f125_operational_identity(),probe_commit_at=NULL,version=version+1 WHERE id=1;
 RETURN pg_current_xact_id()::text;
END;
$$;
CREATE FUNCTION f125_runtime_check() RETURNS JSONB LANGUAGE plpgsql AS $$
BEGIN
 PERFORM f125_runtime_environment();RETURN jsonb_build_object('identity',f125_operational_identity(),'probeCommitAt',f125_runtime_probe_stamp());
END;
$$;
CREATE FUNCTION f125_runtime_enable(expected_version BIGINT, target UUID, expected_identity_digest TEXT, single_writer_confirmation TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c order_edit_runtime_control%ROWTYPE;
BEGIN
 PERFORM f125_require_read_committed();SELECT * INTO STRICT c FROM order_edit_runtime_control WHERE id=1 FOR UPDATE;
 IF c.state<>'STOPPED' OR c.version IS DISTINCT FROM expected_version OR target IS NULL
 OR expected_identity_digest IS DISTINCT FROM encode(sha256(convert_to(f125_operational_identity()::text,'UTF8')),'hex') THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Runtime state/version/expected instance mismatch'; END IF;
 UPDATE order_edit_runtime_control SET state='RUNNING',generation_id=target,approval_reason=single_writer_confirmation,version=version+1 WHERE id=1;
END;
$$;
CREATE FUNCTION f125_runtime_stop(expected_version BIGINT, reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c order_edit_runtime_control%ROWTYPE;
BEGIN
 PERFORM f125_require_read_committed();SELECT * INTO STRICT c FROM order_edit_runtime_control WHERE id=1 FOR UPDATE;
 IF c.version IS DISTINCT FROM expected_version OR coalesce(length(btrim(reason)),0) NOT BETWEEN 1 AND 1000 THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Stop version/reason missing'; END IF;
 -- Deliberately no current-instance/environment requirement for emergency STOP.
 UPDATE order_edit_runtime_control SET state='STOPPED',generation_id=NULL,version=version+1 WHERE id=1;
END;
$$;
DO $$
DECLARE f RECORD; schema_name TEXT=current_schema();
BEGIN
 FOR f IN SELECT oid::regprocedure AS signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND (proname LIKE 'f125_runtime_%' OR proname='f125_operational_identity' OR proname IN
 ('f125_admission_check_runtime','f125_admission_require_runtime','f125_admission_generation_guard','f125_admission_control_guard')) LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,schema_name);
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END;
$$;
