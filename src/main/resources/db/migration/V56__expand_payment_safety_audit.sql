-- Expand only. Empty audit storage: no backfill, financial permits, guard activation or legacy writes.
-- Application roles must not own these tables or have permission to disable triggers.
CREATE TABLE payment_safety_classification_runs (
    id UUID CONSTRAINT pk_ps_runs PRIMARY KEY,
    request_digest VARCHAR(64) COLLATE "C" NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    execution_type VARCHAR(24) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    guard_activation_ref VARCHAR(128),
    classification_contract_version VARCHAR(64) NOT NULL,
    classifier_impl_version VARCHAR(128) NOT NULL,
    fingerprint_format_version SMALLINT NOT NULL CHECK (fingerprint_format_version > 0),
    contract_manifest_digest VARCHAR(64) COLLATE "C" NOT NULL CHECK (contract_manifest_digest ~ '^[0-9a-f]{64}$'),
    contract_manifest JSONB NOT NULL,
    scope_kind VARCHAR(24) NOT NULL,
    lower_order_id_exclusive BIGINT NOT NULL,
    upper_order_id_inclusive BIGINT NOT NULL,
    cursor_order_id BIGINT NOT NULL,
    target_order_id BIGINT,
    resume_of_run_id UUID CONSTRAINT fk_ps_runs_resume REFERENCES payment_safety_classification_runs(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL,
    lease_owner UUID,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    processed_order_count BIGINT NOT NULL DEFAULT 0,
    classified_order_count BIGINT NOT NULL DEFAULT 0,
    review_order_count BIGINT NOT NULL DEFAULT 0,
    acquisition_failure_attempt_count BIGINT NOT NULL DEFAULT 0,
    assembly_failure_attempt_count BIGINT NOT NULL DEFAULT 0,
    persistence_failure_attempt_count BIGINT NOT NULL DEFAULT 0,
    interrupted_attempt_count BIGINT NOT NULL DEFAULT 0,
    retry_attempt_count BIGINT NOT NULL DEFAULT 0,
    unresolved_failure_order_count BIGINT NOT NULL DEFAULT 0,
    not_found_order_count BIGINT NOT NULL DEFAULT 0,
    skipped_order_count BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    last_error_summary VARCHAR(1000),
    requested_by_type VARCHAR(16) NOT NULL,
    requested_by_account_id BIGINT,
    requested_by_username VARCHAR(100) NOT NULL CHECK (btrim(requested_by_username) <> ''),
    CONSTRAINT uq_ps_runs_contract UNIQUE (id,classification_contract_version,classifier_impl_version,fingerprint_format_version,contract_manifest_digest),
    CONSTRAINT chk_ps_runs_status CHECK (status IN ('RUNNING','PAUSED','SUCCESS','PARTIAL_FAILURE','FAILED')),
    CONSTRAINT chk_ps_runs_mode CHECK (mode IN ('OBSERVE_ONLY','ENFORCED') AND
        ((mode='ENFORCED' AND guard_activation_ref IS NOT NULL AND btrim(guard_activation_ref)<>'') OR (mode='OBSERVE_ONLY' AND guard_activation_ref IS NULL))),
    CONSTRAINT chk_ps_runs_execution_type CHECK (execution_type IN ('DIAGNOSTIC','BACKFILL','MANUAL_RECLASSIFY','RETRY_FAILED')),
    CONSTRAINT chk_ps_runs_scope CHECK (
        (scope_kind='RANGE' AND target_order_id IS NULL AND resume_of_run_id IS NULL) OR
        (scope_kind='SINGLE' AND target_order_id IS NOT NULL AND target_order_id>0 AND lower_order_id_exclusive=target_order_id-1 AND upper_order_id_inclusive=target_order_id AND resume_of_run_id IS NULL) OR
        (scope_kind='FAILED_FROM_RUN' AND target_order_id IS NULL AND resume_of_run_id IS NOT NULL)),
    CONSTRAINT chk_ps_runs_cursor CHECK (0<=lower_order_id_exclusive AND lower_order_id_exclusive<=cursor_order_id AND cursor_order_id<=upper_order_id_inclusive),
    CONSTRAINT chk_ps_runs_lease CHECK ((status='RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND heartbeat_at IS NOT NULL AND lease_epoch>0) OR (status<>'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT chk_ps_runs_finished CHECK ((status IN ('SUCCESS','PARTIAL_FAILURE','FAILED')) = (finished_at IS NOT NULL)),
    CONSTRAINT chk_ps_runs_success CHECK (status<>'SUCCESS' OR (cursor_order_id=upper_order_id_inclusive AND unresolved_failure_order_count=0)),
    CONSTRAINT chk_ps_runs_counts CHECK (version>=0 AND lease_epoch>=0 AND processed_order_count>=0 AND classified_order_count>=0 AND review_order_count>=0 AND review_order_count<=classified_order_count AND classified_order_count<=processed_order_count AND acquisition_failure_attempt_count>=0 AND assembly_failure_attempt_count>=0 AND persistence_failure_attempt_count>=0 AND interrupted_attempt_count>=0 AND retry_attempt_count>=0 AND unresolved_failure_order_count>=0 AND not_found_order_count>=0 AND skipped_order_count>=0),
    CONSTRAINT chk_ps_runs_manifest CHECK ((jsonb_typeof(contract_manifest)='object' AND contract_manifest->'version'='1'::jsonb AND octet_length(contract_manifest::text)<=1048576) IS TRUE),
    CONSTRAINT chk_ps_runs_actor CHECK ((requested_by_type='ADMIN' AND requested_by_account_id IS NOT NULL AND requested_by_account_id>0) OR (requested_by_type='SYSTEM' AND requested_by_account_id IS NULL)),
    CONSTRAINT chk_ps_runs_resume_self CHECK (resume_of_run_id IS NULL OR resume_of_run_id<>id)
);
CREATE INDEX idx_ps_runs_status_lease ON payment_safety_classification_runs(status,lease_until);
CREATE INDEX idx_ps_runs_resume ON payment_safety_classification_runs(resume_of_run_id);
CREATE INDEX idx_ps_runs_started ON payment_safety_classification_runs(started_at,id);

-- JSON leaves are typed; SQL NULL is not a substitute for an explicit JSON null leaf.
-- Arrays retain order and duplicates. Hashes MUST be recomputed using the Java canonical encoder,
-- never by hashing jsonb::text. No payload or raw SQL error text is logged by these functions.
CREATE FUNCTION ps_valid_typed_value(v JSONB) RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE t TEXT; x JSONB; k TEXT; decoded BYTEA;
BEGIN
    IF jsonb_typeof(v) IS DISTINCT FROM 'object' OR NOT (v ? 'type' AND v ? 'value') OR (SELECT count(*) FROM jsonb_object_keys(v))<>2 THEN RETURN false; END IF;
    t := v->>'type'; x := v->'value';
    IF t IS NULL OR t NOT IN ('text','int64','int32','int16','timestamp','timestamptz','bytes','enum') THEN RETURN false; END IF;
    IF x='null'::jsonb THEN RETURN true; END IF;
    IF t IN ('text','enum') THEN RETURN jsonb_typeof(x)='string'; END IF;
    IF t IN ('int64','int32','int16') THEN
        IF jsonb_typeof(x)<>'string' OR (v->>'value') !~ '^(0|-?[1-9][0-9]*)$' THEN RETURN false; END IF;
        IF t='int64' THEN PERFORM (v->>'value')::BIGINT; ELSIF t='int32' THEN PERFORM (v->>'value')::INTEGER; ELSE PERFORM (v->>'value')::SMALLINT; END IF;
        RETURN true;
    END IF;
    IF t='timestamp' THEN
        RETURN jsonb_typeof(x)='string' AND (v->>'value') ~ '^[+-]?[0-9]{4,9}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?$';
    END IF;
    IF t='timestamptz' THEN
        IF jsonb_typeof(x)<>'object' OR NOT (x ? 'epochSecond' AND x ? 'nano') OR (SELECT count(*) FROM jsonb_object_keys(x))<>2 THEN RETURN false; END IF;
        IF jsonb_typeof(x->'epochSecond')<>'string' OR (x->>'epochSecond') !~ '^(0|-?[1-9][0-9]*)$' OR jsonb_typeof(x->'nano')<>'number' OR (x->>'nano') !~ '^[0-9]+$' THEN RETURN false; END IF;
        PERFORM (x->>'epochSecond')::BIGINT;
        RETURN (x->>'nano')::BIGINT BETWEEN 0 AND 999999999;
    END IF;
    IF t='bytes' THEN
        IF jsonb_typeof(x)<>'string' THEN RETURN false; END IF;
        decoded:=decode(v->>'value','base64');
        RETURN replace(encode(decoded,'base64'),E'\n','')=v->>'value';
    END IF;
    RETURN false;
EXCEPTION WHEN data_exception THEN RETURN false;
END;
$$;

-- Version 1 raw row fields mirror PaymentSafetyReadFacts exactly. Required keys cannot silently
-- disappear, but NULL/unknown business values remain representable for conservative classification.
CREATE FUNCTION ps_valid_raw_row(v JSONB, kind TEXT) RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE spec TEXT; entry TEXT; field_name TEXT; field_type TEXT; expected_count INTEGER:=0;
BEGIN
    spec := CASE kind
      WHEN 'order' THEN 'id:int64,userId:int64,status:text,totalAmount:int32,itemSubtotal:int32,chargeTotal:int32,taxAmount:int32,contentRevision:int32,orderedAt:timestamp,changeDeadlineAt:timestamp,paidAt:timestamp,cancelledAt:timestamp,shippedAt:timestamp,shippingPreparationStartedAt:timestamp,shippingPreparationStartedByAccountId:int64,shippingPreparationStartedByUsername:text,shippedByAccountId:int64,shippedByUsername:text,shipmentCapturePaymentId:int64,activePaymentId:int64'
      WHEN 'payments' THEN 'id:int64,orderId:int64,provider:text,paymentMethod:text,status:text,amount:int32,providerPaymentId:text,createdAt:timestamp,updatedAt:timestamp'
      WHEN 'transactions' THEN 'id:int64,paymentId:int64,transactionType:text,status:text,amount:int32,orderContentRevision:int32,idempotencyKey:text,providerTransactionId:text,failureCode:text,failureMessage:text,createdAt:timestamp,completedAt:timestamp,initiatorType:text,initiatorId:int64,initiatorUsername:text,internalNote:text,purpose:text'
      WHEN 'statusHistory' THEN 'id:int64,orderId:int64,fromStatus:text,toStatus:text,changedAt:timestamp,changedByType:text,changedByAccountId:int64,changedByUsername:text,internalNote:text'
      WHEN 'captureRequests' THEN 'id:int64,paymentId:int64,captureTransactionId:int64,transactionType:text,provider:text,paymentMethod:text,providerFlowId:text,connectionId:text,providerAccountRef:text,mode:text,apiOrigin:text,httpMethod:text,apiPath:text,contentType:text,amountToCapture:int32,orderContentRevision:int32,idempotencyKey:text,bodyFormatVersion:int16,requestBody:bytes,fingerprintVersion:int16,requestFingerprint:bytes,createdAt:timestamptz,replayNotAfter:timestamptz'
      ELSE NULL END;
    IF spec IS NULL OR jsonb_typeof(v) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
    FOREACH entry IN ARRAY string_to_array(spec,',') LOOP
        field_name:=split_part(entry,':',1);field_type:=split_part(entry,':',2);expected_count:=expected_count+1;
        IF NOT (ps_valid_typed_value(v->field_name) IS TRUE AND v->field_name->>'type'=field_type) THEN RETURN false; END IF;
    END LOOP;
    RETURN (SELECT count(*) FROM jsonb_object_keys(v))=expected_count;
END;
$$;
CREATE FUNCTION ps_valid_input_payload(v JSONB) RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE facts JSONB; name TEXT; row_value JSONB;
BEGIN
    IF NOT ((jsonb_typeof(v)='object' AND v->>'format'='payment-safety-input' AND v->'version'='1'::jsonb) IS TRUE) THEN RETURN false; END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(v))<>3 THEN RETURN false; END IF;
    facts:=v->'facts';
    IF jsonb_typeof(facts) IS DISTINCT FROM 'object' OR (SELECT count(*) FROM jsonb_object_keys(facts))<>7 OR NOT ps_valid_raw_row(facts->'order','order') THEN RETURN false; END IF;
    FOREACH name IN ARRAY ARRAY['payments','transactions','statusHistory','captureRequests'] LOOP
        IF jsonb_typeof(facts->name) IS DISTINCT FROM 'array' THEN RETURN false; END IF;
        FOR row_value IN SELECT value FROM jsonb_array_elements(facts->name) LOOP
            IF NOT ps_valid_raw_row(row_value,name) THEN RETURN false; END IF;
        END LOOP;
    END LOOP;
    -- Maps are arrays of key/value entries; the codec must reject duplicate map keys, not row duplicates.
    FOREACH name IN ARRAY ARRAY['verificationProfiles','timeOrigins'] LOOP
        IF jsonb_typeof(facts->name) IS DISTINCT FROM 'array' THEN RETURN false; END IF;
        FOR row_value IN SELECT value FROM jsonb_array_elements(facts->name) LOOP
            IF jsonb_typeof(row_value) IS DISTINCT FROM 'object' OR NOT (row_value ? 'key' AND row_value ? 'value') THEN RETURN false; END IF;
        END LOOP;
    END LOOP;
    RETURN true;
END;
$$;

CREATE TABLE payment_safety_assessments (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY CONSTRAINT pk_ps_assessments PRIMARY KEY,
    request_id UUID NOT NULL CONSTRAINT uq_ps_assessments_request UNIQUE,
    record_digest VARCHAR(64) COLLATE "C" NOT NULL CHECK (record_digest ~ '^[0-9a-f]{64}$'),
    order_id BIGINT NOT NULL CONSTRAINT fk_ps_assessments_order REFERENCES orders(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL CHECK (attempt_no>0),
    phase VARCHAR(16) NOT NULL,
    pre_assessment_id BIGINT,
    input_fingerprint VARCHAR(64) COLLATE "C" NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    fingerprint_format_version SMALLINT NOT NULL,
    classification_contract_version VARCHAR(64) NOT NULL,
    classifier_impl_version VARCHAR(128) NOT NULL,
    contract_manifest_digest VARCHAR(64) COLLATE "C" NOT NULL,
    disposition VARCHAR(16) NOT NULL,
    selected_payment_id BIGINT,
    financial_resumption VARCHAR(20) NOT NULL DEFAULT 'NOT_EVALUATED',
    observed_order_status VARCHAR(30),
    observed_order_total_amount INTEGER NOT NULL,
    observed_order_content_revision INTEGER NOT NULL,
    observed_active_payment_id BIGINT,
    capture_transaction_count INTEGER NOT NULL,
    capture_request_count INTEGER NOT NULL,
    capture_evidence_summary VARCHAR(20) NOT NULL,
    payload_format_version SMALLINT NOT NULL,
    input_payload JSONB NOT NULL,
    classification_details JSONB NOT NULL,
    capture_evidence_details JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_ps_assessments_owner UNIQUE (order_id,id),
    CONSTRAINT uq_ps_assessments_attempt UNIQUE (run_id,order_id,attempt_no,phase),
    CONSTRAINT uq_ps_assessments_run_owner UNIQUE (run_id,order_id,attempt_no,id),
    CONSTRAINT fk_ps_assessments_contract FOREIGN KEY (run_id,classification_contract_version,classifier_impl_version,fingerprint_format_version,contract_manifest_digest) REFERENCES payment_safety_classification_runs(id,classification_contract_version,classifier_impl_version,fingerprint_format_version,contract_manifest_digest) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ps_assessments_selected_owner FOREIGN KEY (order_id,selected_payment_id) REFERENCES payments(order_id,id) MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ps_assessments_pre_owner FOREIGN KEY (run_id,order_id,attempt_no,pre_assessment_id) REFERENCES payment_safety_assessments(run_id,order_id,attempt_no,id) MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ps_assessments_phase CHECK (phase IN ('OBSERVED','PRE_APPLY','POST_APPLY') AND ((phase='POST_APPLY')=(pre_assessment_id IS NOT NULL)) AND (pre_assessment_id IS NULL OR pre_assessment_id<>id)),
    CONSTRAINT chk_ps_assessments_disposition CHECK (disposition IN ('SELECTED','NORMAL_NULL','REVIEW')),
    CONSTRAINT chk_ps_assessments_selection CHECK ((disposition='SELECTED')=(selected_payment_id IS NOT NULL)),
    CONSTRAINT chk_ps_assessments_financial CHECK (financial_resumption='NOT_EVALUATED'),
    CONSTRAINT chk_ps_assessments_capture CHECK (capture_transaction_count>=0 AND capture_request_count>=0 AND capture_evidence_summary IN ('VERIFIED','UNVERIFIED','MISSING','MISMATCH','NOT_APPLICABLE') AND ((capture_evidence_summary='NOT_APPLICABLE')=(capture_transaction_count=0 AND capture_request_count=0))),
    -- 128 MiB is provisional serialized JSON capacity, not a permission to raise the reader's 16 MiB raw limit.
    CONSTRAINT chk_ps_assessments_payload_size CHECK (octet_length(input_payload::text)::BIGINT+octet_length(classification_details::text)::BIGINT+octet_length(capture_evidence_details::text)::BIGINT<=134217728),
    CONSTRAINT chk_ps_assessments_payload CHECK ((payload_format_version=1 AND ps_valid_input_payload(input_payload) AND jsonb_typeof(classification_details)='object' AND classification_details->'version'='1'::jsonb AND jsonb_typeof(classification_details->'reviewIssues')='array' AND jsonb_typeof(capture_evidence_details)='object' AND capture_evidence_details->'version'='1'::jsonb) IS TRUE),
    CONSTRAINT chk_ps_assessments_review CHECK (((disposition='REVIEW')=(jsonb_array_length(classification_details->'reviewIssues')>0)) IS TRUE)
);
CREATE INDEX idx_ps_assessments_order ON payment_safety_assessments(order_id,created_at,id);
CREATE INDEX idx_ps_assessments_run ON payment_safety_assessments(run_id,order_id);
CREATE INDEX idx_ps_assessments_review ON payment_safety_assessments(order_id,id) WHERE disposition='REVIEW';

CREATE TABLE payment_safety_review_cases (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY CONSTRAINT pk_ps_cases PRIMARY KEY,
    case_uuid UUID NOT NULL CONSTRAINT uq_ps_cases_uuid UNIQUE,
    order_id BIGINT NOT NULL CONSTRAINT fk_ps_cases_order REFERENCES orders(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    previous_case_id BIGINT,
    first_assessment_id BIGINT NOT NULL,
    latest_assessment_id BIGINT NOT NULL,
    open_reasons JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    handling_status VARCHAR(24) NOT NULL DEFAULT 'UNASSIGNED',
    version BIGINT NOT NULL DEFAULT 0,
    guard_activation_ref VARCHAR(128) NOT NULL CHECK (btrim(guard_activation_ref)<>''),
    assigned_admin_account_id BIGINT CONSTRAINT fk_ps_cases_assignee REFERENCES admin_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    assigned_admin_username VARCHAR(100),
    assigned_at TIMESTAMPTZ,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    resolved_at TIMESTAMPTZ,
    resolved_by_account_id BIGINT,
    resolved_by_username VARCHAR(100),
    resolution_code VARCHAR(64),
    resolution_comment TEXT,
    CONSTRAINT uq_ps_cases_owner UNIQUE (order_id,id),
    CONSTRAINT fk_ps_cases_first_owner FOREIGN KEY (order_id,first_assessment_id) REFERENCES payment_safety_assessments(order_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ps_cases_latest_owner FOREIGN KEY (order_id,latest_assessment_id) REFERENCES payment_safety_assessments(order_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ps_cases_previous_owner FOREIGN KEY (order_id,previous_case_id) REFERENCES payment_safety_review_cases(order_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ps_cases_status CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT chk_ps_cases_handling CHECK (handling_status IN ('UNASSIGNED','ASSIGNED','IN_PROGRESS','WAITING_EXTERNAL','COMPLETED')),
    CONSTRAINT chk_ps_cases_assignment CHECK ((assigned_admin_account_id IS NULL AND assigned_admin_username IS NULL AND assigned_at IS NULL) OR (assigned_admin_account_id IS NOT NULL AND assigned_admin_username IS NOT NULL AND btrim(assigned_admin_username)<>'' AND assigned_at IS NOT NULL)),
    CONSTRAINT chk_ps_cases_resolution CHECK ((status='OPEN' AND resolved_at IS NULL AND resolved_by_account_id IS NULL AND resolved_by_username IS NULL AND resolution_code IS NULL AND resolution_comment IS NULL AND ((handling_status='UNASSIGNED' AND assigned_admin_account_id IS NULL) OR (handling_status IN ('ASSIGNED','IN_PROGRESS','WAITING_EXTERNAL') AND assigned_admin_account_id IS NOT NULL))) OR (status='RESOLVED' AND handling_status='COMPLETED' AND resolved_at IS NOT NULL AND resolved_by_account_id IS NOT NULL AND resolved_by_account_id>0 AND resolved_by_username IS NOT NULL AND btrim(resolved_by_username)<>'' AND resolution_code IS NOT NULL AND btrim(resolution_code)<>'' AND resolution_comment IS NOT NULL AND btrim(resolution_comment)<>'' AND length(resolution_comment)<=2000)),
    CONSTRAINT chk_ps_cases_reasons CHECK ((jsonb_typeof(open_reasons)='array' AND jsonb_array_length(open_reasons)>0 AND octet_length(open_reasons::text)<=16384) IS TRUE),
    CONSTRAINT chk_ps_cases_version CHECK (version>=0),
    CONSTRAINT chk_ps_cases_previous_self CHECK (previous_case_id IS NULL OR previous_case_id<>id)
);
CREATE UNIQUE INDEX uq_ps_cases_open_order ON payment_safety_review_cases(order_id) WHERE status='OPEN';
CREATE UNIQUE INDEX uq_ps_cases_root_order ON payment_safety_review_cases(order_id) WHERE previous_case_id IS NULL;
CREATE UNIQUE INDEX uq_ps_cases_successor ON payment_safety_review_cases(previous_case_id) WHERE previous_case_id IS NOT NULL;
CREATE INDEX idx_ps_cases_queue ON payment_safety_review_cases(status,handling_status,updated_at,id);
CREATE INDEX idx_ps_cases_assignee ON payment_safety_review_cases(assigned_admin_account_id,status);
CREATE INDEX idx_ps_cases_latest ON payment_safety_review_cases(latest_assessment_id);

-- Case audit snapshots use exact epoch seconds (numeric, microsecond precision) for the
-- four TIMESTAMPTZ columns. Never compare session-TimeZone-dependent to_jsonb timestamps
-- across transactions. This does not infer any origin for legacy business TIMESTAMPs.
CREATE FUNCTION ps_case_snapshot(c payment_safety_review_cases) RETURNS JSONB
LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT to_jsonb(c) || jsonb_build_object(
        'opened_at',extract(epoch FROM (c).opened_at),
        'updated_at',extract(epoch FROM (c).updated_at),
        'assigned_at',extract(epoch FROM (c).assigned_at),
        'resolved_at',extract(epoch FROM (c).resolved_at));
$$;

CREATE TABLE payment_safety_audit_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY CONSTRAINT pk_ps_events PRIMARY KEY,
    event_uuid UUID NOT NULL CONSTRAINT uq_ps_events_uuid UNIQUE,
    record_digest VARCHAR(64) COLLATE "C" NOT NULL CHECK (record_digest ~ '^[0-9a-f]{64}$'),
    event_scope VARCHAR(8) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    run_id UUID NOT NULL CONSTRAINT fk_ps_events_run REFERENCES payment_safety_classification_runs(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    case_id BIGINT,
    order_id BIGINT,
    target_order_id BIGINT,
    attempt_no INTEGER,
    lease_epoch BIGINT,
    assessment_id BIGINT,
    case_version_before BIGINT,
    case_version_after BIGINT,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    outcome VARCHAR(32),
    actor_type VARCHAR(16) NOT NULL,
    actor_account_id BIGINT,
    actor_username VARCHAR(100) NOT NULL CHECK (btrim(actor_username)<>''),
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    comment TEXT CHECK (length(comment)<=2000),
    error_stage VARCHAR(24),
    error_code VARCHAR(64),
    sql_state VARCHAR(5),
    error_summary VARCHAR(1000),
    payload_format_version SMALLINT NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT fk_ps_events_case_owner FOREIGN KEY (order_id,case_id) REFERENCES payment_safety_review_cases(order_id,id) MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ps_events_assessment_owner FOREIGN KEY (run_id,order_id,attempt_no,assessment_id) REFERENCES payment_safety_assessments(run_id,order_id,attempt_no,id) MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_ps_events_scope CHECK (event_scope IN ('CASE','RUN')),
    CONSTRAINT chk_ps_events_case_shape CHECK (event_scope<>'CASE' OR (event_type IN ('OPENED','REOPENED','UPDATED','RESOLVED') AND case_id IS NOT NULL AND order_id IS NOT NULL AND assessment_id IS NOT NULL AND attempt_no IS NOT NULL AND attempt_no>0 AND case_version_after IS NOT NULL AND case_version_after>=0 AND target_order_id IS NULL AND lease_epoch IS NULL AND outcome IS NULL AND to_status IS NOT NULL AND ((event_type IN ('OPENED','REOPENED') AND case_version_before IS NULL AND case_version_after=0 AND from_status IS NULL AND to_status='OPEN') OR (event_type IN ('UPDATED','RESOLVED') AND case_version_before IS NOT NULL AND case_version_before>=0 AND case_version_after=case_version_before+1 AND from_status='OPEN' AND to_status=CASE WHEN event_type='RESOLVED' THEN 'RESOLVED' ELSE 'OPEN' END)))),
    CONSTRAINT chk_ps_events_run_shape CHECK (event_scope<>'RUN' OR (case_id IS NULL AND case_version_before IS NULL AND case_version_after IS NULL AND from_status IS NULL AND to_status IS NULL AND ((event_type IN ('STARTED','PAUSED','RESUMED','LEASE_TAKEN','FINISHED') AND target_order_id IS NULL AND attempt_no IS NULL AND assessment_id IS NULL AND order_id IS NULL AND outcome IS NULL) OR (event_type IN ('ORDER_STARTED','ORDER_FINISHED') AND target_order_id IS NOT NULL AND target_order_id>0 AND attempt_no IS NOT NULL AND attempt_no>0 AND lease_epoch IS NOT NULL AND lease_epoch>0)))),
    CONSTRAINT chk_ps_events_outcome CHECK (event_scope<>'RUN' OR (event_type<>'ORDER_FINISHED' AND outcome IS NULL AND assessment_id IS NULL AND order_id IS NULL) OR (event_type='ORDER_FINISHED' AND outcome IS NOT NULL AND ((outcome='CLASSIFIED' AND assessment_id IS NOT NULL AND order_id IS NOT NULL AND order_id=target_order_id) OR (outcome IN ('SKIPPED_UNCHANGED','NOT_FOUND','ACQUISITION_FAILED','ASSEMBLY_FAILED','PERSISTENCE_FAILED','INTERRUPTED') AND assessment_id IS NULL AND order_id IS NULL)))),
    CONSTRAINT chk_ps_events_actor CHECK ((actor_type='ADMIN' AND actor_account_id IS NOT NULL AND actor_account_id>0) OR (actor_type='SYSTEM' AND actor_account_id IS NULL)),
    CONSTRAINT chk_ps_events_resolver CHECK (event_scope<>'CASE' OR event_type<>'RESOLVED' OR actor_type='ADMIN'),
    CONSTRAINT chk_ps_events_payload CHECK ((payload_format_version=1 AND jsonb_typeof(payload)='object' AND payload->'version'='1'::jsonb AND octet_length(payload::text)<=262144) IS TRUE)
);
-- CASE versions, not all events with a case_id. Every CASE event represents exactly one mutation.
CREATE UNIQUE INDEX uq_ps_events_case_version ON payment_safety_audit_events(case_id,case_version_after) WHERE event_scope='CASE';
-- Including event_type is essential: ORDER_STARTED and ORDER_FINISHED must coexist.
CREATE UNIQUE INDEX uq_ps_events_order_attempt ON payment_safety_audit_events(run_id,target_order_id,attempt_no,event_type) WHERE event_scope='RUN' AND event_type IN ('ORDER_STARTED','ORDER_FINISHED');
CREATE UNIQUE INDEX uq_ps_events_order_finished ON payment_safety_audit_events(run_id,target_order_id,attempt_no) WHERE event_scope='RUN' AND event_type='ORDER_FINISHED';
CREATE UNIQUE INDEX uq_ps_events_order_accepted ON payment_safety_audit_events(run_id,target_order_id) WHERE event_scope='RUN' AND event_type='ORDER_FINISHED' AND outcome IN ('CLASSIFIED','SKIPPED_UNCHANGED','NOT_FOUND');
CREATE INDEX idx_ps_events_case ON payment_safety_audit_events(case_id,case_version_after);
CREATE INDEX idx_ps_events_run ON payment_safety_audit_events(run_id,id);
CREATE INDEX idx_ps_events_target ON payment_safety_audit_events(target_order_id,recorded_at,id);

CREATE FUNCTION ps_reject_immutable_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Payment safety audit records are immutable'; END;
$$;
CREATE FUNCTION ps_reject_delete_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Payment safety lifecycle records cannot be deleted'; END;
$$;
CREATE FUNCTION ps_validate_run_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE mutable_keys TEXT[]:=ARRAY['status','lease_owner','lease_epoch','lease_until','heartbeat_at','version','updated_at','finished_at','cursor_order_id','processed_order_count','classified_order_count','review_order_count','acquisition_failure_attempt_count','assembly_failure_attempt_count','persistence_failure_attempt_count','interrupted_attempt_count','retry_attempt_count','unresolved_failure_order_count','not_found_order_count','skipped_order_count','last_error_code','last_error_summary'];
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'RUNNING' OR NEW.version<>0 OR NEW.cursor_order_id<>NEW.lower_order_id_exclusive OR NEW.lease_epoch<>1 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid initial run'; END IF;
    ELSE
        IF to_jsonb(NEW)-mutable_keys IS DISTINCT FROM to_jsonb(OLD)-mutable_keys OR NEW.version<>OLD.version+1 OR OLD.status IN ('SUCCESS','PARTIAL_FAILURE','FAILED') OR NEW.cursor_order_id<OLD.cursor_order_id THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid run mutation'; END IF;
        IF NOT ((OLD.status='RUNNING' AND NEW.status IN ('RUNNING','PAUSED','SUCCESS','PARTIAL_FAILURE','FAILED')) OR (OLD.status='PAUSED' AND NEW.status='RUNNING')) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid run transition'; END IF;
        IF NEW.status='RUNNING' THEN
            IF OLD.status='PAUSED' OR NEW.lease_owner IS DISTINCT FROM OLD.lease_owner OR NEW.lease_epoch IS DISTINCT FROM OLD.lease_epoch THEN
                IF NEW.lease_epoch<>OLD.lease_epoch+1 OR (OLD.status='RUNNING' AND OLD.lease_until>clock_timestamp()) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid lease takeover'; END IF;
            ELSIF NEW.lease_epoch<>OLD.lease_epoch THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid lease epoch'; END IF;
        ELSIF NEW.lease_epoch<>OLD.lease_epoch THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid stopped lease epoch'; END IF;
        NEW.updated_at:=clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION ps_validate_assessment_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent payment_safety_assessments%ROWTYPE;
BEGIN
    IF NEW.phase='POST_APPLY' AND NEW.pre_assessment_id IS NOT NULL THEN
        SELECT * INTO parent FROM payment_safety_assessments WHERE id=NEW.pre_assessment_id;
        IF FOUND AND parent.phase<>'PRE_APPLY' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='POST_APPLY requires PRE_APPLY'; END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION ps_validate_case_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent payment_safety_review_cases%ROWTYPE; a payment_safety_assessments%ROWTYPE; r payment_safety_classification_runs%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.status<>'OPEN' OR NEW.first_assessment_id<>NEW.latest_assessment_id THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid initial case'; END IF;
        IF NEW.previous_case_id IS NOT NULL THEN
            SELECT * INTO parent FROM payment_safety_review_cases WHERE id=NEW.previous_case_id;
            IF FOUND AND parent.status<>'RESOLVED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Previous case must be resolved'; END IF;
        END IF;
    ELSE
        IF OLD.status='RESOLVED' THEN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Resolved cases are immutable'; END IF;
        IF NEW.version<>OLD.version+1 OR ROW(NEW.id,NEW.case_uuid,NEW.order_id,NEW.previous_case_id,NEW.first_assessment_id,NEW.open_reasons,NEW.guard_activation_ref,NEW.opened_at) IS DISTINCT FROM ROW(OLD.id,OLD.case_uuid,OLD.order_id,OLD.previous_case_id,OLD.first_assessment_id,OLD.open_reasons,OLD.guard_activation_ref,OLD.opened_at) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid case mutation'; END IF;
        NEW.updated_at:=clock_timestamp();
    END IF;
    SELECT * INTO a FROM payment_safety_assessments WHERE id=NEW.latest_assessment_id;
    IF FOUND THEN
        SELECT * INTO r FROM payment_safety_classification_runs WHERE id=a.run_id;
        IF r.mode IS DISTINCT FROM 'ENFORCED' OR r.guard_activation_ref IS DISTINCT FROM NEW.guard_activation_ref THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='An enforced assessment and activation reference are required'; END IF;
    END IF;
    -- This validates recorded intent only, NOT that every deployed instance has the Guard.
    RETURN NEW;
END;
$$;
CREATE FUNCTION ps_validate_event_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.event_scope='CASE' THEN
        IF NOT (NEW.payload ? 'before' AND NEW.payload ? 'after') OR jsonb_typeof(NEW.payload->'after') IS DISTINCT FROM 'object' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing case snapshots'; END IF;
        IF NEW.case_version_after=0 AND NEW.payload->'before'<>'null'::jsonb THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Initial case has no before snapshot'; END IF;
        IF NEW.case_version_after>0 AND jsonb_typeof(NEW.payload->'before') IS DISTINCT FROM 'object' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing before snapshot'; END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION ps_require_case_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e payment_safety_audit_events%ROWTYPE; prior JSONB;
BEGIN
    -- OLD/NEW are the queued row versions, never replaced by the final case row.
    prior:=CASE WHEN TG_OP='INSERT' THEN 'null'::jsonb ELSE ps_case_snapshot(OLD) END;
    SELECT * INTO e FROM payment_safety_audit_events WHERE event_scope='CASE' AND case_id=NEW.id AND case_version_after=NEW.version;
    IF NOT FOUND OR e.payload->'before' IS DISTINCT FROM prior OR e.payload->'after' IS DISTINCT FROM ps_case_snapshot(NEW) OR e.assessment_id IS DISTINCT FROM NEW.latest_assessment_id OR e.order_id IS DISTINCT FROM NEW.order_id OR e.from_status IS DISTINCT FROM (CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.status END) OR e.to_status IS DISTINCT FROM NEW.status THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Case mutation requires its exact audit event';
    END IF;
    IF NEW.version=0 AND e.event_type IS DISTINCT FROM (CASE WHEN NEW.previous_case_id IS NULL THEN 'OPENED' ELSE 'REOPENED' END) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid opening event'; END IF;
    IF NEW.status='RESOLVED' AND (e.actor_type<>'ADMIN' OR e.actor_account_id IS DISTINCT FROM NEW.resolved_by_account_id OR e.actor_username IS DISTINCT FROM NEW.resolved_by_username) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Resolution actor mismatch'; END IF;
    RETURN NULL;
END;
$$;
CREATE FUNCTION ps_require_event_case_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c payment_safety_review_cases%ROWTYPE; prior JSONB;
BEGIN
    IF NEW.event_scope<>'CASE' THEN RETURN NULL; END IF;
    SELECT * INTO c FROM payment_safety_review_cases WHERE id=NEW.case_id;
    IF NOT FOUND OR NEW.case_version_after>c.version OR NEW.order_id IS DISTINCT FROM c.order_id THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Event without case mutation'; END IF;
    IF NEW.case_version_after=c.version AND NEW.payload->'after' IS DISTINCT FROM ps_case_snapshot(c) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Final case snapshot mismatch'; END IF;
    IF NEW.case_version_after>0 THEN
        SELECT payload->'after' INTO prior FROM payment_safety_audit_events WHERE event_scope='CASE' AND case_id=c.id AND case_version_after=NEW.case_version_before;
        IF NOT FOUND OR prior IS DISTINCT FROM NEW.payload->'before' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Broken case event chain'; END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER ps_runs_validate BEFORE INSERT OR UPDATE ON payment_safety_classification_runs FOR EACH ROW EXECUTE FUNCTION ps_validate_run_change();
CREATE TRIGGER ps_runs_no_delete BEFORE DELETE OR TRUNCATE ON payment_safety_classification_runs FOR EACH STATEMENT EXECUTE FUNCTION ps_reject_delete_truncate();
CREATE TRIGGER ps_assessments_validate BEFORE INSERT ON payment_safety_assessments FOR EACH ROW EXECUTE FUNCTION ps_validate_assessment_insert();
CREATE TRIGGER ps_assessments_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON payment_safety_assessments FOR EACH STATEMENT EXECUTE FUNCTION ps_reject_immutable_mutation();
CREATE TRIGGER ps_cases_validate BEFORE INSERT OR UPDATE ON payment_safety_review_cases FOR EACH ROW EXECUTE FUNCTION ps_validate_case_change();
CREATE TRIGGER ps_cases_no_delete BEFORE DELETE OR TRUNCATE ON payment_safety_review_cases FOR EACH STATEMENT EXECUTE FUNCTION ps_reject_delete_truncate();
CREATE TRIGGER ps_events_validate BEFORE INSERT ON payment_safety_audit_events FOR EACH ROW EXECUTE FUNCTION ps_validate_event_insert();
CREATE TRIGGER ps_events_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON payment_safety_audit_events FOR EACH STATEMENT EXECUTE FUNCTION ps_reject_immutable_mutation();
CREATE CONSTRAINT TRIGGER ps_cases_require_event AFTER INSERT OR UPDATE ON payment_safety_review_cases DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ps_require_case_event();
CREATE CONSTRAINT TRIGGER ps_events_require_case_change AFTER INSERT ON payment_safety_audit_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ps_require_event_case_change();
