-- Feature143-A: closing an owned order change whose re-authorization was never completed
-- (docs/feature143/unfinished-change-closure.md).
--
-- Once automatic_until has passed the change can never be adopted. Its new Payment Flow is cancelled at PAY.JP and,
-- only after it has been read as canceled, the change is aborted, its stock returned and its admission closed in one
-- transaction. A cancel also hits a flow that has just been authorized, so while a closure is REQUESTED or SENDING the
-- new authorization is not applied and nothing new is started for the change.
--   REQUESTED -> SENDING / CLOSED / RELEASED,  SENDING -> CLOSED / REJECTED   (as V110)
-- Same model as V80/V85: an append-only, DB-verified proof row is a transaction-bound capability. The V85 functions
-- are unchanged except for the REAUTH_UNFINISHED alternative. Lock order: runtime, order, operations, payments, ...
-- (f125_reauth_lock). No function sets timezone. No card data, client secret or response body is kept.

CREATE TABLE order_change_flow_closures (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL REFERENCES order_change_operations(id) ON DELETE RESTRICT,
    admission_id UUID NOT NULL REFERENCES order_edit_admissions(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    transaction_id BIGINT NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    provider_payment_flow_id VARCHAR(255) NOT NULL CHECK (provider_payment_flow_id ~ '^pfw_[A-Za-z0-9_-]{1,251}$'),
    requested_by_type VARCHAR(20) NOT NULL CHECK (requested_by_type IN ('ADMIN','SYSTEM')),
    requested_by_account_id BIGINT,
    requested_by_username VARCHAR(100) NOT NULL CHECK (btrim(requested_by_username) <> ''),
    internal_note VARCHAR(500),
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','SENDING','CLOSED','RELEASED','REJECTED')),
    release_reason VARCHAR(30) CHECK (release_reason IN ('AUTHORIZED','PROCESSING','UNREADABLE','CHANGED','STALE')),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((requested_by_type IS NOT DISTINCT FROM 'ADMIN') IS NOT DISTINCT FROM (requested_by_account_id IS NOT NULL)),
    CHECK ((status IN ('CLOSED','RELEASED','REJECTED')) IS NOT DISTINCT FROM (completed_at IS NOT NULL)),
    CHECK ((status IS NOT DISTINCT FROM 'RELEASED') IS NOT DISTINCT FROM (release_reason IS NOT NULL))
);
CREATE UNIQUE INDEX uq_order_change_flow_closure_open ON order_change_flow_closures(operation_id) WHERE status IN ('REQUESTED','SENDING');
CREATE INDEX idx_order_change_flow_closures_order ON order_change_flow_closures(order_id, id);
CREATE INDEX idx_order_change_flow_closures_payment ON order_change_flow_closures(payment_id);

CREATE TABLE order_change_flow_closure_sends (
    id BIGSERIAL PRIMARY KEY,
    closure_id BIGINT NOT NULL REFERENCES order_change_flow_closures(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(100) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,100}$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REJECTED','UNCONFIRMED','ERROR','EXPIRED')),
    error_code VARCHAR(100) CHECK (error_code ~ '^[A-Za-z0-9_]{1,100}$'),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uq_order_change_flow_closure_send_key UNIQUE (idempotency_key),
    CHECK ((status IN ('PENDING','UNCONFIRMED')) IS NOT DISTINCT FROM (completed_at IS NULL))
);
CREATE UNIQUE INDEX uq_order_change_flow_closure_send_in_flight ON order_change_flow_closure_sends(closure_id)
    WHERE status IN ('PENDING','UNCONFIRMED');

-- Append-only proof that the canceled flow was applied; only ever written by f143_close_unfinished.
CREATE TABLE order_edit_unfinished_closures (
    admission_id UUID PRIMARY KEY REFERENCES order_edit_admissions(id),
    closure_id BIGINT NOT NULL UNIQUE REFERENCES order_change_flow_closures(id),
    order_id BIGINT NOT NULL REFERENCES orders(id),
    operation_id UUID NOT NULL UNIQUE REFERENCES order_change_operations(id),
    new_payment_id BIGINT NOT NULL UNIQUE REFERENCES payments(id),
    observation JSONB NOT NULL,
    document JSONB NOT NULL,
    canonical BYTEA NOT NULL,
    digest BYTEA NOT NULL CHECK (digest = sha256(canonical)),
    recording_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON order_edit_unfinished_closures FROM PUBLIC;

CREATE FUNCTION f143_unfinished_proof(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_unfinished_closures n
 WHERE n.admission_id=target AND n.recording_xid=pg_current_xact_id()
 AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical));
$$;
CREATE FUNCTION f143_unfinished_attempt_resolved(attempt UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_execution_attempts a
 JOIN order_edit_unfinished_closures n ON n.admission_id=a.admission_id
 WHERE a.id=attempt AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical)
 AND n.document->'resolvedAttempts' ? attempt::text);
$$;

-- The single eligibility rule, shared by the application and the guards below.
CREATE FUNCTION f143_change_closable(target UUID) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM order_change_operations op
 JOIN orders o ON o.id=op.order_id
 JOIN order_edit_admissions r ON r.order_id=op.order_id AND r.submission_key=op.submission_key
 JOIN order_edit_execution_controls c ON c.admission_id=r.id
 JOIN payments p ON p.id=op.new_payment_id AND p.order_id=op.order_id
 JOIN payments oldp ON oldp.id=op.old_payment_id AND oldp.order_id=op.order_id
 JOIN payment_transactions t ON t.payment_id=p.id AND t.order_change_operation_id=op.id
 JOIN order_change_financial_requests q ON q.operation_id=op.id AND q.transaction_id=t.id AND q.request_kind='CREATE'
 JOIN order_change_request_dispatches d ON d.request_id=q.id AND d.state='OBSERVED'
 WHERE op.id=target AND o.status='ORDERED' AND o.shipping_preparation_started_at IS NULL AND o.active_payment_id=oldp.id
 AND op.execution_version=1 AND op.execution_route='REAUTHORIZE' AND f125_reauth_is_owned(op.order_id,op.id)
 AND (op.state IN ('AUTHORIZING','REQUIRES_ACTION') OR (op.state='MANUAL_REVIEW' AND op.financial_review_reason='UNSETTLED_AUTH_AFTER_GRACE'))
 AND op.switch_decided_at IS NULL AND op.application_xid IS NULL AND op.content_change_history_id IS NULL AND op.abort_request_id IS NULL
 AND r.route='REAUTHORIZE' AND r.admission_state='ACCEPTED' AND r.execution_state='RECOVERY_PENDING'
 AND r.reason_code='REAUTHORIZATION_WAITING' AND r.closed_at IS NULL AND r.applied_operation_id IS NULL
 AND c.active_attempt_id IS NULL AND c.lease_until IS NULL AND c.automatic_until<=clock_timestamp()
 AND f125_runtime_available()
 AND EXISTS(SELECT 1 FROM order_edit_runtime_control rc WHERE rc.id=1 AND rc.generation_id=r.db_generation_id)
 AND p.provider='PAYJP' AND p.payment_method='CARD' AND p.status IN ('PENDING','REQUIRES_ACTION') AND p.amount=r.after_amount
 AND p.provider_payment_id ~ '^pfw_[A-Za-z0-9_-]{1,251}$'
 AND oldp.id=r.base_payment_id AND oldp.status='AUTHORIZED' AND oldp.amount=r.authorized_amount
 AND t.purpose='ORDER_CHANGE_AUTHORIZATION' AND t.transaction_type='AUTHORIZE' AND t.status='PENDING' AND t.amount=r.after_amount
 AND (SELECT count(*) FROM payment_transactions x WHERE x.payment_id=p.id OR x.order_change_operation_id=op.id)=1
 AND NOT EXISTS(SELECT 1 FROM payment_transactions x JOIN payments xp ON xp.id=x.payment_id WHERE xp.order_id=op.order_id AND x.status='PENDING' AND x.id<>t.id)
 AND (SELECT count(*) FROM order_change_financial_requests x WHERE x.operation_id=op.id)=1
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests x LEFT JOIN order_change_request_dispatches y ON y.request_id=x.id
   WHERE x.order_id=op.order_id AND y.state IS DISTINCT FROM 'OBSERVED')
 AND NOT EXISTS(SELECT 1 FROM order_edit_failure_closure_controls x WHERE x.admission_id=r.id)
 AND NOT EXISTS(SELECT 1 FROM order_edit_unfinished_closures x WHERE x.operation_id=op.id)
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=r.id AND NOT EXISTS(
   SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id
   AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR (x.disposition='UNKNOWN' AND x.reason_code='REAUTHORIZATION_WAITING'))))
 AND NOT EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0
   AND (ip.reservation_state IS DISTINCT FROM 'HELD' OR ip.operation_id IS DISTINCT FROM op.id)));
$$;

CREATE FUNCTION f143_closure_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    db_now TIMESTAMP;
    latest order_change_flow_closure_sends%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Order change flow closures cannot be removed';
    END IF;
    -- A closure that may still be sent is decided under the canonical locks.
    IF TG_OP = 'INSERT' OR NEW.status = 'SENDING' THEN
        PERFORM f125_reauth_lock(NEW.order_id, NEW.operation_id);
        PERFORM f125_reauth_assert_current(NEW.admission_id);
        IF NOT f143_change_closable(NEW.operation_id) OR NOT EXISTS (
                SELECT 1 FROM order_change_operations op
                JOIN order_edit_admissions r ON r.order_id = op.order_id AND r.submission_key = op.submission_key
                JOIN payments p ON p.id = op.new_payment_id
                JOIN payment_transactions t ON t.payment_id = p.id
                WHERE op.id = NEW.operation_id AND op.order_id = NEW.order_id AND r.id = NEW.admission_id
                  AND p.id = NEW.payment_id AND t.id = NEW.transaction_id
                  AND p.provider_payment_id = NEW.provider_payment_flow_id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='A flow closure needs an owned change whose re-authorization was not completed in time';
        END IF;
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    IF TG_OP = 'INSERT' THEN
        NEW.status := 'REQUESTED';
        NEW.release_reason := NULL;
        NEW.completed_at := NULL;
        NEW.created_at := db_now;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status','release_reason','completed_at']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status','release_reason','completed_at']) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order change flow closure cannot be redefined';
    END IF;
    IF NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('SENDING','CLOSED','RELEASED'))
            OR (OLD.status = 'SENDING' AND NEW.status IN ('CLOSED','REJECTED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order change flow closure status transition is not allowed';
    END IF;
    IF NEW.status = 'CLOSED' AND NOT f143_unfinished_proof(NEW.admission_id) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A flow closure is closed only together with its proof';
    END IF;
    IF NEW.status = 'REJECTED' THEN
        SELECT * INTO latest FROM order_change_flow_closure_sends WHERE closure_id = NEW.id ORDER BY id DESC LIMIT 1;
        IF latest.id IS NULL OR latest.status IS DISTINCT FROM 'REJECTED' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A closure is rejected only by a rejected cancel';
        END IF;
    END IF;
    IF NEW.status = 'SENDING' THEN
        NEW.completed_at := NULL;
    ELSE
        NEW.completed_at := db_now;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f143_closure_guard BEFORE INSERT OR UPDATE OR DELETE ON order_change_flow_closures
    FOR EACH ROW EXECUTE FUNCTION f143_closure_guard();
CREATE TRIGGER f143_closure_truncate BEFORE TRUNCATE ON order_change_flow_closures
    FOR EACH STATEMENT EXECUTE FUNCTION f143_closure_guard();

CREATE FUNCTION f143_closure_send_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_change_flow_closures%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Order change flow closure sends cannot be removed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO c FROM order_change_flow_closures WHERE id = NEW.closure_id FOR UPDATE;
        IF c.id IS NULL OR c.status IS DISTINCT FROM 'SENDING' OR EXISTS (
                SELECT 1 FROM order_change_flow_closure_sends WHERE closure_id = NEW.closure_id
                AND status NOT IN ('ERROR','EXPIRED')) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A cancel is sent only for a sending closure without an open answer';
        END IF;
        NEW.status := 'PENDING';
        NEW.error_code := NULL;
        NEW.completed_at := NULL;
        NEW.created_at := timezone('Asia/Tokyo', clock_timestamp());
        RETURN NEW;
    END IF;
    IF ROW(NEW.id, NEW.closure_id, NEW.idempotency_key, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.closure_id, OLD.idempotency_key, OLD.created_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order change flow closure send cannot be redefined';
    END IF;
    IF NOT ((OLD.status = 'PENDING' AND NEW.status IN ('ACCEPTED','REJECTED','UNCONFIRMED','ERROR','EXPIRED'))
            OR (OLD.status = 'UNCONFIRMED' AND NEW.status IN ('ACCEPTED','REJECTED','ERROR','EXPIRED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order change flow closure send status transition is not allowed';
    END IF;
    IF NEW.status = 'UNCONFIRMED' THEN
        NEW.completed_at := NULL;
    ELSE
        NEW.completed_at := timezone('Asia/Tokyo', clock_timestamp());
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f143_closure_send_guard BEFORE INSERT OR UPDATE OR DELETE ON order_change_flow_closure_sends
    FOR EACH ROW EXECUTE FUNCTION f143_closure_send_guard();
CREATE TRIGGER f143_closure_send_truncate BEFORE TRUNCATE ON order_change_flow_closure_sends
    FOR EACH STATEMENT EXECUTE FUNCTION f143_closure_send_guard();

-- The fence while a closure is open: the new authorization is not applied, the change only ends by its proof, and no
-- new request, card send, 3DS failure withdrawal or execution starts.
CREATE FUNCTION f143_open_closure_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE blocked BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'payments' THEN
        blocked := EXISTS (SELECT 1 FROM order_change_flow_closures WHERE payment_id = NEW.id AND status IN ('REQUESTED','SENDING'));
    ELSIF TG_TABLE_NAME = 'payment_transactions' THEN
        blocked := EXISTS (SELECT 1 FROM order_change_flow_closures WHERE transaction_id = NEW.id AND status IN ('REQUESTED','SENDING'));
    ELSIF TG_TABLE_NAME = 'order_change_operations' THEN
        blocked := EXISTS (SELECT 1 FROM order_change_flow_closures WHERE operation_id = NEW.id AND status IN ('REQUESTED','SENDING')
                           AND NOT (NEW.state = 'ABORTED' AND f143_unfinished_proof(admission_id)));
    ELSIF TG_TABLE_NAME IN ('order_edit_failure_closure_controls','order_edit_execution_attempts') THEN
        PERFORM 1 FROM orders WHERE id = NEW.order_id FOR SHARE;
        blocked := EXISTS (SELECT 1 FROM order_change_flow_closures WHERE admission_id = NEW.admission_id AND status IN ('REQUESTED','SENDING'));
    ELSE
        -- Serializes with a closure being requested.
        PERFORM 1 FROM orders WHERE id = NEW.order_id FOR SHARE;
        blocked := EXISTS (SELECT 1 FROM order_change_flow_closures WHERE operation_id = NEW.operation_id AND status IN ('REQUESTED','SENDING'));
    END IF;
    IF blocked THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The order change is being closed as unfinished';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f143_payment_fence BEFORE UPDATE OF status ON payments
    FOR EACH ROW WHEN (NEW.status = 'AUTHORIZED' AND OLD.status IS DISTINCT FROM 'AUTHORIZED') EXECUTE FUNCTION f143_open_closure_fence();
CREATE TRIGGER f143_transaction_fence BEFORE UPDATE OF status ON payment_transactions
    FOR EACH ROW WHEN (NEW.status = 'SUCCEEDED' AND OLD.status IS DISTINCT FROM 'SUCCEEDED') EXECUTE FUNCTION f143_open_closure_fence();
CREATE TRIGGER f143_operation_fence BEFORE UPDATE ON order_change_operations
    FOR EACH ROW WHEN (NEW.state IS DISTINCT FROM OLD.state OR NEW.switch_decided_at IS DISTINCT FROM OLD.switch_decided_at
        OR NEW.abort_request_id IS DISTINCT FROM OLD.abort_request_id) EXECUTE FUNCTION f143_open_closure_fence();
CREATE TRIGGER f143_request_fence BEFORE INSERT ON order_change_financial_requests
    FOR EACH ROW EXECUTE FUNCTION f143_open_closure_fence();
CREATE TRIGGER f143_card_send_fence BEFORE INSERT ON order_change_card_sends
    FOR EACH ROW EXECUTE FUNCTION f143_open_closure_fence();
CREATE TRIGGER f143_failure_fence BEFORE INSERT ON order_edit_failure_closure_controls
    FOR EACH ROW EXECUTE FUNCTION f143_open_closure_fence();
CREATE TRIGGER f143_attempt_fence BEFORE INSERT ON order_edit_execution_attempts
    FOR EACH ROW EXECUTE FUNCTION f143_open_closure_fence();

-- Afterwards the failed new payment and its authorization never change again and are never adopted.
CREATE FUNCTION f143_closed_flow_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE closed BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'payments' THEN
        closed := OLD.status = 'FAILED' AND EXISTS (SELECT 1 FROM order_edit_unfinished_closures WHERE new_payment_id = OLD.id);
    ELSIF TG_TABLE_NAME = 'payment_transactions' THEN
        closed := OLD.status = 'FAILED' AND EXISTS (SELECT 1 FROM order_edit_unfinished_closures n
            JOIN order_change_flow_closures f ON f.id = n.closure_id WHERE f.transaction_id = OLD.id);
    ELSE
        closed := OLD.state = 'ABORTED' AND EXISTS (SELECT 1 FROM order_edit_unfinished_closures WHERE operation_id = OLD.id);
    END IF;
    IF closed THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An order change closed as unfinished cannot change';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f143_closed_payment BEFORE UPDATE OF status ON payments
    FOR EACH ROW WHEN (NEW.status IS DISTINCT FROM OLD.status) EXECUTE FUNCTION f143_closed_flow_guard();
CREATE TRIGGER f143_closed_transaction BEFORE UPDATE OF status ON payment_transactions
    FOR EACH ROW WHEN (NEW.status IS DISTINCT FROM OLD.status) EXECUTE FUNCTION f143_closed_flow_guard();
CREATE TRIGGER f143_closed_operation BEFORE UPDATE OF state ON order_change_operations
    FOR EACH ROW WHEN (NEW.state IS DISTINCT FROM OLD.state) EXECUTE FUNCTION f143_closed_flow_guard();

CREATE FUNCTION f143_unfinished_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f order_change_flow_closures%ROWTYPE; r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE;
 q order_change_financial_requests%ROWTYPE; resolved JSONB; doc JSONB; o JSONB;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished closure proof is append only'; END IF;
 SELECT * INTO STRICT f FROM order_change_flow_closures WHERE id=NEW.closure_id;
 PERFORM f125_reauth_lock(f.order_id,f.operation_id);
 PERFORM f125_closure_lock_related(f.admission_id);
 PERFORM f125_execution_check_runtime(f.admission_id);
 SELECT * INTO STRICT f FROM order_change_flow_closures WHERE id=NEW.closure_id FOR UPDATE;
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=f.admission_id;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=f.operation_id;
 SELECT * INTO STRICT q FROM order_change_financial_requests WHERE operation_id=op.id AND request_kind='CREATE';
 o=NEW.observation;
 IF f.status NOT IN ('REQUESTED','SENDING') OR NOT f143_change_closable(op.id)
 OR ROW(NEW.admission_id,NEW.order_id,NEW.operation_id,NEW.new_payment_id)
  IS DISTINCT FROM ROW(f.admission_id,f.order_id,f.operation_id,f.payment_id)
 OR NEW.recording_xid<>pg_current_xact_id() OR NEW.recorded_at>clock_timestamp() THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished closure requires its open flow closure and an unchanged change'; END IF;
 -- The Payment Flow was read as canceled with nothing received, for exactly this order and change.
 IF jsonb_typeof(o) IS DISTINCT FROM 'object'
 OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(o) k)
  IS DISTINCT FROM ARRAY['amount','amountCapturable','amountReceived','captureMethod','currency','flow','livemode','operation','order','status']
 OR o->>'flow' IS DISTINCT FROM f.provider_payment_flow_id OR o->>'status' IS DISTINCT FROM 'canceled'
 OR o->'amount' IS DISTINCT FROM to_jsonb(r.after_amount) OR o->>'currency' IS DISTINCT FROM 'jpy' OR o->>'captureMethod' IS DISTINCT FROM 'manual'
 OR o->'livemode' IS DISTINCT FROM to_jsonb(q.mode='LIVE')
 OR o->>'order' IS DISTINCT FROM f.order_id::text OR o->>'operation' IS DISTINCT FROM f.operation_id::text
 OR coalesce(o->>'amountReceived','0')<>'0' OR coalesce(o->>'amountCapturable','0')<>'0' THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished closure requires the canceled Payment Flow of this change'; END IF;
 PERFORM f125_reauth_assert_current(r.id);
 SELECT coalesce(jsonb_agg(a.id::text ORDER BY a.id::text),'[]'::jsonb) INTO resolved FROM order_edit_execution_attempts a
 WHERE a.admission_id=r.id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND x.disposition NOT IN ('REVIEW','UNKNOWN'));
 doc=jsonb_build_object('domain','f143/unfinished/closure/v1','admission',r.id,'order',r.order_id,'operation',op.id,
  'operationState',op.state,'operationVersion',op.version,'closure',f.id,'newPayment',op.new_payment_id,'oldPayment',r.base_payment_id,
  'observation',o,'resolvedAttempts',resolved,'xid',NEW.recording_xid::text,
  'recorded',to_char(NEW.recorded_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
 NEW.document=doc;NEW.canonical=f125_closure_encode(doc);NEW.digest=sha256(NEW.canonical);
 RETURN NEW;
END;
$$;
CREATE TRIGGER unfinished_insert BEFORE INSERT OR UPDATE OR DELETE ON order_edit_unfinished_closures
    FOR EACH ROW EXECUTE FUNCTION f143_unfinished_insert();
CREATE TRIGGER unfinished_truncate BEFORE TRUNCATE ON order_edit_unfinished_closures EXECUTE FUNCTION f125_failure_no_truncate();

CREATE FUNCTION f143_unfinished_closed(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_unfinished_closures n
 JOIN order_change_flow_closures f ON f.id=n.closure_id AND f.admission_id=n.admission_id
 JOIN order_edit_admissions r ON r.id=n.admission_id
 JOIN order_edit_unapplied_evidences e ON e.admission_id=n.admission_id
 JOIN order_change_operations op ON op.id=n.operation_id
 JOIN payments p ON p.id=n.new_payment_id
 JOIN payment_transactions t ON t.id=f.transaction_id AND t.payment_id=p.id
 WHERE n.admission_id=target AND n.canonical=f125_closure_encode(n.document) AND n.digest=sha256(n.canonical)
 AND n.document->>'admission'=n.admission_id::text AND n.document->>'xid'=n.recording_xid::text
 AND f.status='CLOSED' AND e.recording_xid=n.recording_xid AND e.closure_request_id=n.operation_id
 AND e.terminal_state='CLOSED_UNAPPLIED' AND e.reason_code='REAUTH_UNFINISHED'
 AND e.canonical=f125_closure_canonical(e.evidence_document::text) AND e.evidence_digest=sha256(e.canonical)
 AND r.execution_state='CLOSED_UNAPPLIED' AND r.reason_code='REAUTH_UNFINISHED' AND r.closed_at=e.recorded_at
 AND r.applied_operation_id IS NULL AND op.state='ABORTED' AND op.switch_decided_at IS NULL AND op.new_payment_id=p.id
 AND p.status='FAILED' AND t.status='FAILED' AND t.failure_code='FLOW_CANCELED' AND t.provider_transaction_id=f.provider_payment_flow_id
 AND NOT EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0
   AND (ip.reservation_state IS DISTINCT FROM 'RELEASED' OR ip.release_movement_id IS NULL)));
$$;
CREATE FUNCTION f143_commit_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'order_change_operations' THEN
        IF EXISTS (SELECT 1 FROM order_change_flow_closures WHERE operation_id = NEW.id AND status IN ('REQUESTED','SENDING')) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An aborted change cannot keep an open flow closure';
        END IF;
        RETURN NULL;
    END IF;
    IF NOT f143_unfinished_closed(NEW.admission_id) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Incomplete atomic unfinished change closure';
    END IF;
    IF TG_TABLE_NAME = 'order_edit_unfinished_closures' THEN PERFORM f125_closure_assert_final(NEW.admission_id); END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER unfinished_final AFTER INSERT ON order_edit_unfinished_closures
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f143_commit_check();
CREATE CONSTRAINT TRIGGER f143_closed_commit AFTER UPDATE OF status ON order_change_flow_closures
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status = 'CLOSED') EXECUTE FUNCTION f143_commit_check();
CREATE CONSTRAINT TRIGGER f143_aborted_commit AFTER UPDATE OF state ON order_change_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.state = 'ABORTED') EXECUTE FUNCTION f143_commit_check();

-- The only writer of the proof. Replays return false. Nothing here contacts the provider.
CREATE FUNCTION f143_close_unfinished(target BIGINT, observed JSONB) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE f order_change_flow_closures%ROWTYPE; ec order_edit_execution_controls%ROWTYPE; x order_edit_inventory_plans%ROWTYPE;
 product products%ROWTYPE; movement BIGINT; db_now TIMESTAMP;
BEGIN
 SELECT * INTO STRICT f FROM order_change_flow_closures WHERE id=target;
 PERFORM f125_reauth_lock(f.order_id,f.operation_id);
 PERFORM f125_closure_lock_related(f.admission_id);
 SELECT * INTO STRICT f FROM order_change_flow_closures WHERE id=target FOR UPDATE;
 IF f.status='CLOSED' THEN RETURN false; END IF;
 SELECT * INTO STRICT ec FROM order_edit_execution_controls WHERE admission_id=f.admission_id;
 PERFORM p.id FROM products p JOIN order_edit_inventory_plans ip ON ip.product_id=p.id
  WHERE ip.admission_id=f.admission_id AND ip.reserve_quantity>0 ORDER BY p.id FOR UPDATE OF p;
 INSERT INTO order_edit_unfinished_closures(admission_id,closure_id,order_id,operation_id,new_payment_id,observation)
 VALUES(f.admission_id,f.id,f.order_id,f.operation_id,f.payment_id,observed);
 db_now=timezone('Asia/Tokyo',clock_timestamp());
 UPDATE payment_transactions SET status='FAILED',failure_code='FLOW_CANCELED',failure_message='Unfinished re-authorization canceled',
  provider_transaction_id=f.provider_payment_flow_id,completed_at=db_now WHERE id=f.transaction_id AND status='PENDING';
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Authorization changed before the unfinished closure'; END IF;
 UPDATE payments SET status='FAILED',updated_at=db_now WHERE id=f.payment_id AND status IN ('PENDING','REQUIRES_ACTION');
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Payment changed before the unfinished closure'; END IF;
 UPDATE order_change_operations SET state='ABORTED',financial_review_reason=NULL,version=version+1,updated_at=db_now WHERE id=f.operation_id;
 FOR x IN SELECT * FROM order_edit_inventory_plans WHERE admission_id=f.admission_id AND reserve_quantity>0 ORDER BY product_id FOR UPDATE LOOP
  SELECT * INTO STRICT product FROM products WHERE id=x.product_id FOR UPDATE;
  UPDATE products SET stock=stock+x.reserve_quantity WHERE id=x.product_id;
  INSERT INTO stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,changed_by_type,changed_by_username,reason,order_id)
  VALUES(x.product_id,product.name,'ORDER_CHANGE_RELEASE',x.reserve_quantity,product.stock,product.stock+x.reserve_quantity,
   'SYSTEM','SYSTEM','order_change='||f.operation_id::text,f.order_id) RETURNING id INTO movement;
  UPDATE order_edit_inventory_plans SET reservation_state='RELEASED',release_movement_id=movement,released_at=db_now
  WHERE admission_id=x.admission_id AND product_id=x.product_id;
 END LOOP;
 INSERT INTO order_edit_unapplied_evidences(admission_id,order_id,db_generation_id,invalidated_token,control_version,terminal_state,reason_code,closure_request_id)
 VALUES(f.admission_id,ec.order_id,ec.db_generation_id,ec.fencing_token+1,ec.version+1,'CLOSED_UNAPPLIED','REAUTH_UNFINISHED',f.operation_id);
 UPDATE order_edit_execution_controls SET fencing_token=fencing_token+1,version=version+1,updated_at=clock_timestamp() WHERE admission_id=f.admission_id;
 UPDATE order_edit_admissions SET execution_state='CLOSED_UNAPPLIED',reason_code='REAUTH_UNFINISHED',version=version+1 WHERE id=f.admission_id;
 UPDATE order_change_flow_closures SET status='CLOSED' WHERE id=f.id;
 RETURN true;
END;
$$;

-- V85 functions with only the REAUTH_UNFINISHED alternative added.
CREATE OR REPLACE FUNCTION f125_runtime_other_work(target UUID DEFAULT NULL) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_admissions a WHERE a.closed_at IS NULL AND (target IS NULL OR a.db_generation_id<>target))
 OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE (target IS NULL OR a.db_generation_id<>target)
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=a.id AND r.disposition NOT IN ('UNKNOWN','REVIEW'))
 AND NOT f125_failure_waiting_resolved(a.id) AND NOT f125_not_adopted_attempt_resolved(a.id) AND NOT f143_unfinished_attempt_resolved(a.id))
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
        OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=c.admission_id AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id) OR f143_unfinished_attempt_resolved(a.id)))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved work or invalid closure fence';
    END IF;
    SELECT count(*) INTO NEW.resolved_attempt_count FROM order_edit_execution_attempts WHERE admission_id=c.admission_id;
    NEW.local_digest=c.baseline_digest;NEW.actor_name=session_user;NEW.recorded_at=clock_timestamp();RETURN NEW;
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
        AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id) OR f143_unfinished_attempt_resolved(a.id)));
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

CREATE OR REPLACE FUNCTION f125_closure_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE receipt order_edit_admissions%ROWTYPE; proof order_edit_admission_evidences%ROWTYPE; control order_edit_execution_controls%ROWTYPE;
BEGIN
 SELECT * INTO STRICT receipt FROM order_edit_admissions WHERE id=NEW.admission_id;
 SELECT * INTO STRICT proof FROM order_edit_admission_evidences WHERE admission_id=NEW.admission_id;
 SELECT * INTO STRICT control FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
 IF (NOT f125_failure_business_proof(NEW.admission_id) AND NOT f125_not_adopted_proof(NEW.admission_id) AND NOT f143_unfinished_proof(NEW.admission_id)
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
 -- Old close guard already verified original binding, runtime, no active/unresolved attempts and current basis.
 NEW.request_version=control.version;NEW.original_xid=receipt.original_xid;
 NEW.financial_digest=receipt.financial_digest;NEW.binding_digest=proof.financial_binding_digest;
 NEW.final_snapshot=f125_execution_local(NEW.order_id);
 NEW.evidence_document=f125_closure_document(NEW);NEW.canonical=f125_closure_canonical(NEW.evidence_document::text);
 NEW.evidence_digest=sha256(NEW.canonical);RETURN NEW;
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
                AND NOT EXISTS(SELECT 1 FROM order_edit_not_adopted_closures n WHERE n.admission_id=target AND n.operation_id=op.id AND op.state='ABORTED' AND r.reason_code='CHANGE_NOT_ADOPTED')
                AND NOT EXISTS(SELECT 1 FROM order_edit_unfinished_closures n WHERE n.admission_id=target AND n.operation_id=op.id AND op.state='ABORTED' AND r.reason_code='REAUTH_UNFINISHED')) THEN
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
 owned=f125_failure_business_proof(target) OR f125_not_adopted_proof(target) OR f143_unfinished_proof(target);
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
   SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id AND (x.disposition NOT IN ('REVIEW','UNKNOWN') OR f125_failure_waiting_resolved(a.id) OR f125_not_adopted_attempt_resolved(a.id) OR f143_unfinished_attempt_resolved(a.id))))
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure final business/control/evidence state changed'; END IF;
END;
$$;

DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND (proname LIKE 'f143\_%' OR proname IN (
  'f125_execution_close_guard','f125_closure_capture','f125_execution_final_check','f125_admission_final_check',
  'f125_closure_assert_final','f125_runtime_other_work')) LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END $$;
