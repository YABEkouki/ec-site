-- Feature139-B: saved cards at checkout (docs/feature139/saved-cards.md ch.6.4).
--
-- checkout_card_sends records what this site sent to the checkout Payment Flow of an order:
--   FLOW_UPDATE  POST /v2/payment_flows/{id}          customer_id (+ payment_method_id for SAVED_CARD)
--   CONFIRM      POST /v2/payment_flows/{id}/confirm  only after a succeeded SAVED_CARD update of the same card
-- purpose SAVED_CARD pays with a saved card; REGISTER_NEW_CARD only sets the customer before the browser confirms
-- a new card that is then attached after the authorization. The authorization itself is still decided by reading
-- the Payment Flow (existing refresh); these rows are the external-send and audit records only. They never hold
-- card numbers, client secrets or PAY.JP response bodies.
-- Status: PENDING -> SUCCEEDED / FAILED / UNCONFIRMED, UNCONFIRMED -> SUCCEEDED / FAILED (final otherwise).
-- One send in flight per authorization transaction; at most three CONFIRM sends per transaction (decision 6.10-1).
-- A send is created only for the pending initial authorization of an ORDERED order of the same user, whose active
-- card Payment holds the Payment Flow. Lock order: orders, then payments, then payment_transactions.
--
-- saved_card_operations (V105) gains ATTACH: attach the card of a checkout to the customer.
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

CREATE TABLE checkout_card_sends (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    transaction_id BIGINT NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    send_type VARCHAR(20) NOT NULL CHECK (send_type IN ('FLOW_UPDATE','CONFIRM')),
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('SAVED_CARD','REGISTER_NEW_CARD')),
    provider_payment_flow_id VARCHAR(255) NOT NULL CHECK (provider_payment_flow_id ~ '^pfw_[A-Za-z0-9_-]{1,251}$'),
    provider_customer_id VARCHAR(100) NOT NULL CHECK (provider_customer_id ~ '^[A-Za-z0-9_-]{1,100}$'),
    provider_payment_method_id VARCHAR(255) CHECK (provider_payment_method_id ~ '^pm_[A-Za-z0-9_-]{1,252}$'),
    idempotency_key VARCHAR(100) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,100}$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNCONFIRMED')),
    error_code VARCHAR(100) CHECK (error_code ~ '^[A-Za-z0-9_]{1,100}$'),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_checkout_card_send_key UNIQUE (idempotency_key),
    CHECK ((purpose IS NOT DISTINCT FROM 'SAVED_CARD') IS NOT DISTINCT FROM (provider_payment_method_id IS NOT NULL)),
    CHECK (send_type IS NOT DISTINCT FROM 'FLOW_UPDATE' OR purpose IS NOT DISTINCT FROM 'SAVED_CARD'),
    CHECK ((status IN ('SUCCEEDED','FAILED')) IS NOT DISTINCT FROM (completed_at IS NOT NULL)),
    CHECK (status IS DISTINCT FROM 'SUCCEEDED' OR error_code IS NULL)
);
CREATE UNIQUE INDEX uq_checkout_card_send_in_flight ON checkout_card_sends(transaction_id)
    WHERE status IN ('PENDING','UNCONFIRMED');
CREATE INDEX idx_checkout_card_sends_transaction ON checkout_card_sends(transaction_id, id);
CREATE INDEX idx_checkout_card_sends_order ON checkout_card_sends(order_id);

CREATE FUNCTION f139_checkout_card_send_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    db_now TIMESTAMP;
    o orders%ROWTYPE;
    p payments%ROWTYPE;
    t payment_transactions%ROWTYPE;
    latest checkout_card_sends%ROWTYPE;
    confirms INTEGER;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Checkout card sends cannot be removed';
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO o FROM orders WHERE id = NEW.order_id FOR SHARE;
        SELECT * INTO p FROM payments WHERE id = NEW.payment_id FOR SHARE;
        SELECT * INTO t FROM payment_transactions WHERE id = NEW.transaction_id FOR SHARE;
        IF o.id IS NULL OR p.id IS NULL OR t.id IS NULL
           OR o.user_id IS DISTINCT FROM NEW.user_id OR o.status IS DISTINCT FROM 'ORDERED'
           OR o.active_payment_id IS DISTINCT FROM p.id OR p.order_id IS DISTINCT FROM o.id
           OR p.provider IS DISTINCT FROM 'PAYJP' OR p.payment_method IS DISTINCT FROM 'CARD'
           OR p.provider_payment_id IS DISTINCT FROM NEW.provider_payment_flow_id
           OR t.payment_id IS DISTINCT FROM p.id OR t.transaction_type IS DISTINCT FROM 'AUTHORIZE'
           OR t.status IS DISTINCT FROM 'PENDING'
           OR (t.purpose IS NOT NULL AND t.purpose IS DISTINCT FROM 'INITIAL_ORDER_AUTHORIZATION') THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='A checkout card send needs the pending initial authorization of the user''s ordered card payment';
        END IF;
        IF NEW.send_type = 'CONFIRM' THEN
            SELECT * INTO latest FROM checkout_card_sends
             WHERE transaction_id = NEW.transaction_id AND send_type = 'FLOW_UPDATE' ORDER BY id DESC LIMIT 1;
            IF latest.id IS NULL OR latest.status IS DISTINCT FROM 'SUCCEEDED' OR latest.purpose IS DISTINCT FROM 'SAVED_CARD'
               OR latest.provider_payment_method_id IS DISTINCT FROM NEW.provider_payment_method_id
               OR latest.provider_customer_id IS DISTINCT FROM NEW.provider_customer_id THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A confirm needs a succeeded saved card update of the same card';
            END IF;
            SELECT count(*) INTO confirms FROM checkout_card_sends
             WHERE transaction_id = NEW.transaction_id AND send_type = 'CONFIRM';
            IF confirms >= 3 THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='At most three saved card confirms per authorization';
            END IF;
        END IF;
        NEW.status := 'PENDING';
        NEW.error_code := NULL;
        NEW.completed_at := NULL;
        NEW.created_at := db_now;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    IF ROW(NEW.id, NEW.user_id, NEW.order_id, NEW.payment_id, NEW.transaction_id, NEW.send_type, NEW.purpose,
           NEW.provider_payment_flow_id, NEW.provider_customer_id, NEW.provider_payment_method_id, NEW.idempotency_key,
           NEW.created_at, NEW.recorded_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.user_id, OLD.order_id, OLD.payment_id, OLD.transaction_id, OLD.send_type,
           OLD.purpose, OLD.provider_payment_flow_id, OLD.provider_customer_id, OLD.provider_payment_method_id,
           OLD.idempotency_key, OLD.created_at, OLD.recorded_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A checkout card send cannot be redefined';
    END IF;
    IF NOT ((OLD.status = 'PENDING' AND NEW.status IN ('SUCCEEDED','FAILED','UNCONFIRMED'))
            OR (OLD.status = 'UNCONFIRMED' AND NEW.status IN ('SUCCEEDED','FAILED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='A checkout card send status transition is not allowed';
    END IF;
    IF NEW.status IN ('SUCCEEDED','FAILED') THEN
        NEW.completed_at := db_now;
    ELSE
        NEW.completed_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f139_checkout_card_send_guard BEFORE INSERT OR UPDATE OR DELETE ON checkout_card_sends
    FOR EACH ROW EXECUTE FUNCTION f139_checkout_card_send_guard();
CREATE TRIGGER f139_checkout_card_send_truncate BEFORE TRUNCATE ON checkout_card_sends
    FOR EACH STATEMENT EXECUTE FUNCTION f139_checkout_card_send_guard();

-- saved_card_operations: ATTACH (card required, no Setup Flow, idempotency key, reason USER).
ALTER TABLE saved_card_operations DROP CONSTRAINT saved_card_operations_operation_type_check;
ALTER TABLE saved_card_operations ADD CONSTRAINT saved_card_operations_operation_type_check
    CHECK (operation_type IN ('CUSTOMER_CREATE','SETUP_START','SETUP_COMPLETE','DETACH','SET_DEFAULT','ATTACH'));
ALTER TABLE saved_card_operations DROP CONSTRAINT saved_card_operations_check1;
ALTER TABLE saved_card_operations ADD CONSTRAINT saved_card_operations_check1
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
                AND provider_setup_flow_id IS NULL AND provider_payment_method_id IS NOT NULL)
        OR (operation_type IS NOT DISTINCT FROM 'ATTACH' AND reason IS NOT DISTINCT FROM 'USER'
                AND provider_setup_flow_id IS NULL AND provider_payment_method_id IS NOT NULL));
