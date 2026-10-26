-- No backfill: a request without this row is legacy/unknown and must never auto-POST.
-- STARTED means the transport may have run, not that the provider received it.
-- OBSERVED records validated GET evidence; transaction/operation state determines final success.
CREATE TABLE order_change_request_dispatches (
    request_id BIGINT PRIMARY KEY REFERENCES order_change_financial_requests(id),
    state VARCHAR(10) NOT NULL CHECK (state IN ('READY','STARTED','OBSERVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    observed_at TIMESTAMPTZ,
    CHECK ((state='READY' AND started_at IS NULL AND observed_at IS NULL)
        OR (state='STARTED' AND started_at IS NOT NULL AND observed_at IS NULL)
        OR (state='OBSERVED' AND started_at IS NOT NULL AND observed_at IS NOT NULL))
);
CREATE FUNCTION create_order_change_dispatch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO order_change_request_dispatches(request_id,state) VALUES (NEW.id,'READY');
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_change_request_dispatch AFTER INSERT ON order_change_financial_requests
    FOR EACH ROW EXECUTE FUNCTION create_order_change_dispatch();
CREATE FUNCTION protect_order_change_dispatch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        -- Only the request-insertion trigger may initialize eligibility. No legacy row repair.
        IF pg_trigger_depth()<2 OR NEW.state<>'READY' THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Dispatch initialization requires a new request';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP<>'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Dispatch records cannot be removed';
    END IF;
    IF NEW.request_id IS DISTINCT FROM OLD.request_id OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NOT ((OLD.state='READY' AND NEW.state='STARTED')
           OR (OLD.state='STARTED' AND NEW.state='OBSERVED' AND NEW.started_at=OLD.started_at)) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Dispatch progress is irreversible';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_change_dispatch_progress BEFORE INSERT OR UPDATE OR DELETE ON order_change_request_dispatches
    FOR EACH ROW EXECUTE FUNCTION protect_order_change_dispatch();
CREATE TRIGGER trg_change_dispatch_truncate BEFORE TRUNCATE ON order_change_request_dispatches
    FOR EACH STATEMENT EXECUTE FUNCTION protect_order_change_dispatch();
