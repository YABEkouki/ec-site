-- Business representation only. No stock writer, order applier, payment call or activation.
-- Final lines/new-line keys remain in the immutable V71 candidate payload (no duplicate ledger).
ALTER TABLE order_edit_admissions ADD CONSTRAINT uq_edit_candidate_digest UNIQUE(id,candidate_digest);
CREATE TABLE order_edit_inventory_plans (
    admission_id UUID NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL REFERENCES products(id),
    candidate_digest BYTEA NOT NULL CHECK(octet_length(candidate_digest)=32),
    before_quantity INTEGER NOT NULL CHECK(before_quantity>=0),
    after_quantity INTEGER NOT NULL CHECK(after_quantity>=0),
    reserve_quantity INTEGER GENERATED ALWAYS AS (greatest(after_quantity::bigint-before_quantity,0)::integer) STORED,
    release_quantity INTEGER GENERATED ALWAYS AS (greatest(before_quantity::bigint-after_quantity,0)::integer) STORED,
    -- Reserved storage contract: the future stock writer must supply movement/ownership checks.
    -- The INSERT guard and immutable trigger deliberately keep these NULL in this phase.
    operation_id UUID,
    reservation_state VARCHAR(10),
    hold_movement_id BIGINT UNIQUE REFERENCES stock_movements(id),
    release_movement_id BIGINT UNIQUE REFERENCES stock_movements(id),
    held_at TIMESTAMP,
    consumed_at TIMESTAMP,
    released_at TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(admission_id,product_id),
    FOREIGN KEY(order_id,admission_id) REFERENCES order_edit_admissions(order_id,id),
    FOREIGN KEY(admission_id,candidate_digest) REFERENCES order_edit_admissions(id,candidate_digest),
    FOREIGN KEY(order_id,operation_id) REFERENCES order_change_operations(order_id,id),
    CHECK(before_quantity<>after_quantity),
    CHECK(CASE WHEN reservation_state IS NULL THEN
        num_nonnulls(operation_id,hold_movement_id,release_movement_id,held_at,consumed_at,released_at)=0
      ELSE reserve_quantity>0 AND operation_id IS NOT NULL AND hold_movement_id IS NOT NULL AND held_at IS NOT NULL
        AND ((reservation_state='HELD' AND num_nonnulls(release_movement_id,consumed_at,released_at)=0)
          OR (reservation_state='CONSUMED' AND consumed_at IS NOT NULL AND release_movement_id IS NULL AND released_at IS NULL)
          OR (reservation_state='RELEASED' AND release_movement_id IS NOT NULL AND released_at IS NOT NULL AND consumed_at IS NULL)) END)
);
CREATE INDEX idx_edit_inventory_plan_product ON order_edit_inventory_plans(product_id,admission_id);
CREATE FUNCTION f125_edit_inventory_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE receipt order_edit_admissions%ROWTYPE; candidate JSONB; before_body BYTEA; before_doc JSONB; b BIGINT; a BIGINT;
BEGIN
    -- No upstream locks here: the repository acquires runtime -> Order -> ... -> control first.
    PERFORM f125_execution_assert_basis(NEW.admission_id);
    SELECT * INTO STRICT receipt FROM order_edit_admissions WHERE id=NEW.admission_id;
    PERFORM f125_admission_check_runtime(receipt.db_generation_id);
    IF receipt.execution_state NOT IN ('QUEUED','OBSERVING') OR receipt.closed_at IS NOT NULL
        OR NEW.order_id<>receipt.order_id OR NEW.candidate_digest<>receipt.candidate_digest
        OR num_nonnulls(NEW.operation_id,NEW.reservation_state,NEW.hold_movement_id,NEW.release_movement_id,
            NEW.held_at,NEW.consumed_at,NEW.released_at)<>0 THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inventory plan context invalid or reservation writer not enabled';
    END IF;
    candidate=convert_from(receipt.candidate_payload,'UTF8')::jsonb;
    SELECT CASE WHEN receipt.base_evidence_history_id IS NULL THEN e.start_snapshot ELSE h.after_snapshot END
      INTO before_body FROM order_payment_evidence_epochs e LEFT JOIN order_content_change_histories h
      ON h.id=receipt.base_evidence_history_id AND h.evidence_epoch_id=e.id AND h.order_id=receipt.order_id
      WHERE e.id=receipt.base_evidence_epoch_id AND e.order_id=receipt.order_id;
    IF before_body IS NULL OR sha256(before_body)<>receipt.base_fingerprint THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Recorded before snapshot missing or inconsistent'; END IF;
    before_doc=convert_from(before_body,'UTF8')::jsonb;
    IF candidate->>'version' IS DISTINCT FROM '1' OR candidate->>'kind' IS DISTINCT FROM 'ORDER_EDIT_ADMISSION_CANDIDATE'
       OR (candidate->>'orderId')::bigint IS DISTINCT FROM receipt.order_id
       OR jsonb_typeof(candidate->'preview'->'items') IS DISTINCT FROM 'array'
       OR jsonb_typeof(before_doc->'items') IS DISTINCT FROM 'array'
       OR jsonb_typeof(candidate->'stockDeltas') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid saved candidate'; END IF;
    IF EXISTS(SELECT 1 FROM jsonb_array_elements((candidate->'preview'->'items')||(before_doc->'items')) i
        WHERE jsonb_typeof(i->'quantity') IS DISTINCT FROM 'number' OR (i->>'quantity')!~'^[1-9][0-9]*$'
           OR jsonb_typeof(i->'productId') IS DISTINCT FROM 'number' OR (i->>'productId')!~'^[1-9][0-9]*$') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing quantity or product fact'; END IF;
    SELECT coalesce(sum((i->>'quantity')::bigint),0) INTO b FROM jsonb_array_elements(before_doc->'items') i WHERE (i->>'productId')::bigint=NEW.product_id;
    SELECT coalesce(sum((i->>'quantity')::bigint),0) INTO a FROM jsonb_array_elements(candidate->'preview'->'items') i WHERE (i->>'productId')::bigint=NEW.product_id;
    IF b<>NEW.before_quantity OR a<>NEW.after_quantity OR b=a OR NOT EXISTS(
        SELECT 1 FROM jsonb_array_elements(candidate->'stockDeltas') d WHERE (d->>'productId')::bigint=NEW.product_id
          AND (d->>'beforeQuantity')::bigint=b AND (d->>'afterQuantity')::bigint=a) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inventory plan differs from saved candidate'; END IF;
    NEW.created_at=clock_timestamp();RETURN NEW;
END;
$$;
CREATE TRIGGER edit_inventory_validate BEFORE INSERT ON order_edit_inventory_plans FOR EACH ROW EXECUTE FUNCTION f125_edit_inventory_guard();
CREATE TRIGGER edit_inventory_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_edit_inventory_plans FOR EACH STATEMENT EXECUTE FUNCTION f125_admission_immutable();

-- Completion format only. V72's disposition CHECK and APPLYING/COMPLETED veto remain unchanged.
ALTER TABLE order_edit_execution_attempt_results
    ADD COLUMN application_version SMALLINT,
    ADD COLUMN application_operation_id UUID UNIQUE,
    ADD COLUMN application_history_id BIGINT UNIQUE,
    ADD COLUMN application_before_revision INTEGER,
    ADD COLUMN application_after_revision INTEGER,
    ADD COLUMN application_fingerprint BYTEA,
    ADD CONSTRAINT fk_edit_result_operation FOREIGN KEY(order_id,application_operation_id) REFERENCES order_change_operations(order_id,id),
    ADD CONSTRAINT fk_edit_result_history FOREIGN KEY(order_id,application_history_id) REFERENCES order_content_change_histories(order_id,id),
    ADD CONSTRAINT ck_edit_application_result_shape CHECK(
      num_nonnulls(application_version,application_operation_id,application_history_id,application_before_revision,application_after_revision,application_fingerprint)=0
      OR (num_nonnulls(application_version,application_operation_id,application_history_id,application_before_revision,application_after_revision,application_fingerprint)=6
        AND application_version=1 AND application_before_revision>=0
        AND application_after_revision::bigint=application_before_revision::bigint+1 AND octet_length(application_fingerprint)=32));
CREATE FUNCTION f125_edit_application_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF num_nonnulls(NEW.application_version,NEW.application_operation_id,NEW.application_history_id,
        NEW.application_before_revision,NEW.application_after_revision,NEW.application_fingerprint)<>0 THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application result writer is not enabled'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER edit_application_result_closed BEFORE INSERT ON order_edit_execution_attempt_results FOR EACH ROW EXECUTE FUNCTION f125_edit_application_result_guard();
CREATE FUNCTION f125_edit_application_context(target UUID, attempt UUID, token BIGINT, expected_version BIGINT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM f125_execution_lock(target);
    PERFORM f125_execution_assert_unapplied(target);
    IF NOT EXISTS(SELECT 1 FROM order_edit_admissions r JOIN order_edit_execution_controls c ON c.admission_id=r.id
        JOIN order_edit_execution_attempts a ON a.admission_id=r.id AND a.id=c.active_attempt_id
        WHERE r.id=target AND r.admission_state='ACCEPTED' AND r.execution_state='OBSERVING' AND r.closed_at IS NULL
          AND a.id=attempt AND c.fencing_token=token AND c.version=expected_version AND token>0
          AND a.fencing_token=c.fencing_token AND a.control_version=c.version AND a.order_id=r.order_id
          AND a.db_generation_id=c.db_generation_id AND c.db_generation_id=r.db_generation_id
          AND a.baseline_digest=c.baseline_digest AND a.lease_until=c.lease_until AND c.lease_until>clock_timestamp()
          AND NOT EXISTS(SELECT 1 FROM order_edit_execution_attempt_results x WHERE x.attempt_id=a.id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Application context ownership mismatch'; END IF;
    -- Returns no capability and performs no transition. The future applier must revalidate again.
END;
$$;
DO $$
DECLARE f RECORD;
BEGIN
 FOR f IN SELECT oid::regprocedure signature FROM pg_proc WHERE pronamespace=current_schema()::regnamespace
   AND proname IN ('f125_edit_inventory_guard','f125_edit_application_result_guard','f125_edit_application_context') LOOP
   EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',f.signature,current_schema());
   EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.signature);
 END LOOP;
END;
$$;
REVOKE ALL ON order_edit_inventory_plans FROM PUBLIC;
