-- Storage only. No backfill, provider calls, execution permissions or production writers.
-- Writers lock Order -> operation -> Payment -> transactions -> evidence/children.
-- Triggers acquire NO upper row locks. Freezing a source history uses FOR UPDATE;
-- child writers take FOR KEY SHARE on that lower history row, then re-read protection.
-- Composite FKs pin financial identity;
-- unique indexes serialize duplicate roots/branches. Deferred checks inspect final Tx state.
-- Java V1 Codec/Validator still owns full canonical JSON semantics, digest recomputation,
-- complete financial history, pricing, external authenticity/freshness and admission.

-- VOLATILE rechecks after lock waits require READ COMMITTED. Fail closed rather
-- than allow an older REPEATABLE READ snapshot to miss a newly frozen legacy parent.
-- Applies to history/child writers as well as enrolled evidence writers.
CREATE FUNCTION f125_require_read_committed() RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION USING ERRCODE='25000',MESSAGE='Order evidence writes require READ COMMITTED';
    END IF;
END;
$$;

CREATE FUNCTION f125_snapshot_header(body BYTEA, owner_id BIGINT, revision INTEGER, amount INTEGER)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE j JSONB;
BEGIN
    IF body IS NULL OR octet_length(body)>2097152 THEN RETURN false; END IF;
    j=convert_from(body,'UTF8')::JSONB;
    RETURN (jsonb_typeof(j)='object' AND j->'schemaVersion'='1'::JSONB
        AND j->'orderId'=to_jsonb(owner_id) AND j->'contentRevision'=to_jsonb(revision)
        AND j->'totalAmount'=to_jsonb(amount)) IS TRUE;
EXCEPTION WHEN data_exception THEN RETURN false;
END;
$$;

ALTER TABLE payments ADD CONSTRAINT uq_f125_payment_amount UNIQUE(order_id,id,amount);
ALTER TABLE payment_transactions ADD CONSTRAINT uq_f125_authorization_identity
    UNIQUE(payment_id,id,transaction_type,status,amount,order_content_revision,provider_transaction_id);
ALTER TABLE order_change_operations ADD CONSTRAINT uq_f125_operation_owner UNIQUE(order_id,id);

CREATE TABLE order_payment_evidence_epochs (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL UNIQUE,
    authorization_transaction_id BIGINT NOT NULL UNIQUE,
    origin_kind VARCHAR(32) COLLATE "C" NOT NULL CHECK(origin_kind IN ('INITIAL_AUTH','REAUTH_COMPLETED','LEGACY_CURRENT_CHECKPOINT')),
    origin_operation_id UUID,
    origin_history_id BIGINT,
    previous_epoch_id UUID,
    start_content_revision INTEGER NOT NULL CHECK(start_content_revision>=0),
    start_total_amount INTEGER NOT NULL CHECK(start_total_amount>0),
    authorized_amount INTEGER NOT NULL CHECK(authorized_amount>=start_total_amount),
    authorization_revision INTEGER NOT NULL CHECK(authorization_revision>=0 AND authorization_revision<=start_content_revision),
    authorization_type VARCHAR(30) NOT NULL DEFAULT 'AUTHORIZE' CHECK(authorization_type='AUTHORIZE'),
    authorization_status VARCHAR(30) NOT NULL DEFAULT 'SUCCEEDED' CHECK(authorization_status='SUCCEEDED'),
    provider_flow_id VARCHAR(255) NOT NULL CHECK(provider_flow_id COLLATE "C" ~ '^pfw_[A-Za-z0-9_-]+$'),
    start_snapshot BYTEA NOT NULL,
    start_fingerprint BYTEA NOT NULL CHECK(octet_length(start_fingerprint)=32 AND start_fingerprint=sha256(start_snapshot)),
    observation_payload BYTEA NOT NULL CHECK(octet_length(observation_payload) BETWEEN 1 AND 2097152),
    observation_fingerprint BYTEA NOT NULL CHECK(octet_length(observation_fingerprint)=32 AND observation_fingerprint=sha256(observation_payload)),
    observation_started_at TIMESTAMPTZ NOT NULL,
    observation_finished_at TIMESTAMPTZ NOT NULL CHECK(observation_finished_at>=observation_started_at),
    evidence_version SMALLINT NOT NULL CHECK(evidence_version=1),
    evidence_digest BYTEA NOT NULL CHECK(octet_length(evidence_digest)=32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(order_id,id),
    CHECK(f125_snapshot_header(start_snapshot,order_id,start_content_revision,start_total_amount)),
    CHECK((origin_kind='REAUTH_COMPLETED' AND origin_operation_id IS NOT NULL AND origin_history_id IS NOT NULL)
       OR (origin_kind<>'REAUTH_COMPLETED' AND origin_operation_id IS NULL AND origin_history_id IS NULL AND previous_epoch_id IS NULL)),
    CHECK((origin_kind IN ('INITIAL_AUTH','REAUTH_COMPLETED') AND start_content_revision=authorization_revision AND start_total_amount=authorized_amount)
       OR (origin_kind='LEGACY_CURRENT_CHECKPOINT' AND start_content_revision>authorization_revision AND start_total_amount<authorized_amount)),
    CHECK(previous_epoch_id IS DISTINCT FROM id),
    FOREIGN KEY(order_id,payment_id,authorized_amount) REFERENCES payments(order_id,id,amount) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(payment_id,authorization_transaction_id,authorization_type,authorization_status,authorized_amount,authorization_revision,provider_flow_id)
        REFERENCES payment_transactions(payment_id,id,transaction_type,status,amount,order_content_revision,provider_transaction_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(order_id,origin_operation_id) REFERENCES order_change_operations(order_id,id) ON DELETE RESTRICT,
    FOREIGN KEY(order_id,origin_history_id) REFERENCES order_content_change_histories(order_id,id) ON DELETE RESTRICT,
    FOREIGN KEY(order_id,previous_epoch_id) REFERENCES order_payment_evidence_epochs(order_id,id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_f125_epoch_successor ON order_payment_evidence_epochs(previous_epoch_id) WHERE previous_epoch_id IS NOT NULL;
CREATE UNIQUE INDEX uq_f125_epoch_origin_operation ON order_payment_evidence_epochs(origin_operation_id) WHERE origin_operation_id IS NOT NULL;
CREATE UNIQUE INDEX uq_f125_epoch_origin_history ON order_payment_evidence_epochs(origin_history_id) WHERE origin_history_id IS NOT NULL;

ALTER TABLE order_content_change_histories
    ADD COLUMN before_content_revision INTEGER,
    ADD COLUMN after_content_revision INTEGER,
    ADD COLUMN evidence_epoch_id UUID,
    ADD COLUMN previous_history_id BIGINT,
    ADD COLUMN before_fingerprint BYTEA,
    ADD COLUMN after_fingerprint BYTEA,
    ADD COLUMN after_snapshot BYTEA,
    ADD COLUMN evidence_version SMALLINT,
    ADD COLUMN evidence_digest BYTEA;
ALTER TABLE order_content_change_histories ADD CONSTRAINT ck_f125_history_shape CHECK (
    CASE WHEN evidence_version IS NULL THEN num_nonnulls(before_content_revision,after_content_revision,evidence_epoch_id,
        previous_history_id,before_fingerprint,after_fingerprint,after_snapshot,evidence_digest)=0
    ELSE evidence_version=1 AND num_nonnulls(before_content_revision,after_content_revision,evidence_epoch_id,
        before_fingerprint,after_fingerprint,after_snapshot,evidence_digest)=7
        AND before_content_revision>=0 AND after_content_revision=before_content_revision::BIGINT+1
        AND old_total_amount>0 AND new_total_amount>0
        AND octet_length(before_fingerprint)=32 AND octet_length(after_fingerprint)=32 AND octet_length(evidence_digest)=32
        AND after_fingerprint=sha256(after_snapshot)
        AND f125_snapshot_header(after_snapshot,order_id,after_content_revision,new_total_amount)
        AND previous_history_id IS DISTINCT FROM id END);
ALTER TABLE order_content_change_histories ADD CONSTRAINT uq_f125_history_epoch_identity UNIQUE(order_id,evidence_epoch_id,id);
ALTER TABLE order_content_change_histories ADD CONSTRAINT fk_f125_history_epoch
    FOREIGN KEY(order_id,evidence_epoch_id) REFERENCES order_payment_evidence_epochs(order_id,id) ON DELETE RESTRICT;
ALTER TABLE order_content_change_histories ADD CONSTRAINT fk_f125_history_previous
    FOREIGN KEY(order_id,evidence_epoch_id,previous_history_id) REFERENCES order_content_change_histories(order_id,evidence_epoch_id,id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_f125_history_revision ON order_content_change_histories(order_id,after_content_revision) WHERE evidence_version IS NOT NULL;
CREATE UNIQUE INDEX uq_f125_history_successor ON order_content_change_histories(previous_history_id) WHERE previous_history_id IS NOT NULL;
CREATE UNIQUE INDEX uq_f125_history_first ON order_content_change_histories(evidence_epoch_id) WHERE evidence_version IS NOT NULL AND previous_history_id IS NULL;

ALTER TABLE orders ADD COLUMN current_evidence_epoch_id UUID, ADD COLUMN evidence_head_history_id BIGINT;
ALTER TABLE orders ADD CONSTRAINT fk_f125_order_epoch FOREIGN KEY(id,current_evidence_epoch_id)
    REFERENCES order_payment_evidence_epochs(order_id,id) ON DELETE RESTRICT;
ALTER TABLE orders ADD CONSTRAINT fk_f125_order_head FOREIGN KEY(id,current_evidence_epoch_id,evidence_head_history_id)
    REFERENCES order_content_change_histories(order_id,evidence_epoch_id,id) ON DELETE RESTRICT;
ALTER TABLE orders ADD CONSTRAINT ck_f125_order_head CHECK(current_evidence_epoch_id IS NOT NULL OR evidence_head_history_id IS NULL);

ALTER TABLE order_change_operations
    ADD COLUMN execution_version SMALLINT,
    ADD COLUMN execution_route VARCHAR(16) COLLATE "C",
    ADD COLUMN base_evidence_epoch_id UUID,
    ADD COLUMN base_evidence_history_id BIGINT,
    ADD COLUMN old_authorization_transaction_id BIGINT,
    ADD COLUMN old_authorized_amount INTEGER,
    ADD COLUMN old_authorization_revision INTEGER,
    ADD COLUMN admission_observation_payload BYTEA,
    ADD COLUMN admission_observation_fingerprint BYTEA;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_f125_operation_shape CHECK (
    CASE WHEN execution_version IS NULL THEN num_nonnulls(execution_route,base_evidence_epoch_id,base_evidence_history_id,
        old_authorization_transaction_id,old_authorized_amount,old_authorization_revision,
        admission_observation_payload,admission_observation_fingerprint)=0
    ELSE execution_version=1 AND num_nonnulls(execution_route,base_evidence_epoch_id,old_authorization_transaction_id,
        old_authorized_amount,old_authorization_revision,admission_observation_payload,admission_observation_fingerprint)=7
        AND execution_route IN ('RETAIN_AUTH','REAUTHORIZE') AND old_authorized_amount>0 AND old_authorization_revision>=0
        AND before_amount<=old_authorized_amount AND old_authorization_revision<=base_content_revision
        AND octet_length(admission_observation_payload) BETWEEN 1 AND 2097152
        AND octet_length(admission_observation_fingerprint)=32 AND admission_observation_fingerprint=sha256(admission_observation_payload)
        AND ((execution_route='RETAIN_AUTH' AND after_amount<=old_authorized_amount AND new_payment_id IS NULL AND switch_decided_at IS NULL
              AND state IN ('RESERVED','COMPLETED','ABORTED'))
          OR (execution_route='REAUTHORIZE' AND after_amount>old_authorized_amount)) END);
ALTER TABLE order_change_operations ADD CONSTRAINT fk_f125_operation_epoch FOREIGN KEY(order_id,base_evidence_epoch_id)
    REFERENCES order_payment_evidence_epochs(order_id,id) ON DELETE RESTRICT;
ALTER TABLE order_change_operations ADD CONSTRAINT fk_f125_operation_head FOREIGN KEY(order_id,base_evidence_epoch_id,base_evidence_history_id)
    REFERENCES order_content_change_histories(order_id,evidence_epoch_id,id) ON DELETE RESTRICT;
ALTER TABLE order_change_operations ADD CONSTRAINT fk_f125_operation_authorization FOREIGN KEY(old_authorization_transaction_id)
    REFERENCES payment_transactions(id) ON DELETE RESTRICT;

CREATE FUNCTION f125_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Order payment evidence is immutable'; END;
$$;
CREATE TRIGGER f125_epoch_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON order_payment_evidence_epochs
    FOR EACH STATEMENT EXECUTE FUNCTION f125_immutable();

CREATE FUNCTION f125_validate_epoch() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE p payments%ROWTYPE; a payment_transactions%ROWTYPE; op order_change_operations%ROWTYPE; o orders%ROWTYPE;
BEGIN
    PERFORM f125_require_read_committed();
    SELECT * INTO STRICT o FROM orders WHERE id=NEW.order_id;
    SELECT * INTO STRICT p FROM payments WHERE id=NEW.payment_id AND order_id=NEW.order_id;
    SELECT * INTO STRICT a FROM payment_transactions WHERE id=NEW.authorization_transaction_id AND payment_id=p.id;
    IF (p.provider='PAYJP' AND p.payment_method='CARD' AND p.status='AUTHORIZED' AND p.provider_payment_id=NEW.provider_flow_id
        AND a.transaction_type='AUTHORIZE' AND a.status='SUCCEEDED' AND a.completed_at IS NOT NULL
        AND a.amount=NEW.authorized_amount AND a.order_content_revision=NEW.authorization_revision
        AND a.provider_transaction_id=NEW.provider_flow_id
        AND (convert_from(NEW.start_snapshot,'UTF8')::JSONB->'customerId')=to_jsonb(o.user_id)) IS NOT TRUE THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid epoch financial ownership or content owner';
    END IF;
    IF NEW.origin_kind='REAUTH_COMPLETED' THEN
        PERFORM 1 FROM order_content_change_histories WHERE id=NEW.origin_history_id FOR UPDATE;
        SELECT * INTO STRICT op FROM order_change_operations WHERE id=NEW.origin_operation_id AND order_id=NEW.order_id;
        IF (op.state='COMPLETED' AND op.new_payment_id=p.id AND op.content_change_history_id=NEW.origin_history_id
            AND op.base_content_revision::BIGINT+1=NEW.start_content_revision AND op.after_amount=NEW.start_total_amount
            AND EXISTS(SELECT 1 FROM order_content_change_histories h WHERE h.id=NEW.origin_history_id
                AND h.order_id=NEW.order_id AND h.old_total_amount=op.before_amount AND h.new_total_amount=op.after_amount)
            AND a.purpose='ORDER_CHANGE_AUTHORIZATION' AND a.order_change_operation_id=op.id
            AND EXISTS(SELECT 1 FROM payments oldp JOIN payment_transactions c ON c.payment_id=oldp.id
                WHERE oldp.id=op.old_payment_id AND oldp.order_id=op.order_id AND oldp.status='CANCELLED'
                    AND c.order_change_operation_id=op.id AND c.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL'
                    AND c.transaction_type='CANCEL' AND c.status='SUCCEEDED' AND c.amount=oldp.amount
                    AND c.provider_transaction_id=oldp.provider_payment_id AND c.completed_at IS NOT NULL)
            AND (op.execution_version IS NULL OR (op.execution_route='REAUTHORIZE' AND op.base_evidence_epoch_id=NEW.previous_epoch_id))) IS NOT TRUE THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Epoch requires completed reauthorization and old cancellation';
        END IF;
    ELSE
        IF a.order_change_operation_id IS NOT NULL THEN
            -- A legacy checkpoint may follow an established Feature124 generation, but its
            -- original authorization must still have its own completed-operation provenance.
            IF NEW.origin_kind<>'LEGACY_CURRENT_CHECKPOINT' OR NOT EXISTS(SELECT 1 FROM order_change_operations c
                WHERE c.id=a.order_change_operation_id AND c.order_id=NEW.order_id AND c.state='COMPLETED'
                    AND c.new_payment_id=p.id AND c.content_change_history_id IS NOT NULL AND a.purpose='ORDER_CHANGE_AUTHORIZATION') THEN
                RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unestablished checkpoint authorization';
            END IF;
        ELSIF a.purpose IS NOT NULL AND a.purpose<>'INITIAL_ORDER_AUTHORIZATION' THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unknown initial authorization purpose';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f125_epoch_validate BEFORE INSERT ON order_payment_evidence_epochs FOR EACH ROW EXECUTE FUNCTION f125_validate_epoch();

CREATE FUNCTION f125_history_guard() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE e order_payment_evidence_epochs%ROWTYPE; p order_content_change_histories%ROWTYPE; customer BIGINT;
BEGIN
    PERFORM f125_require_read_committed();
    IF TG_OP<>'INSERT' THEN
        IF OLD.evidence_version IS NOT NULL OR (TG_OP='UPDATE' AND NEW.evidence_version IS NOT NULL)
            OR EXISTS(SELECT 1 FROM order_payment_evidence_epochs WHERE origin_history_id=OLD.id) THEN
            RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Cannot rewrite or backfill evidence history';
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    END IF;
    IF NEW.evidence_version IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE id=NEW.evidence_epoch_id AND order_id=NEW.order_id;
    SELECT user_id INTO STRICT customer FROM orders WHERE id=NEW.order_id;
    IF (NEW.new_total_amount<=e.authorized_amount
        AND convert_from(NEW.after_snapshot,'UTF8')::JSONB->'customerId'=to_jsonb(customer)) IS NOT TRUE THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='History owner or authorization ceiling mismatch';
    END IF;
    IF NEW.previous_history_id IS NULL THEN
        IF ROW(NEW.before_content_revision,NEW.old_total_amount,NEW.before_fingerprint)
            IS DISTINCT FROM ROW(e.start_content_revision,e.start_total_amount,e.start_fingerprint) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='History does not connect to epoch';
        END IF;
    ELSE
        SELECT * INTO STRICT p FROM order_content_change_histories WHERE id=NEW.previous_history_id
            AND order_id=NEW.order_id AND evidence_epoch_id=NEW.evidence_epoch_id;
        IF ROW(NEW.before_content_revision,NEW.old_total_amount,NEW.before_fingerprint)
            IS DISTINCT FROM ROW(p.after_content_revision,p.new_total_amount,p.after_fingerprint) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Broken evidence history chain';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f125_history_guard BEFORE INSERT OR UPDATE OR DELETE ON order_content_change_histories
    FOR EACH ROW EXECUTE FUNCTION f125_history_guard();

CREATE FUNCTION f125_child_guard() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE protected BOOLEAN;
BEGIN
    PERFORM f125_require_read_committed();
    IF TG_OP='INSERT' THEN
        PERFORM 1 FROM order_content_change_histories WHERE id=NEW.history_id FOR KEY SHARE;
    ELSIF TG_OP='DELETE' THEN
        PERFORM 1 FROM order_content_change_histories WHERE id=OLD.history_id FOR KEY SHARE;
    ELSE
        PERFORM 1 FROM order_content_change_histories WHERE id IN (OLD.history_id,NEW.history_id) ORDER BY id FOR KEY SHARE;
    END IF;
    IF TG_OP<>'INSERT' AND EXISTS(SELECT 1 FROM order_content_change_histories h WHERE h.id=OLD.history_id
        AND (h.evidence_version IS NOT NULL OR EXISTS(SELECT 1 FROM order_payment_evidence_epochs e WHERE e.origin_history_id=h.id))) THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Evidence child cannot be rewritten';
    END IF;
    IF TG_OP<>'DELETE' THEN
        SELECT EXISTS(SELECT 1 FROM order_content_change_histories h JOIN order_change_operations c ON c.content_change_history_id=h.id
            WHERE h.id=NEW.history_id AND c.state='COMPLETED'
                AND (h.evidence_version IS NOT NULL OR EXISTS(SELECT 1 FROM order_payment_evidence_epochs e WHERE e.origin_history_id=h.id))) INTO protected;
        IF protected THEN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Completed evidence cannot gain children'; END IF;
        RETURN NEW;
    END IF;
    RETURN OLD;
END;
$$;
-- Both freezing and child writers lock the lower history row. AFTER FK rechecks also
-- cover legacy-child reparenting after a concurrent source-history freeze.
CREATE TRIGGER f125_item_guard BEFORE UPDATE OR DELETE ON order_content_change_items FOR EACH ROW EXECUTE FUNCTION f125_child_guard();
CREATE TRIGGER zz_f125_item_insert AFTER INSERT OR UPDATE ON order_content_change_items FOR EACH ROW EXECUTE FUNCTION f125_child_guard();
CREATE TRIGGER f125_charge_guard BEFORE UPDATE OR DELETE ON order_content_change_charges FOR EACH ROW EXECUTE FUNCTION f125_child_guard();
CREATE TRIGGER zz_f125_charge_insert AFTER INSERT OR UPDATE ON order_content_change_charges FOR EACH ROW EXECUTE FUNCTION f125_child_guard();

CREATE FUNCTION f125_operation_guard() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE e order_payment_evidence_epochs%ROWTYPE; o orders%ROWTYPE;
BEGIN
    IF TG_OP<>'INSERT' THEN
        IF TG_OP='DELETE' AND OLD.execution_version IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Evidence operation cannot be removed';
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        IF OLD.execution_version IS NULL AND NEW.execution_version IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Legacy operations cannot be reinterpreted';
        END IF;
        IF OLD.execution_version IS NOT NULL AND ROW(NEW.execution_version,NEW.execution_route,NEW.base_evidence_epoch_id,
            NEW.base_evidence_history_id,NEW.old_authorization_transaction_id,NEW.old_authorized_amount,NEW.old_authorization_revision,
            NEW.admission_observation_payload,NEW.admission_observation_fingerprint,NEW.order_id,NEW.customer_id,NEW.old_payment_id,
            NEW.base_content_revision,NEW.before_amount,NEW.after_amount,NEW.submission_key,NEW.request_digest,
            NEW.item_subtotal,NEW.charge_total,NEW.tax_amount,NEW.shipping_name,NEW.shipping_amount,NEW.shipping_tax_category_id,
            NEW.shipping_tax_category_code,NEW.shipping_tax_category_name,NEW.shipping_tax_rate,NEW.shipping_display_order,
            NEW.accepted_at,NEW.deadline_at,NEW.grace_until)
            IS DISTINCT FROM ROW(OLD.execution_version,OLD.execution_route,OLD.base_evidence_epoch_id,OLD.base_evidence_history_id,
            OLD.old_authorization_transaction_id,OLD.old_authorized_amount,OLD.old_authorization_revision,
            OLD.admission_observation_payload,OLD.admission_observation_fingerprint,OLD.order_id,OLD.customer_id,OLD.old_payment_id,
            OLD.base_content_revision,OLD.before_amount,OLD.after_amount,OLD.submission_key,OLD.request_digest,
            OLD.item_subtotal,OLD.charge_total,OLD.tax_amount,OLD.shipping_name,OLD.shipping_amount,OLD.shipping_tax_category_id,
            OLD.shipping_tax_category_code,OLD.shipping_tax_category_name,OLD.shipping_tax_rate,OLD.shipping_display_order,
            OLD.accepted_at,OLD.deadline_at,OLD.grace_until) THEN
            RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Accepted evidence context is immutable';
        END IF;
    END IF;
    IF NEW.execution_version IS NULL THEN RETURN NEW; END IF;
    PERFORM f125_require_read_committed();
    IF NEW.state='COMPLETED' AND NEW.content_change_history_id IS NOT NULL THEN
        PERFORM 1 FROM order_content_change_histories WHERE id=NEW.content_change_history_id FOR UPDATE;
    END IF;
    SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE id=NEW.base_evidence_epoch_id AND order_id=NEW.order_id;
    IF ROW(NEW.old_payment_id,NEW.old_authorization_transaction_id,NEW.old_authorized_amount,NEW.old_authorization_revision)
        IS DISTINCT FROM ROW(e.payment_id,e.authorization_transaction_id,e.authorized_amount,e.authorization_revision) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Operation authorization evidence mismatch';
    END IF;
    IF TG_OP='INSERT' THEN
        SELECT * INTO STRICT o FROM orders WHERE id=NEW.order_id;
        IF ROW(o.current_evidence_epoch_id,o.evidence_head_history_id,o.content_revision,o.total_amount,o.active_payment_id)
            IS DISTINCT FROM ROW(NEW.base_evidence_epoch_id,NEW.base_evidence_history_id,NEW.base_content_revision,NEW.before_amount,NEW.old_payment_id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Operation base is stale';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f125_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON order_change_operations FOR EACH ROW EXECUTE FUNCTION f125_operation_guard();

CREATE FUNCTION f125_order_transition() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE e order_payment_evidence_epochs%ROWTYPE; h order_content_change_histories%ROWTYPE;
BEGIN
    IF OLD.current_evidence_epoch_id IS NOT NULL AND NEW.current_evidence_epoch_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Evidence enrollment cannot be undone';
    END IF;
    IF NEW.current_evidence_epoch_id IS NULL THEN RETURN NEW; END IF;
    PERFORM f125_require_read_committed();
    SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE id=NEW.current_evidence_epoch_id AND order_id=NEW.id;
    IF OLD.current_evidence_epoch_id IS NULL THEN
        IF ROW(e.start_content_revision,e.start_total_amount) IS DISTINCT FROM ROW(OLD.content_revision,OLD.total_amount)
            OR (OLD.active_payment_id IS NOT NULL AND OLD.active_payment_id<>e.payment_id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Enrollment must start at saved current content';
        END IF;
    ELSIF OLD.current_evidence_epoch_id<>NEW.current_evidence_epoch_id THEN
        IF (e.origin_kind='REAUTH_COMPLETED' AND e.previous_epoch_id=OLD.current_evidence_epoch_id
            AND EXISTS(SELECT 1 FROM order_change_operations c WHERE c.id=e.origin_operation_id
                AND c.state='COMPLETED' AND c.execution_route='REAUTHORIZE' AND c.base_evidence_epoch_id=OLD.current_evidence_epoch_id
                AND c.base_evidence_history_id IS NOT DISTINCT FROM OLD.evidence_head_history_id
                AND c.base_content_revision=OLD.content_revision AND c.before_amount=OLD.total_amount)) IS NOT TRUE THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Epoch switch requires completed reauthorization';
        END IF;
    ELSIF NEW.evidence_head_history_id IS DISTINCT FROM OLD.evidence_head_history_id THEN
        SELECT * INTO STRICT h FROM order_content_change_histories WHERE id=NEW.evidence_head_history_id AND order_id=NEW.id;
        IF h.previous_history_id IS DISTINCT FROM OLD.evidence_head_history_id
            OR h.before_content_revision IS DISTINCT FROM OLD.content_revision THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Order head cannot skip evidence';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f125_order_transition BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION f125_order_transition();

CREATE FUNCTION f125_check_order_proof(target BIGINT) RETURNS VOID LANGUAGE plpgsql VOLATILE AS $$
DECLARE o orders%ROWTYPE; e order_payment_evidence_epochs%ROWTYPE; h order_content_change_histories%ROWTYPE;
BEGIN
    SELECT * INTO o FROM orders WHERE id=target;
    IF NOT FOUND OR o.current_evidence_epoch_id IS NULL THEN RETURN; END IF;
    SELECT * INTO STRICT e FROM order_payment_evidence_epochs WHERE id=o.current_evidence_epoch_id AND order_id=o.id;
    IF o.status<>'CANCELLED' AND o.active_payment_id IS DISTINCT FROM e.payment_id THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Current epoch and active payment disagree';
    END IF;
    IF o.status='CANCELLED' AND o.active_payment_id IS NOT NULL AND o.active_payment_id<>e.payment_id THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Cancelled order cannot point at a different generation';
    END IF;
    IF o.evidence_head_history_id IS NULL THEN
        IF ROW(o.content_revision,o.total_amount) IS DISTINCT FROM ROW(e.start_content_revision,e.start_total_amount) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Order does not match epoch root';
        END IF;
    ELSE
        SELECT * INTO STRICT h FROM order_content_change_histories WHERE id=o.evidence_head_history_id AND order_id=o.id AND evidence_epoch_id=e.id;
        IF ROW(o.content_revision,o.total_amount) IS DISTINCT FROM ROW(h.after_content_revision,h.new_total_amount) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Order does not match evidence head';
        END IF;
    END IF;
    IF o.total_amount>e.authorized_amount OR EXISTS(SELECT 1 FROM order_content_change_histories c
        WHERE c.evidence_epoch_id=e.id AND c.after_content_revision>o.content_revision) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unattached evidence or authorization ceiling violation';
    END IF;
END;
$$;

CREATE FUNCTION f125_final_consistency() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE target BIGINT; c order_change_operations%ROWTYPE; h order_content_change_histories%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME='orders' THEN target=NEW.id;
    ELSE target=NEW.order_id; END IF;
    PERFORM f125_check_order_proof(target);
    IF TG_TABLE_NAME='order_payment_evidence_epochs' THEN
        IF NOT EXISTS(SELECT 1 FROM orders o WHERE o.id=NEW.order_id AND o.current_evidence_epoch_id=NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='A new epoch must be installed atomically';
        END IF;
    ELSIF TG_TABLE_NAME='order_content_change_histories' THEN
        IF NEW.evidence_version IS NULL THEN RETURN NULL; END IF;
        SELECT * INTO c FROM order_change_operations WHERE content_change_history_id=NEW.id;
        IF NOT FOUND OR (c.execution_version=1 AND c.execution_route='RETAIN_AUTH' AND c.state='COMPLETED'
            AND c.base_evidence_epoch_id=NEW.evidence_epoch_id AND c.base_evidence_history_id IS NOT DISTINCT FROM NEW.previous_history_id
            AND c.base_content_revision=NEW.before_content_revision AND c.before_amount=NEW.old_total_amount AND c.after_amount=NEW.new_total_amount) IS NOT TRUE THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Evidence history requires its completed retained operation';
        END IF;
    ELSIF TG_TABLE_NAME='order_change_operations' THEN
        SELECT * INTO STRICT c FROM order_change_operations WHERE id=NEW.id;
        IF c.execution_version IS NOT NULL AND c.state='COMPLETED' THEN
            IF c.execution_route='RETAIN_AUTH' THEN
                SELECT * INTO h FROM order_content_change_histories WHERE id=c.content_change_history_id AND order_id=c.order_id;
                IF NOT FOUND OR (h.evidence_version=1 AND h.evidence_epoch_id=c.base_evidence_epoch_id
                    AND h.previous_history_id IS NOT DISTINCT FROM c.base_evidence_history_id
                    AND h.before_content_revision=c.base_content_revision AND h.old_total_amount=c.before_amount AND h.new_total_amount=c.after_amount) IS NOT TRUE THEN
                    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Retained completion requires matching evidence history';
                END IF;
            ELSIF NOT EXISTS(SELECT 1 FROM order_payment_evidence_epochs e JOIN orders o ON o.current_evidence_epoch_id=e.id
                WHERE e.origin_operation_id=c.id AND e.origin_history_id=c.content_change_history_id AND o.id=c.order_id) THEN
                RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Reauthorization completion requires its new epoch';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER f125_order_final AFTER INSERT OR UPDATE ON orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_final_consistency();
CREATE CONSTRAINT TRIGGER f125_epoch_final AFTER INSERT ON order_payment_evidence_epochs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_final_consistency();
CREATE CONSTRAINT TRIGGER f125_history_final AFTER INSERT ON order_content_change_histories DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_final_consistency();
CREATE CONSTRAINT TRIGGER f125_operation_final AFTER INSERT OR UPDATE ON order_change_operations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f125_final_consistency();

CREATE FUNCTION f125_no_retained_finance() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE op_id UUID;
BEGIN
    IF TG_TABLE_NAME='payment_transactions' THEN op_id=NEW.order_change_operation_id; ELSE op_id=NEW.operation_id; END IF;
    IF EXISTS(SELECT 1 FROM order_change_operations WHERE id=op_id AND execution_route='RETAIN_AUTH') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Retained operation cannot create financial work';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER zz_f125_transaction_route AFTER INSERT OR UPDATE ON payment_transactions FOR EACH ROW EXECUTE FUNCTION f125_no_retained_finance();
CREATE TRIGGER zz_f125_request_route AFTER INSERT ON order_change_financial_requests FOR EACH ROW EXECUTE FUNCTION f125_no_retained_finance();

CREATE FUNCTION f125_protected_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM order_payment_evidence_epochs) OR EXISTS(SELECT 1 FROM order_change_operations WHERE execution_version IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Cannot truncate evidence-bearing data';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER f125_history_truncate BEFORE TRUNCATE ON order_content_change_histories FOR EACH STATEMENT EXECUTE FUNCTION f125_protected_truncate();
CREATE TRIGGER f125_item_truncate BEFORE TRUNCATE ON order_content_change_items FOR EACH STATEMENT EXECUTE FUNCTION f125_protected_truncate();
CREATE TRIGGER f125_charge_truncate BEFORE TRUNCATE ON order_content_change_charges FOR EACH STATEMENT EXECUTE FUNCTION f125_protected_truncate();
CREATE TRIGGER f125_operation_truncate BEFORE TRUNCATE ON order_change_operations FOR EACH STATEMENT EXECUTE FUNCTION f125_protected_truncate();
