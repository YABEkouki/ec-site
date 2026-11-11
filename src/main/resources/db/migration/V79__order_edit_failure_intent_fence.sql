-- Intent only: no provider permission, financial settlement or stock/order closure.
-- V78 records already present on upgrade remain fenced, but cannot be replayed as
-- a 3C command without its immutable intent binding. Do not invent that binding.
ALTER TABLE order_edit_failure_closure_controls
 ADD COLUMN intent_operation_version BIGINT CHECK(intent_operation_version>=0),
 ADD COLUMN intent_digest BYTEA CHECK(octet_length(intent_digest)=32);

CREATE FUNCTION f125_failure_intent_bytes(c order_edit_failure_closure_controls) RETURNS BYTEA
LANGUAGE SQL STABLE AS $$
 SELECT f125_closure_encode(jsonb_build_object('domain','f125/3ds-failure/intent/v1',
 'id',c.id,'admission',c.admission_id,'operation',c.operation_id,'order',c.order_id,
 'generation',c.generation_id,'oldPayment',c.old_payment_id,'newPayment',c.new_payment_id,
 'authorization',c.authorization_id,'createRequest',c.create_request_id,'flow',c.flow_id,
 'requesterType',c.requester_type,'actor',c.actor_name,'reason',c.reason,
 'executionVersion',c.execution_version,'operationVersion',c.intent_operation_version,
 'history',encode(c.history_digest,'hex'),'evidenceVersion',c.evidence_version));
$$;

CREATE FUNCTION f125_failure_intent_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE op_version BIGINT; calculated BYTEA;
BEGIN
 -- This new insert entry takes canonical upstream locks before any lower DML.
 PERFORM f125_reauth_lock(NEW.order_id,NEW.operation_id);
 SELECT version INTO STRICT op_version FROM order_change_operations WHERE id=NEW.operation_id;
 IF NEW.intent_operation_version IS NOT NULL AND NEW.intent_operation_version<>op_version THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure operation CAS conflict';
 END IF;
 IF EXISTS(SELECT 1 FROM order_change_financial_requests q
   LEFT JOIN order_change_request_dispatches d ON d.request_id=q.id
   WHERE q.order_id=NEW.order_id AND (d.request_id IS NULL OR d.state<>'OBSERVED')) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unresolved dispatch prevents closure intent';
 END IF;
 NEW.intent_operation_version=op_version;
 calculated=sha256(f125_failure_intent_bytes(NEW));
 IF NEW.intent_digest IS NOT NULL AND NEW.intent_digest<>calculated THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure intent digest conflict';
 END IF;
 NEW.intent_digest=calculated;
 RETURN NEW;
END;
$$;
CREATE TRIGGER aa_failure_intent BEFORE INSERT ON order_edit_failure_closure_controls
 FOR EACH ROW EXECUTE FUNCTION f125_failure_intent_insert_guard();

CREATE FUNCTION f125_failure_request_intent(command_id UUID,target UUID,expected_execution BIGINT,
 expected_operation BIGINT,expected_generation UUID,expected_history BYTEA,actor_type TEXT,actor TEXT)
 RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE;
 existing order_edit_failure_closure_controls%ROWTYPE; result UUID;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT op FROM order_change_operations WHERE order_id=r.order_id AND submission_key=r.submission_key;
 PERFORM f125_reauth_lock(r.order_id,op.id);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=op.id;
 SELECT * INTO existing FROM order_edit_failure_closure_controls WHERE admission_id=target;
 IF FOUND THEN
  IF ROW(existing.id,existing.execution_version,existing.intent_operation_version,existing.generation_id,
       existing.history_digest,existing.requester_type,existing.actor_name)
    IS DISTINCT FROM ROW(command_id,expected_execution,expected_operation,expected_generation,
       expected_history,actor_type,actor)
    OR existing.intent_digest IS NULL
    OR existing.intent_digest<>sha256(f125_failure_intent_bytes(existing)) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Existing closure intent conflicts with command';
  END IF;
  RETURN existing.id;
 END IF;
 IF expected_execution IS NULL OR expected_operation IS NULL OR expected_generation IS NULL
 OR expected_history IS NULL OR command_id IS NULL OR actor_type IS NULL OR actor IS NULL
 OR op.version<>expected_operation OR r.db_generation_id<>expected_generation
 OR expected_history IS DISTINCT FROM f125_failure_history(target)
 OR NOT EXISTS(SELECT 1 FROM order_edit_execution_controls WHERE admission_id=target AND version=expected_execution)
 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure intent CAS or history conflict'; END IF;
 INSERT INTO order_edit_failure_closure_controls(id,admission_id,operation_id,order_id,generation_id,
 old_payment_id,new_payment_id,authorization_id,create_request_id,flow_id,requester_type,actor_name,
 reason,execution_version,history_digest,intent_operation_version)
 SELECT command_id,r.id,op.id,r.order_id,r.db_generation_id,r.base_payment_id,op.new_payment_id,
 q.transaction_id,q.id,p.provider_payment_id,actor_type,actor,'THREE_DS_FAILED',expected_execution,
 expected_history,expected_operation
 FROM order_change_financial_requests q JOIN payments p ON p.id=q.payment_id
 WHERE q.operation_id=op.id AND q.request_kind='CREATE'
 RETURNING id INTO STRICT result;
 RETURN result;
END;
$$;

-- As with V72/V75 mutation guards, do not take upstream locks from a lower-row
-- trigger. Conventional writers already hold Order -> operation -> Payment.
-- An intent holds the same locks, so a normal writer either commits first (and
-- invalidates intent's CAS/history), or sees the durable fence and rolls back.
CREATE FUNCTION f125_failure_intent_mutation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE old_order BIGINT; new_order BIGINT;
BEGIN
 IF TG_OP<>'INSERT' THEN old_order=f125_closure_owner(TG_TABLE_NAME,to_jsonb(OLD)); END IF;
 IF TG_OP<>'DELETE' THEN new_order=f125_closure_owner(TG_TABLE_NAME,to_jsonb(NEW)); END IF;
 IF EXISTS(SELECT 1 FROM order_edit_failure_closure_controls
   WHERE order_id=old_order OR order_id=new_order) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Closure intent fences normal order-edit mutation';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$;
DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['orders','order_items','order_charges','order_change_operations',
 'payments','payment_transactions','order_change_financial_requests','order_change_request_dispatches',
 'order_edit_execution_controls','order_edit_execution_attempts','order_edit_execution_attempt_results',
 'order_edit_admissions','order_edit_inventory_plans','order_edit_unapplied_evidences',
 'order_content_change_histories','order_content_change_items','order_content_change_charges',
 'order_change_items','inventory_reservations','stock_movements','capture_requests'] LOOP
  EXECUTE format('CREATE TRIGGER aa_failure_intent_fence BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION f125_failure_intent_mutation_guard()',tbl);
 END LOOP;
END $$;
