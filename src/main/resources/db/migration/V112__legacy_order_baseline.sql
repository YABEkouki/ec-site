-- Feature143-A2: admissions accepted before V81 keep a baseline without the order columns V81 added.
-- Owned re-authorizations compare the current order with that baseline (V75), so such an admission could never be
-- closed or continued. Only those two columns, only with the values V81 gave every existing order, are left out of the
-- comparison, and only when the baseline does not have them. Any other difference is still refused.

CREATE FUNCTION f143_legacy_order_local(baseline JSONB, actual JSONB) RETURNS JSONB LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF baseline IS NULL OR actual IS NULL OR jsonb_typeof(baseline->'order') IS DISTINCT FROM 'object'
            OR jsonb_typeof(actual->'order') IS DISTINCT FROM 'object' THEN
        RETURN actual;
    END IF;
    IF (baseline->'order') ? 'acceptance_mode' OR (baseline->'order') ? 'accepted_at' THEN
        RETURN actual;
    END IF;
    IF (actual->'order'->'acceptance_mode') IS DISTINCT FROM '"LEGACY"'::jsonb
            OR (actual->'order'->'accepted_at') IS DISTINCT FROM 'null'::jsonb THEN
        RETURN actual;
    END IF;
    RETURN jsonb_set(actual, '{order}', (actual->'order') - 'acceptance_mode' - 'accepted_at');
END;
$$;

-- V75 with only the comparison basis changed.
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

-- True when the owned change still matches its admission; the closure is then offered only when it can be written.
CREATE FUNCTION f143_reauth_current(target UUID) RETURNS BOOLEAN LANGUAGE plpgsql VOLATILE AS $$
BEGIN
    -- A stopped runtime or another generation is answered as not closable, never as an error.
    IF NOT f125_runtime_available() OR NOT EXISTS (SELECT 1 FROM order_edit_admissions r JOIN order_edit_runtime_control rc
            ON rc.id = 1 AND rc.generation_id = r.db_generation_id WHERE r.id = target) THEN
        RETURN false;
    END IF;
    PERFORM f125_reauth_assert_current(target);
    RETURN true;
EXCEPTION WHEN check_violation THEN
    RETURN false;
END;
$$;

-- V111 with only the last condition added.
CREATE OR REPLACE FUNCTION f143_change_closable(target UUID) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
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
 AND f125_runtime_available() AND f143_reauth_current(r.id)
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

DO $$ DECLARE f RECORD; BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
 AND proname IN ('f143_legacy_order_local','f125_reauth_assert_current','f143_reauth_current','f143_change_closable') LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END $$;
