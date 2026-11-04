-- Explicit isolated-test reauthorization ownership. No grants or runtime activation.
DO $$
DECLARE matches TEXT[];
BEGIN
 SELECT array_agg(conname) INTO matches FROM pg_constraint
 WHERE conrelid='order_edit_admissions'::regclass AND contype='c'
 AND regexp_replace(pg_get_constraintdef(oid),'[[:space:]()]','','g')='CHECKafter_amount>0ANDafter_amount<=authorized_amount';
 IF coalesce(cardinality(matches),0)<>1 THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Expected exactly one original admission amount ceiling'; END IF;
 EXECUTE format('ALTER TABLE order_edit_admissions DROP CONSTRAINT %I',matches[1]);
END;
$$;
ALTER TABLE order_edit_admissions DROP CONSTRAINT order_edit_admissions_route_check;
ALTER TABLE order_edit_admissions ADD CONSTRAINT ck_edit_route_amount CHECK (
 (route='RETAIN_AUTH' AND after_amount>0 AND after_amount<=authorized_amount)
 OR (route='REAUTHORIZE' AND after_amount>authorized_amount));

CREATE FUNCTION f125_reauth_is_owned(owner_id BIGINT, operation UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_admissions r JOIN order_change_operations op
 ON op.order_id=r.order_id AND op.submission_key=r.submission_key
 WHERE r.order_id=owner_id AND op.id=operation AND r.route='REAUTHORIZE' AND r.admission_state='ACCEPTED'
 AND ROW(op.customer_id,op.request_digest,op.execution_version,op.execution_route,op.base_content_revision,
 op.base_evidence_epoch_id,op.base_evidence_history_id,op.old_payment_id,op.old_authorization_transaction_id,
 op.old_authorized_amount,op.old_authorization_revision,op.before_amount,op.after_amount)
 IS NOT DISTINCT FROM ROW(r.customer_id,encode(r.request_digest,'hex'),1::smallint,'REAUTHORIZE'::varchar,
 r.base_content_revision,r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_payment_id,r.base_authorization_id,
 r.authorized_amount,r.authorization_revision,r.before_amount,r.after_amount))
$$;
CREATE FUNCTION f125_reauth_require_owner(target UUID, attempt UUID, token BIGINT, expected_version BIGINT, operation UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
 PERFORM f125_execution_check_runtime(target);PERFORM f125_financial_assert_saved(target);
 IF NOT EXISTS(SELECT 1 FROM order_edit_admissions r JOIN order_edit_execution_controls c ON c.admission_id=r.id
 JOIN order_edit_execution_attempts a ON a.id=c.active_attempt_id AND a.admission_id=r.id
 WHERE r.id=target AND r.route='REAUTHORIZE' AND r.admission_state='ACCEPTED' AND r.closed_at IS NULL
 AND r.execution_state IN ('OBSERVING','APPLYING') AND f125_reauth_is_owned(r.order_id,operation)
 AND a.id=attempt AND c.fencing_token=token AND c.version=expected_version AND token>0
 AND a.fencing_token=c.fencing_token AND a.control_version=c.version AND a.order_id=r.order_id
 AND a.db_generation_id=c.db_generation_id AND c.db_generation_id=r.db_generation_id
 AND a.baseline_digest=c.baseline_digest AND a.lease_until=c.lease_until AND c.lease_until>clock_timestamp()
 AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id)) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Reauthorization requires live explicit receipt owner'; END IF;
END;
$$;

-- Compare every baseline row. Only rows bearing this exact operation's financial/movement
-- references may be projected away; baseline content, epoch and old authorization stay exact.
CREATE FUNCTION f125_reauth_assert_current(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; c order_edit_execution_controls%ROWTYPE;
 baseline JSONB; actual JSONB; k TEXT; kept JSONB; old_payment JSONB;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 PERFORM f125_execution_check_runtime(target);PERFORM f125_financial_assert_saved(target);
 baseline=r.unapplied_baseline;actual=f125_execution_local(r.order_id);
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
 OR NOT EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.operation_id=op.id AND q.transaction_id=t.id
 AND q.payment_id=t.payment_id AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type AND q.amount=t.amount))) THEN
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
CREATE FUNCTION f125_reauth_lock(owner_id BIGINT, operation UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE target UUID;
BEGIN
 SELECT id INTO STRICT target FROM order_edit_admissions WHERE order_id=owner_id
 AND submission_key=(SELECT submission_key FROM order_change_operations WHERE id=operation AND order_id=owner_id);
 PERFORM f125_execution_lock(target);
 IF NOT f125_reauth_is_owned(owner_id,operation) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unowned reauthorization lock'; END IF;
END;
$$;

CREATE OR REPLACE FUNCTION f125_execution_assert_basis(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; o orders%ROWTYPE; v_proof order_edit_admission_evidences%ROWTYPE; fp BYTEA;
BEGIN
    SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
    SELECT * INTO STRICT o FROM orders WHERE id=r.order_id;
    SELECT * INTO STRICT v_proof FROM order_edit_admission_evidences WHERE admission_id=target;
    SELECT CASE WHEN r.base_evidence_history_id IS NULL THEN e.start_fingerprint ELSE h.after_fingerprint END INTO fp
        FROM order_payment_evidence_epochs e LEFT JOIN order_content_change_histories h ON h.id=r.base_evidence_history_id AND h.evidence_epoch_id=e.id WHERE e.id=r.base_evidence_epoch_id AND e.order_id=r.order_id;
    IF r.route='REAUTHORIZE' THEN PERFORM f125_reauth_assert_current(target); ELSE PERFORM f125_financial_assert_current(target); END IF;
    IF r.admission_state<>'ACCEPTED' OR r.closed_at IS NOT NULL OR r.route NOT IN ('RETAIN_AUTH','REAUTHORIZE')
        OR ROW(v_proof.decision,v_proof.original_xid,v_proof.db_generation_id,v_proof.request_digest,v_proof.candidate_digest,v_proof.deadline_at)
           IS DISTINCT FROM ROW('ACCEPTED'::text,r.original_xid,r.db_generation_id,r.request_digest,r.candidate_digest,r.deadline_at)
        OR NOT v_proof.original_commit_at<r.deadline_at OR r.request_digest<>sha256(r.request_payload) OR r.candidate_digest<>sha256(r.candidate_payload)
        OR ROW(o.content_revision,o.current_evidence_epoch_id,o.evidence_head_history_id,o.active_payment_id,o.total_amount,fp)
           IS DISTINCT FROM ROW(r.base_content_revision,r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_payment_id,r.before_amount,r.base_fingerprint)
        OR o.status<>'ORDERED' OR o.shipping_preparation_started_at IS NOT NULL OR o.shipment_capture_payment_id IS NOT NULL
        OR o.shipping_preparation_started_by_account_id IS NOT NULL OR o.shipping_preparation_started_by_username IS NOT NULL
        OR EXISTS(SELECT 1 FROM order_change_operations WHERE order_id=r.order_id AND (submission_key=r.submission_key OR state NOT IN ('COMPLETED','ABORTED')) AND NOT (r.route='REAUTHORIZE' AND f125_reauth_is_owned(r.order_id,id)))
        OR EXISTS(SELECT 1 FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.order_id=r.order_id AND t.status='PENDING' AND NOT (r.route='REAUTHORIZE' AND f125_reauth_is_owned(r.order_id,t.order_change_operation_id))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Execution basis or unapplied status is unproven';
    END IF;
END;
$$;
ALTER FUNCTION f125_execution_assert_unapplied(UUID) RENAME TO f125_retained_assert_unapplied;
CREATE FUNCTION f125_execution_assert_unapplied(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT route FROM order_edit_admissions WHERE id=target)='REAUTHORIZE' THEN
 PERFORM f125_execution_assert_basis(target);PERFORM f125_reauth_assert_current(target);
 ELSE PERFORM f125_retained_assert_unapplied(target); END IF;
END;
$$;

CREATE FUNCTION f125_reauth_initialize(target UUID, attempt UUID, token BIGINT, expected_version BIGINT,
 operation UUID, observation BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; preview JSONB; shipping JSONB; accepted TIMESTAMP;
BEGIN
 PERFORM f125_edit_application_context(target,attempt,token,expected_version);
 IF (SELECT route FROM order_edit_admissions WHERE id=target)<>'REAUTHORIZE' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Reauthorization route required'; END IF;
 PERFORM f125_edit_application_observation(target,observation);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 preview=convert_from(r.candidate_payload,'UTF8')::jsonb->'preview';shipping=preview->'charges'->0;
 SELECT original_commit_at AT TIME ZONE 'Asia/Tokyo' INTO STRICT accepted FROM order_edit_admission_evidences WHERE admission_id=target;
 INSERT INTO order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
 old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
 shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
 accepted_at,deadline_at,grace_until,state,version,created_at,updated_at,execution_version,execution_route,
 base_evidence_epoch_id,base_evidence_history_id,old_authorization_transaction_id,old_authorized_amount,
 old_authorization_revision,admission_observation_payload,admission_observation_fingerprint,application_xid)
 VALUES(operation,r.order_id,r.customer_id,r.submission_key,encode(r.request_digest,'hex'),r.base_content_revision,
 r.base_payment_id,r.before_amount,r.after_amount,(preview->>'itemSubtotal')::integer,(preview->>'chargeTotal')::integer,
 (preview->>'taxAmount')::integer,shipping->>'name',(shipping->>'amount')::integer,(shipping->>'taxCategoryId')::bigint,
 shipping->>'taxCategoryCode',shipping->>'taxCategoryName',(shipping->>'taxRateBasisPoints')::numeric/100,
 (shipping->>'displayOrder')::integer,accepted,r.deadline_at AT TIME ZONE 'Asia/Tokyo',
 (r.deadline_at AT TIME ZONE 'Asia/Tokyo')+interval '10 minutes','RESERVED',0,
 clock_timestamp() AT TIME ZONE 'Asia/Tokyo',clock_timestamp() AT TIME ZONE 'Asia/Tokyo',1,'REAUTHORIZE',
 r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_authorization_id,r.authorized_amount,
 r.authorization_revision,observation,sha256(observation),NULL);
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
CREATE FUNCTION f125_reauth_observation(target UUID, observation BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; e order_payment_evidence_epochs%ROWTYPE; j JSONB; original JSONB; op order_change_operations%ROWTYPE; started TIMESTAMPTZ; finished TIMESTAMPTZ;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE id=r.base_evidence_epoch_id;
 IF observation IS NULL OR octet_length(observation) NOT BETWEEN 1 AND 2097152 THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing application observation'; END IF;
 SELECT * INTO STRICT op FROM order_change_operations WHERE order_id=r.order_id AND submission_key=r.submission_key;
 original=convert_from(e.observation_payload,'UTF8')::jsonb;
 SELECT t.id,t.payment_id,t.amount,p.provider_payment_id INTO STRICT e.authorization_transaction_id,e.payment_id,e.authorized_amount,e.provider_flow_id
 FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE t.order_change_operation_id=op.id
 AND t.payment_id=op.new_payment_id AND t.transaction_type='AUTHORIZE' AND t.purpose='ORDER_CHANGE_AUTHORIZATION'
 AND t.status='SUCCEEDED' AND t.completed_at IS NOT NULL AND p.status='AUTHORIZED' AND t.amount=r.after_amount
 AND p.amount=r.after_amount AND t.provider_transaction_id=p.provider_payment_id;
 j=convert_from(observation,'UTF8')::jsonb;
 started=(j->>'startedAt')::timestamptz;finished=(j->>'finishedAt')::timestamptz;
 IF j->'version' IS DISTINCT FROM '1'::jsonb OR j->>'flowId' IS DISTINCT FROM e.provider_flow_id
  OR j->'amount' IS DISTINCT FROM to_jsonb(e.authorized_amount) OR j->'amountCapturable' IS DISTINCT FROM to_jsonb(e.authorized_amount)
  OR j->'amountReceived' IS DISTINCT FROM '0'::jsonb OR j->>'status' IS DISTINCT FROM 'requires_capture'
  OR j->>'currency' IS DISTINCT FROM 'jpy' OR j->>'captureMethod' IS DISTINCT FROM 'manual'
  OR jsonb_typeof(j->'connection') IS DISTINCT FROM 'object' OR jsonb_typeof(j->'liveMode') IS DISTINCT FROM 'boolean'
  OR coalesce(j->'connection'->>'connectionId','')!~'^[A-Za-z0-9_-]{1,100}$'
  OR coalesce(j->'connection'->>'providerAccountRef','')!~'^[A-Za-z0-9_-]{1,100}$'
  OR j->'connection'->>'apiOrigin' IS DISTINCT FROM 'https://api.pay.jp'
  OR coalesce(j->'connection'->>'mode','') NOT IN ('TEST','LIVE')
  OR j->'liveMode' IS DISTINCT FROM to_jsonb(j->'connection'->>'mode'='LIVE')
  OR (jsonb_typeof(original->'connection')='object' AND j->'connection' IS DISTINCT FROM original->'connection')
  OR (jsonb_typeof(original->'liveMode')='boolean' AND j->'liveMode' IS DISTINCT FROM original->'liveMode')
  OR j->>'authorizationOperationId' IS DISTINCT FROM (SELECT order_change_operation_id::text FROM payment_transactions WHERE id=e.authorization_transaction_id)
  OR started IS NULL OR finished IS NULL OR started>finished OR finished>clock_timestamp() OR started<clock_timestamp()-interval '5 seconds'
  OR (j->>'expiredAt' IS NOT NULL AND (j->>'expiredAt')::timestamptz<=clock_timestamp()) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application observation target or freshness mismatch'; END IF;
END;
$$;
CREATE FUNCTION f125_reauth_application_context(target UUID, attempt UUID, token BIGINT, expected_version BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE operation UUID;
BEGIN
 PERFORM f125_execution_lock(target);PERFORM f125_reauth_assert_current(target);
 SELECT op.id INTO STRICT operation FROM order_change_operations op JOIN order_edit_admissions r
 ON r.order_id=op.order_id AND r.submission_key=op.submission_key WHERE r.id=target;
 PERFORM f125_reauth_require_owner(target,attempt,token,expected_version,operation);
 IF NOT EXISTS(SELECT 1 FROM order_change_operations WHERE id=operation AND state='COMMIT_PENDING' AND application_xid IS NULL) OR NOT f125_reauth_conclusive(target) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Reauthorization application requires old commit pending'; END IF;
END;
$$;
CREATE FUNCTION f125_reauth_application_begin(target UUID, attempt UUID, token BIGINT, expected_version BIGINT,
 operation UUID, observation BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
 PERFORM f125_reauth_application_context(target,attempt,token,expected_version);
 PERFORM f125_reauth_require_owner(target,attempt,token,expected_version,operation);
 PERFORM f125_reauth_observation(target,observation);
 IF NOT EXISTS(SELECT 1 FROM order_change_operations op JOIN payments p ON p.id=op.old_payment_id
 WHERE op.id=operation AND p.status='CANCELLED' AND EXISTS(SELECT 1 FROM payment_transactions t
 WHERE t.payment_id=p.id AND t.order_change_operation_id=op.id AND t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL'
 AND t.transaction_type='CANCEL' AND t.status='SUCCEEDED' AND t.completed_at IS NOT NULL
 AND t.amount=p.amount AND t.provider_transaction_id=p.provider_payment_id)) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Old authorization cancellation is not confirmed'; END IF;
 UPDATE order_change_operations SET application_xid=pg_current_xact_id() WHERE id=operation AND application_xid IS NULL;
 UPDATE order_edit_admissions SET execution_state='APPLYING',reason_code='APPLICATION_STARTED',version=version+1 WHERE id=target;
END;
$$;
ALTER FUNCTION f125_edit_application_owner(UUID,UUID) RENAME TO f125_retained_application_owner;
CREATE FUNCTION f125_edit_application_owner(target UUID, operation UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE;
BEGIN
 IF (SELECT route FROM order_edit_admissions WHERE id=target)='REAUTHORIZE' THEN
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 PERFORM f125_reauth_require_owner(target,c.active_attempt_id,c.fencing_token,c.version,operation);
 IF NOT EXISTS(SELECT 1 FROM order_change_operations WHERE id=operation AND application_xid=pg_current_xact_id()
 AND state IN ('COMMIT_PENDING','COMPLETED')) THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Reauthorization application marker not owned'; END IF;
 ELSE PERFORM f125_retained_application_owner(target,operation); END IF;
END;
$$;

CREATE OR REPLACE FUNCTION f125_edit_application_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; c order_edit_execution_controls%ROWTYPE;
BEGIN
 IF TG_OP='UPDATE' AND OLD.application_xid IS NULL AND NEW.application_xid IS NOT NULL
 AND f125_reauth_is_owned(NEW.order_id,NEW.id) THEN
 SELECT r.id INTO STRICT target FROM order_edit_admissions r WHERE r.order_id=NEW.order_id AND r.submission_key=NEW.submission_key;
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 PERFORM f125_reauth_require_owner(target,c.active_attempt_id,c.fencing_token,c.version,NEW.id);
 IF OLD.state<>'COMMIT_PENDING' OR NEW.state<>OLD.state OR NEW.application_xid<>pg_current_xact_id()
 OR (to_jsonb(NEW)-'application_xid') IS DISTINCT FROM (to_jsonb(OLD)-'application_xid') THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid reauthorization application marker'; END IF;
 PERFORM f125_reauth_assert_current(target);RETURN NEW;
 END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.application_xid IS DISTINCT FROM OLD.application_xid THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application transaction identity immutable'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.application_xid IS NULL THEN RETURN NEW; END IF;
 SELECT id INTO STRICT target FROM order_edit_admissions WHERE order_id=NEW.order_id AND submission_key=NEW.submission_key;
 PERFORM f125_execution_check_runtime(target);PERFORM f125_execution_assert_unapplied(target);
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 IF NEW.application_xid<>pg_current_xact_id() OR NEW.state<>'RESERVED' OR NOT EXISTS(
  SELECT 1 FROM order_edit_admissions r JOIN order_edit_execution_attempts a ON a.id=c.active_attempt_id AND a.admission_id=r.id
  WHERE r.id=target AND r.execution_state='OBSERVING' AND r.closed_at IS NULL
   AND c.fencing_token=a.fencing_token AND c.version=a.control_version AND c.lease_until=a.lease_until
   AND c.lease_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application operation requires live same transaction owner'; END IF;
 PERFORM f125_edit_application_observation(target,NEW.admission_observation_payload);RETURN NEW;
END;
$$;
DROP TRIGGER edit_inventory_immutable ON order_edit_inventory_plans;
CREATE TRIGGER edit_inventory_preserve BEFORE DELETE OR TRUNCATE ON order_edit_inventory_plans FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();
CREATE FUNCTION f125_reauth_inventory_update() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER reauth_inventory_update BEFORE UPDATE ON order_edit_inventory_plans FOR EACH ROW EXECUTE FUNCTION f125_reauth_inventory_update();

CREATE FUNCTION f125_reauth_application_snapshot(target UUID, operation UUID, history BIGINT, fingerprint BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; h order_content_change_histories%ROWTYPE;
 o orders%ROWTYPE; e order_payment_evidence_epochs%ROWTYPE; candidate JSONB; preview JSONB; actual JSONB; expected_items JSONB; actual_items JSONB; live_items JSONB; live_charges JSONB;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=operation AND order_id=r.order_id;
 SELECT * INTO STRICT h FROM order_content_change_histories WHERE id=history AND order_id=r.order_id;
 SELECT * INTO STRICT o FROM orders WHERE id=r.order_id;
 SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE order_id=r.order_id AND origin_operation_id=operation AND origin_history_id=history;
 candidate=convert_from(r.candidate_payload,'UTF8')::jsonb;preview=candidate->'preview';actual=convert_from(e.start_snapshot,'UTF8')::jsonb;
 -- New candidate-local IDs are replaced by real IDs; all other persisted facts must match.
 SELECT jsonb_agg(CASE WHEN EXISTS(SELECT 1 FROM jsonb_array_elements(candidate->'newLineReferences') n
   WHERE n->'previewItemId'=i->'orderItemId') THEN i-'orderItemId' ELSE i END ORDER BY i::text)
 INTO expected_items FROM jsonb_array_elements(preview->'items') i;
 SELECT jsonb_agg(CASE WHEN NOT EXISTS(SELECT 1 FROM jsonb_array_elements(preview->'items') p
   WHERE p->'orderItemId'=i->'orderItemId' AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(candidate->'newLineReferences') n
     WHERE n->'previewItemId'=p->'orderItemId')) THEN i-'orderItemId' ELSE i END ORDER BY
   CASE WHEN NOT EXISTS(SELECT 1 FROM jsonb_array_elements(preview->'items') p WHERE p->'orderItemId'=i->'orderItemId'
    AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(candidate->'newLineReferences') n WHERE n->'previewItemId'=p->'orderItemId'))
    THEN (i-'orderItemId')::text ELSE i::text END)
 INTO actual_items FROM jsonb_array_elements(actual->'items') i;
 -- Normalize order only after removing candidate-local identifiers.
 SELECT jsonb_agg(i ORDER BY i::text) INTO expected_items FROM jsonb_array_elements(expected_items) i;
 SELECT jsonb_agg(jsonb_build_object('orderItemId',id,'productId',product_id,'productName',product_name,
  'categoryId',category_id,'categoryName',category_name,'unitPrice',price,'quantity',quantity,'subtotal',subtotal,
  'taxCategoryId',tax_category_id,'taxCategoryCode',tax_category_code,'taxCategoryName',tax_category_name,
  'taxRateBasisPoints',(tax_rate*100)::integer) ORDER BY id) INTO live_items FROM order_items WHERE order_id=r.order_id;
 SELECT jsonb_agg(jsonb_build_object('orderChargeId',id,'type',charge_type,'name',name,'amount',amount,
  'taxCategoryId',tax_category_id,'taxCategoryCode',tax_category_code,'taxCategoryName',tax_category_name,
  'taxRateBasisPoints',(tax_rate*100)::integer,'displayOrder',display_order) ORDER BY id) INTO live_charges FROM order_charges WHERE order_id=r.order_id;
 IF op.application_xid IS DISTINCT FROM pg_current_xact_id() OR op.state<>'COMPLETED' OR op.content_change_history_id IS DISTINCT FROM history
  OR h.evidence_version IS NOT NULL OR h.old_total_amount<>r.before_amount OR h.new_total_amount<>r.after_amount
  OR ROW(e.origin_kind,e.previous_epoch_id,e.payment_id,e.authorized_amount,e.authorization_revision,e.start_content_revision,e.start_total_amount,e.start_fingerprint)
   IS DISTINCT FROM ROW('REAUTH_COMPLETED'::text,r.base_evidence_epoch_id,op.new_payment_id,r.after_amount,r.base_content_revision+1,r.base_content_revision+1,r.after_amount,fingerprint)
  OR ROW(op.item_subtotal,op.charge_total,op.tax_amount,op.shipping_name,op.shipping_amount,
      op.shipping_tax_category_id,op.shipping_tax_category_code,op.shipping_tax_category_name,op.shipping_tax_rate,op.shipping_display_order)
   IS DISTINCT FROM ROW((preview->>'itemSubtotal')::integer,(preview->>'chargeTotal')::integer,(preview->>'taxAmount')::integer,
      preview->'charges'->0->>'name',(preview->'charges'->0->>'amount')::integer,
      (preview->'charges'->0->>'taxCategoryId')::bigint,preview->'charges'->0->>'taxCategoryCode',
      preview->'charges'->0->>'taxCategoryName',(preview->'charges'->0->>'taxRateBasisPoints')::numeric/100,
      (preview->'charges'->0->>'displayOrder')::integer)
  OR e.start_fingerprint IS DISTINCT FROM sha256(e.start_snapshot)
  OR actual->'contentRevision' IS DISTINCT FROM to_jsonb(e.start_content_revision)
  OR ROW(o.content_revision,o.current_evidence_epoch_id,o.evidence_head_history_id,o.active_payment_id,o.total_amount,o.item_subtotal,o.charge_total,o.tax_amount)
   IS DISTINCT FROM ROW(r.base_content_revision+1,e.id,NULL::bigint,op.new_payment_id,r.after_amount,
    (preview->>'itemSubtotal')::integer,(preview->>'chargeTotal')::integer,(preview->>'taxAmount')::integer)
  OR o.status<>'ORDERED' OR o.shipping_preparation_started_at IS NOT NULL OR o.shipment_capture_payment_id IS NOT NULL
  OR o.shipping_preparation_started_by_account_id IS NOT NULL OR o.shipping_preparation_started_by_username IS NOT NULL
  OR (actual-ARRAY['items','contentRevision']) IS DISTINCT FROM (preview-ARRAY['items','contentRevision'])
  OR expected_items IS DISTINCT FROM actual_items
  OR (SELECT jsonb_agg(i ORDER BY (i->>'orderItemId')::bigint) FROM jsonb_array_elements(actual->'items') i) IS DISTINCT FROM live_items
  OR (SELECT jsonb_agg(i ORDER BY (i->>'orderChargeId')::bigint) FROM jsonb_array_elements(actual->'charges') i) IS DISTINCT FROM live_charges
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(actual->'items') i WHERE (i->>'orderItemId')::bigint<=0)
  OR (SELECT count(*) FROM jsonb_array_elements(actual->'items'))<>(SELECT count(DISTINCT i->>'orderItemId') FROM jsonb_array_elements(actual->'items') i)
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(actual->'items') i
    WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(preview->'items') p WHERE p->'orderItemId'=i->'orderItemId'
      AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(candidate->'newLineReferences') n WHERE n->'previewItemId'=p->'orderItemId'))
    AND EXISTS(SELECT 1 FROM jsonb_array_elements(r.unapplied_baseline->'items') b WHERE b->'id'=i->'orderItemId'))
  OR (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(f125_execution_local(r.order_id)->'operations') j
       WHERE j->>'id'<>operation::text) IS DISTINCT FROM
     (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(r.unapplied_baseline->'operations') j)
  OR (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(f125_execution_local(r.order_id)->'histories') j
       WHERE (j->>'id')::bigint<>history) IS DISTINCT FROM
     (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(r.unapplied_baseline->'histories') j)
  OR (SELECT count(*) FROM stock_movements m WHERE m.order_id=r.order_id AND m.reason='order_change='||operation::text)
      <>jsonb_array_length(candidate->'stockDeltas')
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(candidate->'stockDeltas') d WHERE NOT EXISTS(
    SELECT 1 FROM stock_movements m JOIN products p ON p.id=m.product_id
    WHERE m.order_id=r.order_id AND m.reason='order_change='||operation::text
      AND m.product_id=(d->>'productId')::bigint
      AND m.quantity=(d->>'beforeQuantity')::bigint-(d->>'afterQuantity')::bigint
      AND m.quantity<>0 AND m.stock_after::bigint=m.stock_before::bigint+m.quantity AND m.stock_after>=0
      AND ((m.quantity<0 AND EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=target AND ip.product_id=m.product_id AND ip.reservation_state='CONSUMED' AND ip.operation_id=operation AND ip.hold_movement_id=m.id)) OR (m.quantity>0 AND p.stock=m.stock_after)) AND m.product_name=p.name
      AND m.changed_by_type='SYSTEM' AND m.changed_by_username='SYSTEM'
      AND m.movement_type=CASE WHEN m.quantity<0 THEN 'ORDER_CHANGE_RESERVATION' ELSE 'ORDER_CHANGE_RELEASE' END))
  OR (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(f125_execution_local(r.order_id)->'movements') j
       WHERE j->>'reason' IS DISTINCT FROM 'order_change='||operation::text) IS DISTINCT FROM
     (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(r.unapplied_baseline->'movements') j)
 THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application final snapshot, candidate, history or retained finance mismatch'; END IF;
 PERFORM f125_reauth_observation(target,e.observation_payload);
 PERFORM f125_reauth_assert_current(target);
END;
$$;
ALTER FUNCTION f125_edit_application_snapshot(UUID,UUID,BIGINT,BYTEA) RENAME TO f125_retained_application_snapshot;
CREATE FUNCTION f125_edit_application_snapshot(target UUID, operation UUID, history BIGINT, fingerprint BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT route FROM order_edit_admissions WHERE id=target)='REAUTHORIZE' THEN
 PERFORM f125_reauth_application_snapshot(target,operation,history,fingerprint);
 ELSE PERFORM f125_retained_application_snapshot(target,operation,history,fingerprint); END IF;
END;
$$;

CREATE OR REPLACE FUNCTION f125_edit_application_assert_final(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; c order_edit_execution_controls%ROWTYPE; x order_edit_execution_attempt_results%ROWTYPE; na BIGINT; nr BIGINT;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 SELECT * INTO STRICT x FROM order_edit_execution_attempt_results WHERE admission_id=target AND disposition='APPLIED';
 PERFORM f125_execution_check_runtime(target);PERFORM f125_financial_assert_saved(target);
 IF r.execution_state<>'COMPLETED' OR r.admission_state<>'ACCEPTED' OR r.closed_at IS NULL
  OR r.applied_operation_id IS DISTINCT FROM x.application_operation_id OR r.reason_code IS DISTINCT FROM x.reason_code
  OR c.active_attempt_id IS NOT NULL OR c.lease_until IS NOT NULL
  OR c.version<>x.control_version+1 OR c.fencing_token<>x.fencing_token+1
  OR x.recovery OR x.application_version IS DISTINCT FROM 1
  OR x.application_before_revision IS DISTINCT FROM r.base_content_revision OR x.application_after_revision IS DISTINCT FROM r.base_content_revision+1
  OR NOT EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.id=x.attempt_id AND a.admission_id=target
    AND a.fencing_token=x.fencing_token AND a.control_version=x.control_version AND a.order_id=r.order_id
    AND a.db_generation_id=r.db_generation_id AND a.baseline_digest=c.baseline_digest)
  OR EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.admission_id=target AND NOT EXISTS(
    SELECT 1 FROM order_edit_execution_attempt_results z WHERE z.attempt_id=a.id AND z.disposition NOT IN ('REVIEW','UNKNOWN')))
  OR EXISTS(SELECT 1 FROM order_edit_unapplied_evidences WHERE admission_id=target) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application completion ownership/result/fence mismatch'; END IF;
 SELECT count(*) INTO na FROM order_edit_execution_attempts WHERE admission_id=target;
 SELECT count(*) INTO nr FROM order_edit_execution_attempt_results WHERE admission_id=target;
 IF c.get_attempt_count<>na OR c.version<>na+nr OR c.fencing_token<>c.version THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application execution sequence mismatch'; END IF;
 -- Historical receipt remains readable after later legitimate order edits. Same-Tx completion
 -- must prove actual final business state, even when deferred constraints were made immediate.
 IF x.recording_xid=pg_current_xact_id() THEN
  IF NOT EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.id=x.attempt_id AND a.lease_until>clock_timestamp()) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application lease expired before commit'; END IF;
 IF r.route='REAUTHORIZE' THEN
 PERFORM f125_reauth_observation(target,(SELECT observation_payload FROM order_payment_evidence_epochs WHERE order_id=r.order_id AND origin_operation_id=x.application_operation_id));
 ELSE
 PERFORM f125_edit_application_observation(target,(SELECT admission_observation_payload FROM order_change_operations WHERE id=x.application_operation_id)); END IF;
  PERFORM f125_edit_application_snapshot(target,x.application_operation_id,x.application_history_id,x.application_fingerprint);
  IF x.local_digest IS DISTINCT FROM sha256(convert_to(f125_execution_local(r.order_id)::text,'UTF8')) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business state changed after application result'; END IF;
 END IF;
END;
$$;
DO $$
DECLARE f RECORD;
BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND (proname LIKE 'f125_reauth_%' OR proname LIKE 'f125_retained_%' OR proname IN
 ('f125_execution_assert_basis','f125_execution_assert_unapplied','f125_admission_final_check','f125_edit_application_owner',
 'f125_edit_application_operation_guard','f125_edit_application_snapshot','f125_edit_application_assert_final')) LOOP
 EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
 EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END;
$$;
CREATE FUNCTION f125_reauth_ready(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM order_edit_admissions r JOIN order_change_operations op
 ON op.order_id=r.order_id AND op.submission_key=r.submission_key
 WHERE r.id=target AND r.admission_state='ACCEPTED' AND r.closed_at IS NULL
 AND f125_reauth_is_owned(r.order_id,op.id) AND op.application_xid IS NULL AND op.abort_request_id IS NULL
 AND (SELECT count(*) FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id)=jsonb_array_length(convert_from(r.candidate_payload,'UTF8')::jsonb->'stockDeltas')
 AND NOT EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0
 AND (ip.operation_id IS DISTINCT FROM op.id OR ip.reservation_state IS DISTINCT FROM 'HELD'))
 AND EXISTS(SELECT 1 FROM payments oldp WHERE oldp.id=r.base_payment_id AND oldp.order_id=r.order_id
 AND oldp.status='AUTHORIZED' AND oldp.amount=r.authorized_amount)
 AND ((op.state='AUTHORIZING' AND op.switch_decided_at IS NULL
 AND (SELECT count(*) FROM payment_transactions t WHERE t.order_change_operation_id=op.id)=1
 AND (SELECT count(*) FROM order_change_financial_requests q WHERE q.operation_id=op.id)=1
 AND EXISTS(SELECT 1 FROM payments p JOIN payment_transactions t ON t.payment_id=p.id
 JOIN order_change_financial_requests q ON q.transaction_id=t.id AND q.operation_id=op.id
 JOIN order_change_request_dispatches d ON d.request_id=q.id
 WHERE p.id=op.new_payment_id AND p.order_id=r.order_id AND p.provider='PAYJP' AND p.payment_method='CARD'
 AND p.status='PENDING' AND p.amount=r.after_amount AND p.provider_payment_id IS NULL
 AND t.order_change_operation_id=op.id AND t.order_change_order_id=r.order_id
 AND t.purpose='ORDER_CHANGE_AUTHORIZATION' AND t.transaction_type='AUTHORIZE'
 AND t.status='PENDING' AND t.amount=r.after_amount AND t.order_content_revision=r.base_content_revision+1
 AND t.completed_at IS NULL AND t.provider_transaction_id IS NULL
 AND q.order_id=r.order_id AND q.payment_id=p.id AND q.request_kind='CREATE'
 AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type AND q.amount=t.amount
 AND d.state='READY' AND d.started_at IS NULL AND d.observed_at IS NULL))
 OR (op.state IN ('OLD_CANCEL_PENDING','OLD_CANCEL_UNKNOWN') AND op.switch_decided_at IS NOT NULL
 AND (SELECT count(*) FROM payment_transactions t WHERE t.order_change_operation_id=op.id)=2
 AND (SELECT count(*) FROM order_change_financial_requests q WHERE q.operation_id=op.id)=2
 AND EXISTS(SELECT 1 FROM payments p JOIN payment_transactions t ON t.payment_id=p.id
 JOIN order_change_financial_requests q ON q.transaction_id=t.id AND q.operation_id=op.id
 JOIN order_change_request_dispatches d ON d.request_id=q.id
 WHERE p.id=op.new_payment_id AND p.order_id=r.order_id AND p.provider='PAYJP' AND p.payment_method='CARD'
 AND p.status='AUTHORIZED' AND p.amount=r.after_amount AND p.provider_payment_id IS NOT NULL
 AND t.order_change_operation_id=op.id AND t.order_change_order_id=r.order_id
 AND t.purpose='ORDER_CHANGE_AUTHORIZATION' AND t.transaction_type='AUTHORIZE'
 AND t.status='SUCCEEDED' AND t.amount=r.after_amount AND t.order_content_revision=r.base_content_revision+1
 AND t.completed_at IS NOT NULL AND t.provider_transaction_id=p.provider_payment_id
 AND q.order_id=r.order_id AND q.payment_id=p.id AND q.request_kind='CREATE'
 AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type AND q.amount=t.amount AND d.state='OBSERVED')
 AND EXISTS(SELECT 1 FROM payment_transactions t JOIN order_change_financial_requests q
 ON q.transaction_id=t.id AND q.operation_id=op.id JOIN order_change_request_dispatches d ON d.request_id=q.id
 JOIN payments p ON p.id=t.payment_id
 WHERE p.id=r.base_payment_id AND t.order_change_operation_id=op.id AND t.order_change_order_id=r.order_id
 AND t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL' AND t.transaction_type='CANCEL'
 AND t.status='PENDING' AND t.amount=r.authorized_amount AND t.order_content_revision=r.base_content_revision
 AND t.completed_at IS NULL AND t.provider_transaction_id IS NULL
 AND q.order_id=r.order_id AND q.payment_id=p.id AND q.request_kind='OLD_CANCEL' AND q.provider_flow_id=p.provider_payment_id
 AND q.purpose=t.purpose AND q.transaction_type=t.transaction_type AND q.amount=t.amount
 AND d.state='READY' AND d.started_at IS NULL AND d.observed_at IS NULL))))
$$;
CREATE FUNCTION f125_reauth_conclusive(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT f125_reauth_ready(target) OR EXISTS(SELECT 1 FROM order_edit_admissions r WHERE r.id=target AND r.route='REAUTHORIZE'
 AND r.admission_state='ACCEPTED' AND r.closed_at IS NULL AND r.unapplied_baseline IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM order_change_operations op WHERE op.order_id=r.order_id AND op.submission_key=r.submission_key)
 AND f125_execution_local(r.order_id)=r.unapplied_baseline
 AND NOT EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id
 AND num_nonnulls(ip.operation_id,ip.reservation_state,ip.hold_movement_id,ip.release_movement_id,ip.held_at,ip.consumed_at,ip.released_at)<>0))
 OR EXISTS(SELECT 1 FROM order_edit_admissions r JOIN order_change_operations op
 ON op.order_id=r.order_id AND op.submission_key=r.submission_key
 WHERE r.id=target AND f125_reauth_is_owned(r.order_id,op.id) AND op.application_xid IS NULL
 AND ((op.state='RESERVED' AND op.new_payment_id IS NULL AND op.switch_decided_at IS NULL
 AND NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=op.id)
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests q WHERE q.operation_id=op.id)
 AND NOT EXISTS(SELECT 1 FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id AND ip.reserve_quantity>0
 AND (ip.operation_id IS DISTINCT FROM op.id OR ip.reservation_state IS DISTINCT FROM 'HELD'))
 AND (SELECT count(*) FROM order_edit_inventory_plans ip WHERE ip.admission_id=r.id)=jsonb_array_length(convert_from(r.candidate_payload,'UTF8')::jsonb->'stockDeltas'))
 OR (op.state IN ('AUTHORIZED','COMMIT_PENDING') AND EXISTS(SELECT 1 FROM payment_transactions t JOIN payments p ON p.id=t.payment_id
 WHERE t.order_change_operation_id=op.id AND t.payment_id=op.new_payment_id AND t.purpose='ORDER_CHANGE_AUTHORIZATION'
 AND t.transaction_type='AUTHORIZE' AND t.status='SUCCEEDED' AND t.completed_at IS NOT NULL
 AND t.amount=r.after_amount AND t.order_content_revision=r.base_content_revision+1
 AND p.status='AUTHORIZED' AND p.provider_payment_id=t.provider_transaction_id)
 AND NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.order_change_operation_id=op.id AND t.status='PENDING')
 AND NOT EXISTS(SELECT 1 FROM order_change_financial_requests q LEFT JOIN payment_transactions t ON t.id=q.transaction_id
 WHERE q.operation_id=op.id AND (t.id IS NULL OR t.status NOT IN ('SUCCEEDED','FAILED') OR t.completed_at IS NULL))
 AND (op.state<>'COMMIT_PENDING' OR EXISTS(SELECT 1 FROM payments p JOIN payment_transactions t ON t.payment_id=p.id
 WHERE p.id=op.old_payment_id AND p.status='CANCELLED' AND t.order_change_operation_id=op.id
 AND t.transaction_type='CANCEL' AND t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL' AND t.status='SUCCEEDED'
 AND t.completed_at IS NOT NULL AND t.amount=p.amount AND t.provider_transaction_id=p.provider_payment_id)))))
$$;
CREATE FUNCTION f125_reauth_resume(target UUID, result_id UUID, expected_version BIGINT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE a order_edit_execution_attempts%ROWTYPE; c order_edit_execution_controls%ROWTYPE; prior order_edit_execution_attempt_results%ROWTYPE;
BEGIN
 PERFORM f125_execution_lock(target);PERFORM f125_reauth_assert_current(target);
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 SELECT * INTO prior FROM order_edit_execution_attempt_results WHERE id=result_id;
 IF FOUND THEN
 IF prior.admission_id<>target OR prior.control_version<>expected_version OR prior.reason_code<>'REAUTH_FINANCE_CONCLUSIVE' OR prior.disposition<>'REQUEUE' THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Recovery result identity reused'; END IF;RETURN;END IF;
 IF c.active_attempt_id IS NOT NULL OR c.version<>expected_version OR NOT f125_reauth_conclusive(target)
 OR (SELECT execution_state FROM order_edit_admissions WHERE id=target) NOT IN ('RECOVERY_PENDING','REVIEW_REQUIRED') THEN
 RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Recovery requires fenced owner and conclusive owned finance'; END IF;
 SELECT * INTO STRICT a FROM order_edit_execution_attempts x WHERE admission_id=target AND NOT EXISTS(
 SELECT 1 FROM order_edit_execution_attempt_results r WHERE r.attempt_id=x.id AND r.disposition NOT IN ('REVIEW','UNKNOWN'));
 PERFORM f125_execution_resolve(target,a.id,a.fencing_token,expected_version,'REQUEUE','REAUTH_FINANCE_CONCLUSIVE',true,result_id);
END;
$$;

CREATE OR REPLACE FUNCTION f125_execution_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c order_edit_execution_controls%ROWTYPE; a order_edit_execution_attempts%ROWTYPE; state TEXT;
BEGIN
    PERFORM f125_execution_check_runtime(NEW.admission_id);
    SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=NEW.admission_id;
    SELECT * INTO STRICT a FROM order_edit_execution_attempts WHERE id=NEW.attempt_id AND admission_id=NEW.admission_id;
    SELECT execution_state INTO STRICT state FROM order_edit_admissions WHERE id=NEW.admission_id;
    IF NEW.disposition='APPLIED' THEN
        PERFORM f125_edit_application_owner(NEW.admission_id,NEW.application_operation_id);
        IF state<>'APPLYING' OR NEW.recovery OR NEW.order_id<>c.order_id OR NEW.control_version<>c.version
            OR NEW.fencing_token<>c.fencing_token OR NEW.fencing_token<>a.fencing_token
            OR c.active_attempt_id IS DISTINCT FROM a.id OR NEW.recording_xid<>pg_current_xact_id()
            OR NEW.reason_code IS DISTINCT FROM 'APPLICATION_COMPLETED' THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Applied result ownership mismatch'; END IF;
        NEW.local_digest=sha256(convert_to(f125_execution_local(c.order_id)::text,'UTF8'));
        NEW.recorded_at=clock_timestamp();RETURN NEW;
    END IF;
    PERFORM f125_financial_assert_saved(NEW.admission_id);
    IF NEW.disposition NOT IN ('REVIEW','UNKNOWN') THEN PERFORM f125_execution_assert_unapplied(NEW.admission_id); END IF;
    IF NEW.order_id<>c.order_id OR NEW.control_version<>c.version OR NEW.fencing_token<>a.fencing_token
        OR NEW.recording_xid<>pg_current_xact_id() OR NEW.reason_code IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Attempt result identity/CAS conflict';
    END IF;
    IF NEW.recovery THEN
        IF NOT ((NEW.disposition='REQUEUE' AND NEW.reason_code='REAUTH_FINANCE_CONCLUSIVE' AND state IN ('RECOVERY_PENDING','REVIEW_REQUIRED') AND c.active_attempt_id IS NULL AND c.fencing_token>a.fencing_token AND f125_reauth_conclusive(NEW.admission_id)) OR (state='OBSERVING' AND NEW.disposition='UNKNOWN' AND c.active_attempt_id=a.id AND c.lease_until<=clock_timestamp())
            OR (state='RECOVERY_PENDING' AND NEW.disposition='REVIEW' AND c.active_attempt_id IS NULL AND c.fencing_token>a.fencing_token)) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Recovery must fence expired/revoked work';
        END IF;
    ELSIF state<>'OBSERVING' OR c.active_attempt_id IS DISTINCT FROM a.id OR c.fencing_token<>a.fencing_token OR c.lease_until<=clock_timestamp() THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worker token/lease expired';
    END IF;
    NEW.local_digest=sha256(convert_to(f125_execution_local(c.order_id)::text,'UTF8'));NEW.recorded_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION f125_execution_admission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.execution_state='APPLYING' THEN
        IF OLD.execution_state<>'OBSERVING' OR NEW.applied_operation_id IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Applying requires observing owner'; END IF;
        PERFORM f125_edit_application_owner(NEW.id,(SELECT op.id FROM order_change_operations op
          WHERE op.order_id=NEW.order_id AND op.submission_key=NEW.submission_key AND op.application_xid=pg_current_xact_id()));
        RETURN NEW;
    ELSIF NEW.execution_state='COMPLETED' THEN
        IF OLD.execution_state<>'APPLYING' OR OLD.applied_operation_id IS NOT NULL OR NEW.applied_operation_id IS NULL
            OR NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x JOIN order_edit_execution_controls c ON c.admission_id=x.admission_id
                WHERE x.admission_id=NEW.id AND x.disposition='APPLIED' AND x.application_operation_id=NEW.applied_operation_id
                 AND x.recording_xid=pg_current_xact_id() AND c.active_attempt_id IS NULL AND c.lease_until IS NULL
                 AND c.version=x.control_version+1 AND c.fencing_token=x.fencing_token+1) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Completion requires atomic applied result and revocation'; END IF;
        RETURN NEW;
    END IF;
    IF OLD.execution_state='APPLYING' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Atomic application cannot park incomplete business work'; END IF;
    IF NEW.applied_operation_id IS DISTINCT FROM OLD.applied_operation_id THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application identity requires applying transition'; END IF;
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
    IF OLD.execution_state IN ('REVIEW_REQUIRED','RECOVERY_PENDING') AND NEW.execution_state='QUEUED' AND NOT (NEW.route='REAUTHORIZE' AND f125_reauth_conclusive(NEW.id) AND EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.admission_id=NEW.id AND x.disposition='REQUEUE' AND x.reason_code='REAUTH_FINANCE_CONCLUSIVE' AND x.recovery AND x.recording_xid=pg_current_xact_id())) THEN
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
                OR (OLD.execution_state='RECOVERY_PENDING' AND NEW.execution_state='REVIEW_REQUIRED')
                OR (OLD.execution_state IN ('RECOVERY_PENDING','REVIEW_REQUIRED') AND NEW.execution_state='QUEUED' AND NEW.route='REAUTHORIZE' AND f125_reauth_conclusive(NEW.id) AND EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.admission_id=NEW.id AND x.recovery AND x.disposition='REQUEUE' AND x.reason_code='REAUTH_FINANCE_CONCLUSIVE' AND x.recording_xid=pg_current_xact_id()))))
        ) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Illegal admission state transition'; END IF;
        NEW.updated_at=clock_timestamp();
        NEW.closed_at=CASE WHEN NEW.execution_state IN ('FAILED_UNAPPLIED','CLOSED_UNAPPLIED') THEN (SELECT recorded_at FROM order_edit_unapplied_evidences WHERE admission_id=NEW.id) WHEN NEW.execution_state='COMPLETED' OR NEW.admission_state='REJECTED_LATE' THEN NEW.updated_at ELSE NULL END;
    END IF;
    RETURN NEW;
END;
$$;
DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND proname IN ('f125_reauth_ready','f125_reauth_conclusive','f125_reauth_resume','f125_execution_result_guard','f125_execution_admission_guard','f125_admission_row_guard') LOOP
 EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
 EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);END LOOP;
END; $$;
