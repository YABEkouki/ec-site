-- Feature139-C: saved cards for the order edit reauthorization (docs/feature139/saved-cards.md ch.7.5).
--
-- order_change_card_sends records what this site sent to the new Payment Flow of an order edit reauthorization
-- (Feature125 owned REAUTHORIZE route) when the customer chose a saved card instead of entering a card:
--   FLOW_UPDATE  POST /v2/payment_flows/{id}          customer_id + payment_method_id (once per operation)
--   CONFIRM      POST /v2/payment_flows/{id}/confirm  after the succeeded update of the same card, before the change
--                                                     deadline; a second confirm only after the first was not delivered
-- The one-time confirmation start of the operation (order_change_operations.browser_confirmation_issued_at, V60/V82)
-- must already be recorded: a saved card uses the same single chance as the card entry screen. The authorization,
-- the old authorization cancel, the switch, the not-adopted cancel and the 3-D Secure failure closure are still
-- decided by the existing code by reading the Payment Flow; these rows are the external-send and audit records only.
-- They never hold card numbers, client secrets or PAY.JP response bodies.
-- Status: PENDING -> SUCCEEDED / FAILED / UNCONFIRMED, UNCONFIRMED -> SUCCEEDED / FAILED (final otherwise).
-- One send in flight per operation. Lock order of writers: f125_reauth_lock (execution control), orders, payments.
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

CREATE TABLE order_change_card_sends (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL REFERENCES order_change_operations(id) ON DELETE RESTRICT,
    admission_id UUID NOT NULL REFERENCES order_edit_admissions(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    transaction_id BIGINT NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    send_type VARCHAR(20) NOT NULL CHECK (send_type IN ('FLOW_UPDATE','CONFIRM')),
    provider_payment_flow_id VARCHAR(255) NOT NULL CHECK (provider_payment_flow_id ~ '^pfw_[A-Za-z0-9_-]{1,251}$'),
    provider_customer_id VARCHAR(100) NOT NULL CHECK (provider_customer_id ~ '^[A-Za-z0-9_-]{1,100}$'),
    provider_payment_method_id VARCHAR(255) NOT NULL CHECK (provider_payment_method_id ~ '^pm_[A-Za-z0-9_-]{1,252}$'),
    idempotency_key VARCHAR(100) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,100}$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNCONFIRMED')),
    error_code VARCHAR(100) CHECK (error_code ~ '^[A-Za-z0-9_]{1,100}$'),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_order_change_card_send_key UNIQUE (idempotency_key),
    CHECK ((status IN ('SUCCEEDED','FAILED')) IS NOT DISTINCT FROM (completed_at IS NOT NULL)),
    CHECK (status IS DISTINCT FROM 'SUCCEEDED' OR error_code IS NULL)
);
CREATE UNIQUE INDEX uq_order_change_card_send_in_flight ON order_change_card_sends(operation_id)
    WHERE status IN ('PENDING','UNCONFIRMED');
CREATE UNIQUE INDEX uq_order_change_card_send_update ON order_change_card_sends(operation_id)
    WHERE send_type = 'FLOW_UPDATE';
CREATE INDEX idx_order_change_card_sends_operation ON order_change_card_sends(operation_id, id);
CREATE INDEX idx_order_change_card_sends_order ON order_change_card_sends(order_id);

CREATE FUNCTION f139_order_change_card_send_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    db_now TIMESTAMP;
    o orders%ROWTYPE;
    op order_change_operations%ROWTYPE;
    a order_edit_admissions%ROWTYPE;
    p payments%ROWTYPE;
    t payment_transactions%ROWTYPE;
    upd order_change_card_sends%ROWTYPE;
    prior order_change_card_sends%ROWTYPE;
    confirms INTEGER;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Order change card sends cannot be removed';
    END IF;
    db_now := timezone('Asia/Tokyo', clock_timestamp());
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO o FROM orders WHERE id = NEW.order_id FOR SHARE;
        SELECT * INTO op FROM order_change_operations WHERE id = NEW.operation_id FOR SHARE;
        SELECT * INTO a FROM order_edit_admissions WHERE id = NEW.admission_id;
        SELECT * INTO p FROM payments WHERE id = NEW.payment_id FOR SHARE;
        SELECT * INTO t FROM payment_transactions WHERE id = NEW.transaction_id FOR SHARE;
        IF o.id IS NULL OR op.id IS NULL OR a.id IS NULL OR p.id IS NULL OR t.id IS NULL
           OR o.user_id IS DISTINCT FROM NEW.user_id OR o.status IS DISTINCT FROM 'ORDERED'
           OR op.order_id IS DISTINCT FROM o.id OR op.customer_id IS DISTINCT FROM NEW.user_id
           OR a.order_id IS DISTINCT FROM o.id OR a.customer_id IS DISTINCT FROM NEW.user_id
           OR a.submission_key IS DISTINCT FROM op.submission_key OR a.route IS DISTINCT FROM 'REAUTHORIZE'
           OR a.admission_state IS DISTINCT FROM 'ACCEPTED' OR a.closed_at IS NOT NULL
           OR NOT f125_reauth_is_owned(o.id, op.id)
           OR op.state IS DISTINCT FROM 'AUTHORIZING' OR op.switch_decided_at IS NOT NULL
           OR op.abort_request_id IS NOT NULL OR op.browser_confirmation_issued_at IS NULL
           OR op.new_payment_id IS DISTINCT FROM p.id
           OR p.order_id IS DISTINCT FROM o.id OR p.provider IS DISTINCT FROM 'PAYJP'
           OR p.payment_method IS DISTINCT FROM 'CARD' OR p.status IS DISTINCT FROM 'PENDING'
           OR p.provider_payment_id IS DISTINCT FROM NEW.provider_payment_flow_id
           OR t.payment_id IS DISTINCT FROM p.id OR t.transaction_type IS DISTINCT FROM 'AUTHORIZE'
           OR t.status IS DISTINCT FROM 'PENDING' OR t.purpose IS DISTINCT FROM 'ORDER_CHANGE_AUTHORIZATION'
           OR t.order_change_operation_id IS DISTINCT FROM op.id THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='An order change card send needs the confirmation-started pending reauthorization of the user''s order edit';
        END IF;
        IF NEW.send_type = 'CONFIRM' THEN
            SELECT * INTO upd FROM order_change_card_sends
             WHERE operation_id = NEW.operation_id AND send_type = 'FLOW_UPDATE';
            IF upd.id IS NULL OR upd.status IS DISTINCT FROM 'SUCCEEDED'
               OR upd.provider_payment_method_id IS DISTINCT FROM NEW.provider_payment_method_id
               OR upd.provider_customer_id IS DISTINCT FROM NEW.provider_customer_id
               OR upd.provider_payment_flow_id IS DISTINCT FROM NEW.provider_payment_flow_id THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A confirm needs the succeeded saved card update of the same card';
            END IF;
            IF o.change_deadline_at IS NULL OR db_now >= o.change_deadline_at THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A saved card confirm cannot be sent at or after the change deadline';
            END IF;
            SELECT count(*) INTO confirms FROM order_change_card_sends
             WHERE operation_id = NEW.operation_id AND send_type = 'CONFIRM';
            IF confirms >= 2 THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='At most two saved card confirms per order change';
            END IF;
            IF confirms = 1 THEN
                SELECT * INTO prior FROM order_change_card_sends
                 WHERE operation_id = NEW.operation_id AND send_type = 'CONFIRM';
                IF prior.status IS DISTINCT FROM 'FAILED' THEN
                    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='A saved card confirm is resent only after it was not delivered';
                END IF;
            END IF;
        END IF;
        NEW.status := 'PENDING';
        NEW.error_code := NULL;
        NEW.completed_at := NULL;
        NEW.created_at := db_now;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    IF ROW(NEW.id, NEW.user_id, NEW.order_id, NEW.operation_id, NEW.admission_id, NEW.payment_id, NEW.transaction_id,
           NEW.send_type, NEW.provider_payment_flow_id, NEW.provider_customer_id, NEW.provider_payment_method_id,
           NEW.idempotency_key, NEW.created_at, NEW.recorded_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.user_id, OLD.order_id, OLD.operation_id, OLD.admission_id, OLD.payment_id,
           OLD.transaction_id, OLD.send_type, OLD.provider_payment_flow_id, OLD.provider_customer_id,
           OLD.provider_payment_method_id, OLD.idempotency_key, OLD.created_at, OLD.recorded_at) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order change card send cannot be redefined';
    END IF;
    IF NOT ((OLD.status = 'PENDING' AND NEW.status IN ('SUCCEEDED','FAILED','UNCONFIRMED'))
            OR (OLD.status = 'UNCONFIRMED' AND NEW.status IN ('SUCCEEDED','FAILED'))) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An order change card send status transition is not allowed';
    END IF;
    IF NEW.status IN ('SUCCEEDED','FAILED') THEN
        NEW.completed_at := db_now;
    ELSE
        NEW.completed_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER f139_order_change_card_send_guard BEFORE INSERT OR UPDATE OR DELETE ON order_change_card_sends
    FOR EACH ROW EXECUTE FUNCTION f139_order_change_card_send_guard();
CREATE TRIGGER f139_order_change_card_send_truncate BEFORE TRUNCATE ON order_change_card_sends
    FOR EACH STATEMENT EXECUTE FUNCTION f139_order_change_card_send_guard();
