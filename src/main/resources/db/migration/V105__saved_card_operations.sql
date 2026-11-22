-- Feature139-A: saved card operations (docs/feature139/saved-cards.md ch.4.2).
--
-- Cards and the PAY.JP customer are never stored here: the customer id is derived from the configured prefix and
-- users.id, and cards are read from PAY.JP each time. This table only records the operations sent to PAY.JP
-- (external sends and audit). It never holds card numbers, brands, last digits, expiry dates or client secrets.
--
-- One row per operation:
--   CUSTOMER_CREATE  create the PAY.JP customer                     (idempotency key)
--   SETUP_START      create a Setup Flow                             (idempotency key; Setup Flow id once known)
--   SETUP_COMPLETE   the browser confirmed that Setup Flow           (no send; recorded as SUCCEEDED once per flow)
--   DETACH           delete a card                                   (idempotency key)
--   SET_DEFAULT      change the customer's default card              (idempotency key)
-- Status: PENDING -> SUCCEEDED / FAILED / UNCONFIRMED, UNCONFIRMED -> SUCCEEDED / FAILED. SUCCEEDED and FAILED
-- are final. At most one PENDING or UNCONFIRMED operation per user (the application checks it while holding the
-- users row lock; the partial unique index is the database guarantee). Rows are never deleted.
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

CREATE TABLE saved_card_operations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    operation_type VARCHAR(20) NOT NULL
        CHECK (operation_type IN ('CUSTOMER_CREATE','SETUP_START','SETUP_COMPLETE','DETACH','SET_DEFAULT')),
    reason VARCHAR(30) NOT NULL CHECK (reason IN ('USER','DUPLICATE','LIMIT','DEFAULT_REPLACEMENT')),
    provider_customer_id VARCHAR(100) NOT NULL CHECK (provider_customer_id ~ '^[A-Za-z0-9_-]{1,100}$'),
    provider_setup_flow_id VARCHAR(255) CHECK (provider_setup_flow_id ~ '^[A-Za-z0-9_-]{1,255}$'),
    provider_payment_method_id VARCHAR(255) CHECK (provider_payment_method_id ~ '^pm_[A-Za-z0-9_-]{1,252}$'),
    idempotency_key VARCHAR(100) CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,100}$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNCONFIRMED')),
    error_code VARCHAR(100) CHECK (error_code ~ '^[A-Za-z0-9_]{1,100}$'),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_saved_card_operation_key UNIQUE (idempotency_key),
    CHECK ((operation_type IS NOT DISTINCT FROM 'SETUP_COMPLETE') IS NOT DISTINCT FROM (idempotency_key IS NULL)),
    CHECK ((operation_type IS NOT DISTINCT FROM 'CUSTOMER_CREATE' AND reason IS NOT DISTINCT FROM 'USER'
                AND provider_setup_flow_id IS NULL AND provider_payment_method_id IS NULL)
        OR (operation_type IS NOT DISTINCT FROM 'SETUP_START' AND reason IS NOT DISTINCT FROM 'USER'
                AND provider_payment_method_id IS NULL
                AND (status IS NOT DISTINCT FROM 'SUCCEEDED') IS NOT DISTINCT FROM (provider_setup_flow_id IS NOT NULL))
        OR (operation_type IS NOT DISTINCT FROM 'SETUP_COMPLETE' AND reason IS NOT DISTINCT FROM 'USER'
                AND status IS NOT DISTINCT FROM 'SUCCEEDED'
                AND provider_setup_flow_id IS NOT NULL AND provider_payment_method_id IS NOT NULL)
        OR (operation_type IS NOT DISTINCT FROM 'DETACH' AND reason IN ('USER','DUPLICATE','LIMIT')
                AND provider_setup_flow_id IS NULL AND provider_payment_method_id IS NOT NULL)
        OR (operation_type IS NOT DISTINCT FROM 'SET_DEFAULT' AND reason IN ('USER','DEFAULT_REPLACEMENT')
                AND provider_setup_flow_id IS NULL AND provider_payment_method_id IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED','FAILED')) IS NOT DISTINCT FROM (completed_at IS NOT NULL)),
    CHECK (status IS DISTINCT FROM 'SUCCEEDED' OR error_code IS NULL)
);
CREATE UNIQUE INDEX uq_saved_card_operation_in_flight ON saved_card_operations(user_id)
    WHERE status IN ('PENDING','UNCONFIRMED');
CREATE UNIQUE INDEX uq_saved_card_operation_setup_start ON saved_card_operations(provider_setup_flow_id)
    WHERE operation_type = 'SETUP_START';
CREATE UNIQUE INDEX uq_saved_card_operation_setup_complete ON saved_card_operations(provider_setup_flow_id)
    WHERE operation_type = 'SETUP_COMPLETE';
CREATE INDEX idx_saved_card_operations_user ON saved_card_operations(user_id, id);

CREATE FUNCTION f139_saved_card_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    db_now TIMESTAMP;
    started BIGINT;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Saved card operations cannot be removed';
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    IF TG_OP = 'INSERT' THEN
        IF NEW.operation_type = 'SETUP_COMPLETE' THEN
            -- Completion of a Setup Flow this user started successfully, for the same customer.
            SELECT id INTO started FROM saved_card_operations
             WHERE operation_type = 'SETUP_START' AND status = 'SUCCEEDED' AND user_id = NEW.user_id
               AND provider_customer_id = NEW.provider_customer_id AND provider_setup_flow_id = NEW.provider_setup_flow_id;
            IF started IS NULL THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A Setup Flow completion needs its successful start';
            END IF;
            IF NEW.status IS DISTINCT FROM 'SUCCEEDED' THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A Setup Flow completion is recorded as succeeded';
            END IF;
            NEW.completed_at := db_now;
        ELSE
            IF NEW.status IS DISTINCT FROM 'PENDING' OR NEW.provider_setup_flow_id IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A saved card operation starts as pending';
            END IF;
            NEW.completed_at := NULL;
        END IF;
        NEW.error_code := NULL;
        NEW.created_at := db_now;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE: what the operation is never changes.
    IF ROW(NEW.id, NEW.user_id, NEW.operation_type, NEW.reason, NEW.provider_customer_id,
           NEW.provider_payment_method_id, NEW.idempotency_key, NEW.created_at, NEW.recorded_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.user_id, OLD.operation_type, OLD.reason, OLD.provider_customer_id,
           OLD.provider_payment_method_id, OLD.idempotency_key, OLD.created_at, OLD.recorded_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A saved card operation cannot be redefined';
    END IF;
    IF NOT ((OLD.status = 'PENDING' AND NEW.status IN ('SUCCEEDED','FAILED','UNCONFIRMED'))
            OR (OLD.status = 'UNCONFIRMED' AND NEW.status IN ('SUCCEEDED','FAILED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A saved card operation status transition is not allowed';
    END IF;
    -- The Setup Flow id is learned once, when its start succeeds.
    IF NEW.provider_setup_flow_id IS DISTINCT FROM OLD.provider_setup_flow_id THEN
        IF OLD.operation_type IS DISTINCT FROM 'SETUP_START' OR OLD.provider_setup_flow_id IS NOT NULL
           OR NEW.status IS DISTINCT FROM 'SUCCEEDED' THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A Setup Flow id is recorded only when its start succeeds';
        END IF;
    END IF;
    IF NEW.status IN ('SUCCEEDED','FAILED') THEN
        NEW.completed_at := db_now;
    ELSE
        NEW.completed_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f139_saved_card_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON saved_card_operations
    FOR EACH ROW EXECUTE FUNCTION f139_saved_card_operation_guard();
CREATE TRIGGER f139_saved_card_operation_truncate BEFORE TRUNCATE ON saved_card_operations
    FOR EACH STATEMENT EXECUTE FUNCTION f139_saved_card_operation_guard();
