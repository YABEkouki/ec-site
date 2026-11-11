-- Isolated-test retained authorization applier. No provider IO or production activation.
-- Canonical entry points lock upstream; guards only inspect rows already owned by the caller.
ALTER TABLE order_change_operations ADD COLUMN application_xid XID8;
ALTER TABLE order_edit_execution_attempt_results DROP CONSTRAINT order_edit_execution_attempt_results_disposition_check;
ALTER TABLE order_edit_execution_attempt_results ADD CONSTRAINT order_edit_execution_attempt_results_disposition_check
 CHECK(disposition IN ('REQUEUE','CLOSE','FAIL','REVIEW','UNKNOWN','APPLIED'));
CREATE OR REPLACE FUNCTION f125_execution_local(owner_id BIGINT) RETURNS JSONB LANGUAGE SQL STABLE AS $$
    SELECT jsonb_build_object(
      'order',(SELECT to_jsonb(o) FROM orders o WHERE o.id=owner_id),
      'items',coalesce((SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM order_items i WHERE order_id=owner_id),'[]'),
      'charges',coalesce((SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM order_charges c WHERE order_id=owner_id),'[]'),
      'payments',coalesce((SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payments p WHERE order_id=owner_id),'[]'),
      'transactions',coalesce((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.payment_id,t.id) FROM payment_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.order_id=owner_id),'[]'),
      'operations',coalesce((SELECT jsonb_agg((to_jsonb(c)-'application_xid') ORDER BY id) FROM order_change_operations c WHERE order_id=owner_id),'[]'),
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
ALTER FUNCTION f125_execution_local(BIGINT) SET timezone='UTC';
ALTER FUNCTION f125_execution_local(BIGINT) SET datestyle='ISO, YMD';

CREATE FUNCTION f125_edit_application_observation(target UUID, observation BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; e order_payment_evidence_epochs%ROWTYPE; j JSONB; original JSONB; started TIMESTAMPTZ; finished TIMESTAMPTZ;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE id=r.base_evidence_epoch_id;
 IF observation IS NULL OR octet_length(observation) NOT BETWEEN 1 AND 2097152 THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing application observation'; END IF;
 j=convert_from(observation,'UTF8')::jsonb; original=convert_from(e.observation_payload,'UTF8')::jsonb;
 started=(j->>'startedAt')::timestamptz;finished=(j->>'finishedAt')::timestamptz;
 IF j->'version' IS DISTINCT FROM '1'::jsonb OR j->>'flowId' IS DISTINCT FROM e.provider_flow_id
  OR j->'amount' IS DISTINCT FROM to_jsonb(r.authorized_amount) OR j->'amountCapturable' IS DISTINCT FROM to_jsonb(r.authorized_amount)
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
  OR j->>'authorizationOperationId' IS DISTINCT FROM (SELECT order_change_operation_id::text FROM payment_transactions WHERE id=r.base_authorization_id)
  OR started IS NULL OR finished IS NULL OR started>finished OR finished>clock_timestamp() OR started<clock_timestamp()-interval '5 seconds'
  OR (j->>'expiredAt' IS NOT NULL AND (j->>'expiredAt')::timestamptz<=clock_timestamp()) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application observation target or freshness mismatch'; END IF;
END;
$$;

CREATE FUNCTION f125_edit_application_owner(target UUID, operation UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; c order_edit_execution_controls%ROWTYPE; op order_change_operations%ROWTYPE;
BEGIN
 PERFORM f125_execution_check_runtime(target);PERFORM f125_financial_assert_saved(target);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=operation;
 IF r.admission_state<>'ACCEPTED' OR r.closed_at IS NOT NULL OR r.execution_state NOT IN ('OBSERVING','APPLYING')
  OR op.application_xid IS DISTINCT FROM pg_current_xact_id()
  OR ROW(op.order_id,op.customer_id,op.submission_key,op.request_digest,op.execution_version,op.execution_route,
     op.base_content_revision,op.base_evidence_epoch_id,op.base_evidence_history_id,op.old_payment_id,
     op.old_authorization_transaction_id,op.old_authorized_amount,op.old_authorization_revision,op.before_amount,op.after_amount)
   IS DISTINCT FROM ROW(r.order_id,r.customer_id,r.submission_key,encode(r.request_digest,'hex'),1::smallint,'RETAIN_AUTH'::varchar,
     r.base_content_revision,r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_payment_id,
     r.base_authorization_id,r.authorized_amount,r.authorization_revision,r.before_amount,r.after_amount)
  OR NOT EXISTS(SELECT 1 FROM order_edit_execution_attempts a WHERE a.id=c.active_attempt_id AND a.admission_id=target
    AND a.order_id=r.order_id AND a.db_generation_id=r.db_generation_id AND c.db_generation_id=r.db_generation_id
    AND a.fencing_token=c.fencing_token AND a.control_version=c.version AND a.baseline_digest=c.baseline_digest
    AND a.lease_until=c.lease_until AND c.lease_until>clock_timestamp()
    AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application ownership or operation mismatch'; END IF;
 PERFORM f125_edit_application_observation(target,op.admission_observation_payload);
END;
$$;

CREATE FUNCTION f125_edit_application_begin(target UUID, attempt UUID, token BIGINT, expected_version BIGINT,
 operation UUID, observation BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; preview JSONB; shipping JSONB; accepted TIMESTAMP;
BEGIN
 PERFORM f125_edit_application_context(target,attempt,token,expected_version);
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
 clock_timestamp() AT TIME ZONE 'Asia/Tokyo',clock_timestamp() AT TIME ZONE 'Asia/Tokyo',1,'RETAIN_AUTH',
 r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_authorization_id,r.authorized_amount,
 r.authorization_revision,observation,sha256(observation),pg_current_xact_id());
 UPDATE order_edit_admissions SET execution_state='APPLYING',
 reason_code='APPLICATION_STARTED',version=version+1 WHERE id=target;
END;
$$;
CREATE FUNCTION f125_edit_application_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; c order_edit_execution_controls%ROWTYPE;
BEGIN
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
CREATE TRIGGER aa_edit_application_operation BEFORE INSERT OR UPDATE ON order_change_operations
 FOR EACH ROW EXECUTE FUNCTION f125_edit_application_operation_guard();

-- OrderEvidenceDigests uses int32(-1) for NULL and int32(length)+bytes otherwise.
-- Financial-record framing has a different discriminator and cannot encode this wire contract.
CREATE FUNCTION f125_edit_application_frame(value BYTEA) RETURNS BYTEA LANGUAGE SQL IMMUTABLE AS $$
 SELECT CASE WHEN value IS NULL THEN int4send(-1) ELSE int4send(octet_length(value))||value END
$$;
CREATE FUNCTION f125_edit_application_history_digest(history BIGINT, operation UUID) RETURNS BYTEA LANGUAGE SQL STABLE AS $$
 SELECT sha256(
  f125_edit_application_frame(convert_to('ec-site/order-evidence/change/v1','UTF8'))||
  f125_edit_application_frame(int4send(h.evidence_version::integer))||
  f125_edit_application_frame(int8send(h.order_id))||
  f125_edit_application_frame(convert_to(h.evidence_epoch_id::text,'UTF8'))||
  f125_edit_application_frame(int8send(h.id))||
  f125_edit_application_frame(convert_to(operation::text,'UTF8'))||
  f125_edit_application_frame(int8send(h.previous_history_id))||
  f125_edit_application_frame(int4send(h.before_content_revision))||
  f125_edit_application_frame(int4send(h.after_content_revision))||
  f125_edit_application_frame(int4send(h.old_total_amount))||
  f125_edit_application_frame(int4send(h.new_total_amount))||
  f125_edit_application_frame(h.before_fingerprint)||
  f125_edit_application_frame(h.after_fingerprint)||
  f125_edit_application_frame(CASE WHEN h.previous_history_id IS NULL THEN e.evidence_digest ELSE previous.evidence_digest END))
 FROM order_content_change_histories h JOIN order_payment_evidence_epochs e ON e.id=h.evidence_epoch_id AND e.order_id=h.order_id
 LEFT JOIN order_content_change_histories previous ON previous.id=h.previous_history_id
  AND previous.order_id=h.order_id AND previous.evidence_epoch_id=h.evidence_epoch_id
 WHERE h.id=history
$$;

CREATE FUNCTION f125_edit_application_snapshot(target UUID, operation UUID, history BIGINT, fingerprint BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; op order_change_operations%ROWTYPE; h order_content_change_histories%ROWTYPE;
 o orders%ROWTYPE; candidate JSONB; preview JSONB; actual JSONB; expected_items JSONB; actual_items JSONB; live_items JSONB; live_charges JSONB;
BEGIN
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT op FROM order_change_operations WHERE id=operation AND order_id=r.order_id;
 SELECT * INTO STRICT h FROM order_content_change_histories WHERE id=history AND order_id=r.order_id;
 SELECT * INTO STRICT o FROM orders WHERE id=r.order_id;
 candidate=convert_from(r.candidate_payload,'UTF8')::jsonb;preview=candidate->'preview';actual=convert_from(h.after_snapshot,'UTF8')::jsonb;
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
  OR ROW(h.evidence_version,h.evidence_epoch_id,h.previous_history_id,h.before_content_revision,h.after_content_revision,
      h.old_total_amount,h.new_total_amount,h.before_fingerprint,h.after_fingerprint)
   IS DISTINCT FROM ROW(1::smallint,r.base_evidence_epoch_id,r.base_evidence_history_id,r.base_content_revision,r.base_content_revision+1,
      r.before_amount,r.after_amount,r.base_fingerprint,fingerprint)
  OR ROW(op.item_subtotal,op.charge_total,op.tax_amount,op.shipping_name,op.shipping_amount,
      op.shipping_tax_category_id,op.shipping_tax_category_code,op.shipping_tax_category_name,op.shipping_tax_rate,op.shipping_display_order)
   IS DISTINCT FROM ROW((preview->>'itemSubtotal')::integer,(preview->>'chargeTotal')::integer,(preview->>'taxAmount')::integer,
      preview->'charges'->0->>'name',(preview->'charges'->0->>'amount')::integer,
      (preview->'charges'->0->>'taxCategoryId')::bigint,preview->'charges'->0->>'taxCategoryCode',
      preview->'charges'->0->>'taxCategoryName',(preview->'charges'->0->>'taxRateBasisPoints')::numeric/100,
      (preview->'charges'->0->>'displayOrder')::integer)
  OR h.after_fingerprint IS DISTINCT FROM sha256(h.after_snapshot)
  OR actual->'contentRevision' IS DISTINCT FROM to_jsonb(h.after_content_revision)
  OR h.evidence_digest IS DISTINCT FROM f125_edit_application_history_digest(history,operation)
  OR ROW(o.content_revision,o.current_evidence_epoch_id,o.evidence_head_history_id,o.active_payment_id,o.total_amount,o.item_subtotal,o.charge_total,o.tax_amount)
   IS DISTINCT FROM ROW(r.base_content_revision+1,r.base_evidence_epoch_id,history,r.base_payment_id,r.after_amount,
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
      AND p.stock=m.stock_after AND m.product_name=p.name
      AND m.changed_by_type='SYSTEM' AND m.changed_by_username='SYSTEM'
      AND m.movement_type=CASE WHEN m.quantity<0 THEN 'ORDER_CHANGE_RESERVATION' ELSE 'ORDER_CHANGE_RELEASE' END))
  OR (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(f125_execution_local(r.order_id)->'movements') j
       WHERE j->>'reason' IS DISTINCT FROM 'order_change='||operation::text) IS DISTINCT FROM
     (SELECT coalesce(jsonb_agg(j ORDER BY j->>'id'),'[]'::jsonb) FROM jsonb_array_elements(r.unapplied_baseline->'movements') j)
  OR (f125_execution_local(r.order_id)->'payments') IS DISTINCT FROM (r.unapplied_baseline->'payments')
  OR (f125_execution_local(r.order_id)->'transactions') IS DISTINCT FROM (r.unapplied_baseline->'transactions')
  OR (f125_execution_local(r.order_id)->'requests') IS DISTINCT FROM (r.unapplied_baseline->'requests')
  OR (f125_execution_local(r.order_id)->'dispatches') IS DISTINCT FROM (r.unapplied_baseline->'dispatches')
  OR (f125_execution_local(r.order_id)->'captures') IS DISTINCT FROM (r.unapplied_baseline->'captures') THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application final snapshot, candidate, history or retained finance mismatch'; END IF;
END;
$$;
CREATE OR REPLACE FUNCTION f125_edit_application_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.disposition='APPLIED' THEN
  IF NEW.application_version IS DISTINCT FROM 1 OR NEW.application_before_revision IS DISTINCT FROM
    (SELECT base_content_revision FROM order_edit_admissions WHERE id=NEW.admission_id)
   OR NEW.application_after_revision IS DISTINCT FROM NEW.application_before_revision+1 THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid applied result shape'; END IF;
  PERFORM f125_edit_application_owner(NEW.admission_id,NEW.application_operation_id);
  PERFORM f125_edit_application_snapshot(NEW.admission_id,NEW.application_operation_id,NEW.application_history_id,NEW.application_fingerprint);
 ELSIF num_nonnulls(NEW.application_version,NEW.application_operation_id,NEW.application_history_id,
    NEW.application_before_revision,NEW.application_after_revision,NEW.application_fingerprint)<>0 THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Nonapplication result cannot carry application proof';
 END IF;RETURN NEW;
END;
$$;

CREATE FUNCTION f125_edit_application_finish(target UUID, attempt UUID, token BIGINT, expected_version BIGINT,
 result UUID, operation UUID, history BIGINT, fingerprint BYTEA) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r order_edit_admissions%ROWTYPE; c order_edit_execution_controls%ROWTYPE;
BEGIN
 -- begin already owns upstream locks; reenter the canonical order before finishing.
 PERFORM f125_execution_lock(target);PERFORM f125_edit_application_owner(target,operation);
 SELECT * INTO STRICT r FROM order_edit_admissions WHERE id=target;
 SELECT * INTO STRICT c FROM order_edit_execution_controls WHERE admission_id=target;
 IF r.execution_state<>'APPLYING' OR r.applied_operation_id IS NOT NULL
  OR c.active_attempt_id IS DISTINCT FROM attempt OR c.fencing_token IS DISTINCT FROM token
  OR c.version IS DISTINCT FROM expected_version OR result IS NULL THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application finish identity/CAS mismatch'; END IF;
 INSERT INTO order_edit_execution_attempt_results(id,attempt_id,admission_id,order_id,fencing_token,control_version,
  recovery,disposition,reason_code,application_version,application_operation_id,application_history_id,
  application_before_revision,application_after_revision,application_fingerprint)
 VALUES(result,attempt,target,r.order_id,token,expected_version,false,'APPLIED','APPLICATION_COMPLETED',1,
  operation,history,r.base_content_revision,r.base_content_revision+1,fingerprint);
 UPDATE order_edit_execution_controls SET active_attempt_id=NULL,lease_until=NULL,fencing_token=fencing_token+1,
  version=version+1,updated_at=clock_timestamp() WHERE admission_id=target;
 UPDATE order_edit_admissions SET execution_state='COMPLETED',applied_operation_id=operation,reason_code='APPLICATION_COMPLETED',version=version+1 WHERE id=target;
END;
$$;

CREATE FUNCTION f125_edit_application_assert_final(target UUID) RETURNS VOID LANGUAGE plpgsql AS $$
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
  PERFORM f125_edit_application_observation(target,(SELECT admission_observation_payload FROM order_change_operations WHERE id=x.application_operation_id));
  PERFORM f125_edit_application_snapshot(target,x.application_operation_id,x.application_history_id,x.application_fingerprint);
  IF x.local_digest IS DISTINCT FROM sha256(convert_to(f125_execution_local(r.order_id)::text,'UTF8')) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business state changed after application result'; END IF;
 END IF;
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
CREATE FUNCTION f125_edit_application_operation_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID;
BEGIN
 IF NEW.application_xid IS NULL THEN RETURN NULL; END IF;
 SELECT id INTO STRICT target FROM order_edit_admissions WHERE order_id=NEW.order_id AND submission_key=NEW.submission_key;
 PERFORM f125_edit_application_assert_final(target);RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER edit_application_operation_final AFTER INSERT OR UPDATE ON order_change_operations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_edit_application_operation_final();

CREATE FUNCTION f125_edit_application_mutation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_owner BIGINT; new_owner BIGINT;
BEGIN
 IF TG_TABLE_NAME='products' AND TG_OP<>'TRUNCATE' THEN
  IF EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x JOIN stock_movements m
    ON m.order_id=x.order_id AND m.reason='order_change='||x.application_operation_id::text
    WHERE x.disposition='APPLIED' AND x.recording_xid=pg_current_xact_id()
      AND m.product_id IN ((to_jsonb(OLD)->>'id')::bigint,(to_jsonb(NEW)->>'id')::bigint)) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Product mutation after application result in same transaction'; END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
 END IF;
 IF TG_TABLE_NAME IN ('order_edit_runtime_control','order_edit_runtime_generations') OR TG_OP='TRUNCATE' THEN
  IF EXISTS(SELECT 1 FROM order_edit_execution_attempt_results WHERE disposition='APPLIED' AND recording_xid=pg_current_xact_id()) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application transaction cannot mutate runtime or truncate after result'; END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD WHEN TG_OP='TRUNCATE' THEN NULL ELSE NEW END;
 END IF;
 IF TG_OP<>'INSERT' THEN old_owner=f125_closure_owner(TG_TABLE_NAME,to_jsonb(OLD)); END IF;
 IF TG_OP<>'DELETE' THEN new_owner=f125_closure_owner(TG_TABLE_NAME,to_jsonb(NEW)); END IF;
 IF EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.disposition='APPLIED' AND x.recording_xid=pg_current_xact_id()
  AND (x.order_id IN (old_owner,new_owner) OR (TG_TABLE_NAME='stock_movements' AND EXISTS(
    SELECT 1 FROM inventory_reservations ir JOIN order_change_operations op ON op.id=ir.operation_id
    WHERE op.order_id=x.order_id AND (ir.hold_movement_id IN ((to_jsonb(OLD)->>'id')::bigint,(to_jsonb(NEW)->>'id')::bigint)
      OR ir.release_movement_id IN ((to_jsonb(OLD)->>'id')::bigint,(to_jsonb(NEW)->>'id')::bigint)))))) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Business mutation after application result in same transaction'; END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;
DO $$
DECLARE tbl TEXT; f RECORD;
BEGIN
 FOREACH tbl IN ARRAY ARRAY['orders','order_items','order_charges','order_content_change_histories','order_content_change_items','order_content_change_charges',
 'order_payment_evidence_epochs','payments','payment_transactions','order_change_operations','order_change_items','order_change_financial_requests',
 'order_change_request_dispatches','capture_requests','inventory_reservations','stock_movements','order_edit_runtime_control','order_edit_runtime_generations','products'] LOOP
  EXECUTE format('CREATE TRIGGER aa_edit_application_mutation BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION f125_edit_application_mutation_guard()',tbl);
  EXECUTE format('CREATE TRIGGER aa_edit_application_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION f125_edit_application_mutation_guard()',tbl);
 END LOOP;
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
  AND (proname LIKE 'f125_edit_application_%' OR proname IN ('f125_execution_local','f125_execution_result_guard','f125_execution_admission_guard','f125_execution_final_check')) LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END;
$$;
