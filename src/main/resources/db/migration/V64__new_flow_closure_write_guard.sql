-- No backfill or financial-result application. The writer holds Order -> operation -> strong
-- Payment FOR UPDATE -> transactions -> reservations -> dispatches. These guards add NO upper locks.
CREATE INDEX idx_change_closed_new_payment ON order_change_operations(new_payment_id)
    WHERE abort_flow_closed_at IS NOT NULL;

CREATE FUNCTION guard_closed_new_payment() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE financial_change BOOLEAN;
BEGIN
    IF TG_OP='DELETE' THEN financial_change=true;
    ELSE financial_change=ROW(NEW.id,NEW.order_id,NEW.provider,NEW.payment_method,NEW.status,NEW.amount,NEW.provider_payment_id,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.order_id,OLD.provider,OLD.payment_method,OLD.status,OLD.amount,OLD.provider_payment_id,OLD.created_at);
    END IF;
    IF financial_change THEN
        IF EXISTS(SELECT 1 FROM order_change_operations WHERE new_payment_id=OLD.id AND abort_flow_closed_at IS NOT NULL) THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Closed new Payment financial state is immutable';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER closed_new_payment_guard BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION guard_closed_new_payment();

CREATE FUNCTION guard_closed_new_transaction() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
DECLARE old_payment BIGINT; new_payment BIGINT; old_operation UUID; new_operation UUID;
BEGIN
    IF TG_OP<>'INSERT' THEN old_payment=OLD.payment_id; old_operation=OLD.order_change_operation_id; END IF;
    IF TG_OP<>'DELETE' THEN new_payment=NEW.payment_id; new_operation=NEW.order_change_operation_id; END IF;
    -- Audit notes, initiator fields and failure descriptions may still be recorded.
    IF TG_OP='UPDATE' THEN
    IF ROW(NEW.id,NEW.payment_id,NEW.transaction_type,NEW.purpose,NEW.status,NEW.amount,
        NEW.order_content_revision,NEW.idempotency_key,NEW.provider_transaction_id,NEW.order_change_operation_id,
        NEW.order_change_order_id,NEW.created_at,NEW.completed_at)
        IS NOT DISTINCT FROM ROW(OLD.id,OLD.payment_id,OLD.transaction_type,OLD.purpose,OLD.status,OLD.amount,
        OLD.order_content_revision,OLD.idempotency_key,OLD.provider_transaction_id,OLD.order_change_operation_id,
        OLD.order_change_order_id,OLD.created_at,OLD.completed_at) THEN RETURN NEW; END IF;
    END IF;
    IF EXISTS(SELECT 1 FROM order_change_operations WHERE abort_flow_closed_at IS NOT NULL
        AND (new_payment_id IN (old_payment,new_payment) OR id IN (old_operation,new_operation))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Closed new Flow transaction financial state is immutable';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER closed_new_transaction_guard BEFORE UPDATE OR DELETE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION guard_closed_new_transaction();
-- The zz AFTER trigger runs after immediate RI_ FK checks. An INSERT/key-changing UPDATE
-- that waited on the writer's strong Payment lock must recheck closure after that wait.
-- VOLATILE queries see the newly committed closure; BEFORE-only would leave a race.
CREATE TRIGGER zz_closed_new_transaction_guard AFTER INSERT OR UPDATE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION guard_closed_new_transaction();

CREATE FUNCTION guard_closed_new_request() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM order_change_operations WHERE abort_flow_closed_at IS NOT NULL
        AND (id=NEW.operation_id OR new_payment_id=NEW.payment_id)) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Closed new Flow cannot create financial requests';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER zz_closed_new_request_guard AFTER INSERT ON order_change_financial_requests
    FOR EACH ROW EXECUTE FUNCTION guard_closed_new_request();

CREATE FUNCTION guard_closed_new_dispatch() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM order_change_financial_requests r JOIN order_change_operations o ON o.id=r.operation_id
        WHERE r.id=NEW.request_id AND o.abort_flow_closed_at IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Closed new Flow cannot advance financial dispatch';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER closed_new_dispatch_guard BEFORE UPDATE ON order_change_request_dispatches
    FOR EACH ROW EXECUTE FUNCTION guard_closed_new_dispatch();
CREATE TRIGGER zz_closed_new_dispatch_guard AFTER INSERT OR UPDATE ON order_change_request_dispatches
    FOR EACH ROW EXECUTE FUNCTION guard_closed_new_dispatch();
