-- Storage only. No dispatcher permit, financial/order writer, stock release, runtime
-- activation or effective execution-attempt resolution is installed by this migration.
-- Trusted callers acquire f125_reauth_lock before DML, as in V72/V75; row guards
-- validate facts but do not certify lock ownership or external-provider authenticity.
CREATE TABLE order_edit_failure_closure_controls (
 id UUID PRIMARY KEY,
 admission_id UUID NOT NULL UNIQUE REFERENCES order_edit_admissions(id),
 operation_id UUID NOT NULL UNIQUE REFERENCES order_change_operations(id),
 order_id BIGINT NOT NULL REFERENCES orders(id),
 generation_id UUID NOT NULL REFERENCES order_edit_runtime_generations(id),
 old_payment_id BIGINT NOT NULL REFERENCES payments(id),
 new_payment_id BIGINT NOT NULL REFERENCES payments(id),
 authorization_id BIGINT NOT NULL REFERENCES payment_transactions(id),
 create_request_id BIGINT NOT NULL REFERENCES order_change_financial_requests(id),
 cancel_request_id BIGINT UNIQUE REFERENCES order_change_financial_requests(id),
 flow_id TEXT NOT NULL CHECK(length(flow_id) BETWEEN 1 AND 255),
 requester_type TEXT NOT NULL CHECK(requester_type IN ('CUSTOMER','ADMINISTRATOR')),
 actor_name TEXT NOT NULL CHECK(length(btrim(actor_name)) BETWEEN 1 AND 100),
 reason TEXT NOT NULL CHECK(reason='THREE_DS_FAILED'),
 execution_version BIGINT NOT NULL CHECK(execution_version>=0),
 state TEXT NOT NULL DEFAULT 'REQUESTED' CHECK(state IN ('REQUESTED','FAILURE_OBSERVED','TERMINAL_OBSERVED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
 history_digest BYTEA NOT NULL CHECK(octet_length(history_digest)=32),
 evidence_version SMALLINT NOT NULL DEFAULT 1 CHECK(evidence_version=1),
 recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(old_payment_id<>new_payment_id)
);
CREATE TABLE order_edit_failure_closure_observations (
 id UUID PRIMARY KEY,
 control_id UUID NOT NULL REFERENCES order_edit_failure_closure_controls(id),
 control_version BIGINT NOT NULL CHECK(control_version>=0),
 kind TEXT NOT NULL CHECK(kind IN ('THREE_DS_FAILURE','FLOW_TERMINAL')),
 document JSONB NOT NULL,
 observed_started_at TIMESTAMPTZ NOT NULL,
 observed_finished_at TIMESTAMPTZ NOT NULL,
 history_digest BYTEA NOT NULL CHECK(octet_length(history_digest)=32),
 evidence_version SMALLINT NOT NULL DEFAULT 1 CHECK(evidence_version=1),
 canonical BYTEA NOT NULL,
 digest BYTEA NOT NULL CHECK(digest=sha256(canonical)),
 recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(control_id,kind),
 CHECK(isfinite(observed_started_at) AND isfinite(observed_finished_at)
   AND observed_started_at<=observed_finished_at)
);
-- This is an append-only *proposed* resolution. Existing V72/V75 unresolved-work
-- predicates deliberately do not consume it. A later atomic closure writer must
-- validate and consume it together with financial settlement and stock release.
CREATE TABLE order_edit_failure_unknown_resolutions (
 id UUID PRIMARY KEY,
 control_id UUID NOT NULL REFERENCES order_edit_failure_closure_controls(id),
 unknown_result_id UUID NOT NULL UNIQUE REFERENCES order_edit_execution_attempt_results(id),
 terminal_observation_id UUID NOT NULL REFERENCES order_edit_failure_closure_observations(id),
 control_version BIGINT NOT NULL CHECK(control_version>=0),
 disposition TEXT NOT NULL CHECK(disposition='TERMINATED_UNAPPLIED'),
 document JSONB NOT NULL,
 evidence_version SMALLINT NOT NULL DEFAULT 1 CHECK(evidence_version=1),
 canonical BYTEA NOT NULL,
 digest BYTEA NOT NULL CHECK(digest=sha256(canonical)),
 recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION f125_failure_history(target UUID) RETURNS BYTEA LANGUAGE SQL STABLE SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
 SELECT sha256(f125_closure_encode(jsonb_build_object('domain','f125/3ds-failure/history/v1',
 'local',f125_execution_local(r.order_id),
 'attempts',coalesce((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM order_edit_execution_attempts a WHERE a.admission_id=r.id),'[]'),
 'results',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM order_edit_execution_attempt_results x WHERE x.admission_id=r.id),'[]'))))
 FROM order_edit_admissions r WHERE r.id=target
$$;

CREATE FUNCTION f125_failure_observation_bytes(o order_edit_failure_closure_observations) RETURNS BYTEA
 LANGUAGE SQL STABLE SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
 SELECT f125_closure_encode(jsonb_build_object('domain','f125/3ds-failure/observation/v1',
 'id',o.id,'control',o.control_id,'controlVersion',o.control_version,'kind',o.kind,'document',o.document,
 'started',o.observed_started_at,'finished',o.observed_finished_at,'history',encode(o.history_digest,'hex'),
 'version',o.evidence_version,'xid',o.recording_xid::text,'recorded',o.recorded_at));
$$;

CREATE FUNCTION f125_failure_resolution_document(c order_edit_failure_closure_controls, u order_edit_execution_attempt_results,
 a order_edit_execution_attempts,t order_edit_failure_closure_observations,n order_edit_failure_unknown_resolutions)
 RETURNS JSONB LANGUAGE SQL STABLE SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
 SELECT jsonb_build_object('domain','f125/3ds-failure/resolution/v1','control',c.id,'controlVersion',c.version,
 'admission',c.admission_id,'operation',c.operation_id,'generation',c.generation_id,'oldPayment',c.old_payment_id,
 'newPayment',c.new_payment_id,'authorization',c.authorization_id,'flow',c.flow_id,'cancelRequest',c.cancel_request_id,
 'attempt',to_jsonb(a),'unknown',to_jsonb(u),'terminal',t.id,'terminalDigest',encode(t.digest,'hex'),
 'disposition',n.disposition,'version',n.evidence_version,'xid',n.recording_xid::text,'recorded',n.recorded_at);
$$;

CREATE FUNCTION f125_failure_assert_owner(c order_edit_failure_closure_controls) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; q order_change_financial_requests%ROWTYPE;
BEGIN
 PERFORM f125_execution_check_runtime(c.admission_id);
 PERFORM f125_financial_assert_saved(c.admission_id);
 PERFORM f125_reauth_assert_current(c.admission_id);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=c.admission_id;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=c.operation_id;
 SELECT * INTO STRICT q FROM order_change_financial_requests WHERE id=c.create_request_id;
 IF NOT f125_reauth_is_owned(c.order_id,c.operation_id)
 OR op.submission_key IS DISTINCT FROM r.submission_key OR op.customer_id<>r.customer_id
 OR ROW(r.order_id,r.db_generation_id,r.base_payment_id,r.applied_operation_id,r.closed_at)
 IS DISTINCT FROM ROW(c.order_id,c.generation_id,c.old_payment_id,NULL::uuid,NULL::timestamptz)
 OR (c.requester_type='CUSTOMER' AND c.actor_name<>r.actor_username)
 OR r.execution_state NOT IN ('RECOVERY_PENDING','REVIEW_REQUIRED','QUEUED')
 OR op.new_payment_id IS DISTINCT FROM c.new_payment_id OR op.old_payment_id<>c.old_payment_id
 OR op.switch_decided_at IS NOT NULL OR op.content_change_history_id IS NOT NULL
 OR op.state NOT IN ('AUTHORIZING','AUTH_UNKNOWN','REQUIRES_ACTION','ABORTING','ABORT_UNKNOWN')
 OR NOT EXISTS(SELECT 1 FROM orders o WHERE o.id=c.order_id AND o.status='ORDERED'
   AND o.content_revision=r.base_content_revision AND o.total_amount=r.before_amount
   AND o.active_payment_id=c.old_payment_id AND o.shipping_preparation_started_at IS NULL)
 OR NOT EXISTS(SELECT 1 FROM payments p WHERE p.id=c.old_payment_id AND p.order_id=c.order_id AND p.status='AUTHORIZED')
 OR NOT EXISTS(SELECT 1 FROM payments p WHERE p.id=c.new_payment_id AND p.order_id=c.order_id
   AND p.status IN ('PENDING','REQUIRES_ACTION') AND p.amount=r.after_amount AND p.provider_payment_id=c.flow_id)
 OR NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.id=c.authorization_id
   AND t.payment_id=c.new_payment_id AND t.order_change_operation_id=c.operation_id
   AND t.transaction_type='AUTHORIZE' AND t.purpose='ORDER_CHANGE_AUTHORIZATION'
   AND t.status='PENDING' AND t.completed_at IS NULL AND t.amount=r.after_amount
   AND t.order_content_revision=r.base_content_revision+1)
 OR ROW(q.operation_id,q.order_id,q.payment_id,q.transaction_id,q.request_kind)
 IS DISTINCT FROM ROW(c.operation_id,c.order_id,c.new_payment_id,c.authorization_id,'CREATE'::varchar)
 OR NOT EXISTS(SELECT 1 FROM order_edit_execution_controls x WHERE x.admission_id=r.id
   AND x.db_generation_id=c.generation_id AND x.version=c.execution_version
   AND x.active_attempt_id IS NULL AND x.lease_until IS NULL)
 OR EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=c.operation_id
   AND t.status='SUCCEEDED' AND t.transaction_type IN ('AUTHORIZE','CAPTURE'))
 OR EXISTS(SELECT 1 FROM order_change_financial_requests x WHERE x.operation_id=c.operation_id AND x.request_kind='OLD_CANCEL')
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='3DS closure ownership or financial basis conflict'; END IF;
 IF c.cancel_request_id IS NOT NULL AND NOT EXISTS(
 SELECT 1 FROM order_change_financial_requests x JOIN payment_transactions t ON t.id=x.transaction_id
 WHERE x.id=c.cancel_request_id AND x.operation_id=c.operation_id AND x.order_id=c.order_id
 AND x.payment_id=c.new_payment_id AND x.provider_flow_id=c.flow_id AND x.request_kind='NEW_ABORT'
 AND x.purpose='ORDER_CHANGE_NEW_AUTH_ABORT' AND x.transaction_type='CANCEL'
 AND x.amount=r.after_amount AND t.payment_id=c.new_payment_id AND t.order_change_operation_id=c.operation_id
 AND ROW(x.connection_id,x.provider_account_ref,x.mode,x.api_origin)
 IS NOT DISTINCT FROM ROW(q.connection_id,q.provider_account_ref,q.mode,q.api_origin))
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='3DS closure cancel request conflict'; END IF;
END;
$$;

CREATE FUNCTION f125_failure_control_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure control is durable'; END IF;
 PERFORM f125_failure_assert_owner(NEW);
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'REQUESTED' OR NEW.version<>0 OR NEW.cancel_request_id IS NOT NULL
   OR NEW.history_digest IS DISTINCT FROM f125_failure_history(NEW.admission_id)
   OR NEW.recording_xid<>pg_current_xact_id() OR NEW.recorded_at>clock_timestamp() THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid initial closure control'; END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['state','version','cancel_request_id']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['state','version','cancel_request_id'])
   OR NEW.version<>OLD.version+1
   OR (OLD.cancel_request_id IS NOT NULL AND NEW.cancel_request_id IS DISTINCT FROM OLD.cancel_request_id)
   OR NOT ((OLD.state='REQUESTED' AND NEW.state='FAILURE_OBSERVED'
      AND EXISTS(SELECT 1 FROM order_edit_failure_closure_observations x WHERE x.control_id=OLD.id
        AND x.kind='THREE_DS_FAILURE' AND x.control_version=OLD.version AND x.history_digest=f125_failure_history(OLD.admission_id)))
    OR (OLD.state='FAILURE_OBSERVED' AND NEW.state='TERMINAL_OBSERVED'
      AND EXISTS(SELECT 1 FROM order_edit_failure_closure_observations x WHERE x.control_id=OLD.id
        AND x.kind='FLOW_TERMINAL' AND x.control_version=OLD.version AND x.history_digest=f125_failure_history(OLD.admission_id))))
  THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure CAS or evidence transition conflict'; END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER f125_failure_control BEFORE INSERT OR UPDATE OR DELETE ON order_edit_failure_closure_controls
 FOR EACH ROW EXECUTE FUNCTION f125_failure_control_guard();

CREATE FUNCTION f125_failure_observation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_failure_closure_controls%ROWTYPE; q order_change_financial_requests%ROWTYPE;
 d JSONB; expected_keys TEXT[]; actual_keys TEXT[]; bytes BYTEA;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure evidence is append only'; END IF;
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=NEW.control_id;
 PERFORM f125_failure_assert_owner(c);
 SELECT * INTO STRICT q FROM order_change_financial_requests WHERE id=c.create_request_id;
 d=NEW.document;
 expected_keys=ARRAY['amount','amount_capturable','amount_received','api_origin','canceled_at','capture_method','connection_id','currency','flow_id','last_error_code','livemode','metadata','next_action','provider_account_ref','status'];
 IF jsonb_typeof(d) IS DISTINCT FROM 'object' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid observation object'; END IF;
 SELECT array_agg(k ORDER BY k) INTO actual_keys FROM jsonb_object_keys(d) k;
 IF actual_keys IS DISTINCT FROM expected_keys
 OR jsonb_typeof(d->'flow_id') IS DISTINCT FROM 'string' OR d->>'flow_id'<>c.flow_id
 OR jsonb_typeof(d->'amount') IS DISTINCT FROM 'number' OR (d->>'amount') !~ '^[0-9]+$'
 OR d->'amount' IS DISTINCT FROM to_jsonb(q.amount) OR d->'currency' IS DISTINCT FROM '"jpy"'::jsonb
 OR d->'capture_method' IS DISTINCT FROM '"manual"'::jsonb OR d->'livemode' IS DISTINCT FROM to_jsonb(q.mode='LIVE')
 OR d->'metadata' IS DISTINCT FROM jsonb_build_object('order_id',c.order_id::text,'order_change_operation_id',c.operation_id::text)
 OR d->'connection_id' IS DISTINCT FROM to_jsonb(q.connection_id) OR d->'provider_account_ref' IS DISTINCT FROM to_jsonb(q.provider_account_ref)
 OR d->'api_origin' IS DISTINCT FROM to_jsonb(q.api_origin)
 OR d->'amount_received' IS DISTINCT FROM 'null'::jsonb OR d->'amount_capturable' IS DISTINCT FROM 'null'::jsonb
 OR d->'next_action' IS DISTINCT FROM '{}'::jsonb OR d->'last_error_code' IS DISTINCT FROM '"three_d_secure_failed"'::jsonb
 OR NEW.control_version<>c.version OR NEW.history_digest IS DISTINCT FROM f125_failure_history(c.admission_id)
 OR NEW.observed_finished_at>clock_timestamp() OR NEW.observed_started_at<clock_timestamp()-interval '5 seconds'
 OR NEW.recording_xid<>pg_current_xact_id() OR NEW.recorded_at>clock_timestamp()
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='3DS failure observation conflict'; END IF;
 IF NEW.kind='THREE_DS_FAILURE' THEN
  IF c.state<>'REQUESTED' OR d->'status' IS DISTINCT FROM '"requires_payment_method"'::jsonb OR d->'canceled_at' IS DISTINCT FROM 'null'::jsonb THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid 3DS failure phase'; END IF;
 ELSE
  IF c.state<>'FAILURE_OBSERVED' OR d->'status' IS DISTINCT FROM '"canceled"'::jsonb
    OR jsonb_typeof(d->'canceled_at') IS DISTINCT FROM 'string'
    OR (d->>'canceled_at') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid terminal phase'; END IF;
  IF NOT isfinite((d->>'canceled_at')::timestamptz) OR (d->>'canceled_at')::timestamptz>NEW.observed_finished_at THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid cancellation timestamp'; END IF;
 END IF;
 bytes=f125_failure_observation_bytes(NEW);
 IF NEW.canonical IS NULL THEN NEW.canonical=bytes; END IF;
 IF NEW.digest IS NULL THEN NEW.digest=sha256(bytes); END IF;
 IF NEW.canonical IS DISTINCT FROM bytes OR NEW.digest IS DISTINCT FROM sha256(bytes) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Observation canonical or digest conflict'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER f125_failure_observation BEFORE INSERT OR UPDATE OR DELETE ON order_edit_failure_closure_observations
 FOR EACH ROW EXECUTE FUNCTION f125_failure_observation_guard();

CREATE FUNCTION f125_failure_resolution_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_failure_closure_controls%ROWTYPE; u order_edit_execution_attempt_results%ROWTYPE;
 t order_edit_failure_closure_observations%ROWTYPE; a order_edit_execution_attempts%ROWTYPE; doc JSONB; bytes BYTEA;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Resolution evidence is append only'; END IF;
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=NEW.control_id;
 PERFORM f125_failure_assert_owner(c);
 SELECT * INTO STRICT u FROM order_edit_execution_attempt_results WHERE id=NEW.unknown_result_id;
 SELECT * INTO STRICT a FROM order_edit_execution_attempts WHERE id=u.attempt_id;
 SELECT * INTO STRICT t FROM order_edit_failure_closure_observations WHERE id=NEW.terminal_observation_id;
 IF c.state<>'TERMINAL_OBSERVED' OR NEW.control_version<>c.version OR u.admission_id<>c.admission_id
 OR u.disposition<>'UNKNOWN' OR a.db_generation_id<>c.generation_id OR t.control_id<>c.id OR t.kind<>'FLOW_TERMINAL'
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=u.attempt_id AND r.disposition NOT IN ('UNKNOWN','REVIEW'))
 OR t.history_digest IS DISTINCT FROM f125_failure_history(c.admission_id)
 OR NEW.recording_xid<>pg_current_xact_id() OR NEW.recorded_at>clock_timestamp() THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='UNKNOWN resolution identity conflict'; END IF;
 doc=f125_failure_resolution_document(c,u,a,t,NEW);
 bytes=f125_closure_encode(doc);
 IF NEW.document IS NULL THEN NEW.document=doc; END IF;
 IF NEW.canonical IS NULL THEN NEW.canonical=bytes; END IF;
 IF NEW.digest IS NULL THEN NEW.digest=sha256(bytes); END IF;
 IF NEW.document IS DISTINCT FROM doc OR NEW.canonical IS DISTINCT FROM bytes OR NEW.digest IS DISTINCT FROM sha256(bytes) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Resolution canonical or digest conflict'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER f125_failure_resolution BEFORE INSERT OR UPDATE OR DELETE ON order_edit_failure_unknown_resolutions
 FOR EACH ROW EXECUTE FUNCTION f125_failure_resolution_guard();

CREATE FUNCTION f125_failure_no_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure evidence cannot be truncated'; END;
$$;
CREATE TRIGGER f125_failure_control_truncate BEFORE TRUNCATE ON order_edit_failure_closure_controls EXECUTE FUNCTION f125_failure_no_truncate();
CREATE TRIGGER f125_failure_observation_truncate BEFORE TRUNCATE ON order_edit_failure_closure_observations EXECUTE FUNCTION f125_failure_no_truncate();
CREATE TRIGGER f125_failure_resolution_truncate BEFORE TRUNCATE ON order_edit_failure_unknown_resolutions EXECUTE FUNCTION f125_failure_no_truncate();

-- CAS entry: canonical upstream locks precede the closure-control row lock. This
-- only advances evidence bookkeeping; it never claims an execution/dispatch token.
CREATE FUNCTION f125_failure_advance(target UUID, expected_version BIGINT, next_state TEXT, cancel_request BIGINT DEFAULT NULL)
 RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c order_edit_failure_closure_controls%ROWTYPE;
BEGIN
 SELECT * INTO STRICT c FROM order_edit_failure_closure_controls WHERE id=target;
 PERFORM f125_reauth_lock(c.order_id,c.operation_id);
 UPDATE order_edit_failure_closure_controls SET state=next_state,version=version+1,
 cancel_request_id=coalesce(cancel_request,cancel_request_id) WHERE id=target AND version=expected_version;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure CAS conflict'; END IF;
END;
$$;
