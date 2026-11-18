-- Feature135-A1: PAY.JP authorization expiry is recorded for every AUTHORIZED payment and gates shipping.
--
-- PAY.JP (API v2) cancels an uncaptured Payment Flow when its authorization expires (status canceled,
-- cancellation_reason expired; observed in test mode about 18 minutes after expired_at). A payment that is still
-- AUTHORIZED locally can therefore no longer be captured. The expiry is judged locally from expired_at.
--
-- Invariant: every payment that becomes AUTHORIZED has exactly one row here, created by this trigger as a
-- conservative ESTIMATED value (payment created_at + 5 days: the authorization is never earlier than the Flow,
-- and 5 days is the shortest default period, for overseas cards). The application replaces it once with the
-- PROVIDER value (expired_at) in the transaction that applies the authorization. Rows are never deleted
-- except together with their payment (ON DELETE CASCADE).
--
-- Gates (application and database):
--   * shipping preparation (warehouse export or administrator) needs expires_at > now + 24 hours;
--   * starting the shipment capture needs expires_at > now.
-- Times are Asia/Tokyo wall-clock values read from the database clock. No function sets timezone.

CREATE TABLE payment_authorization_expiries (
    payment_id BIGINT PRIMARY KEY REFERENCES payments(id) ON DELETE CASCADE,
    authorization_base_at TIMESTAMP NOT NULL,
    estimated_expires_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    source VARCHAR(20) NOT NULL CHECK (source IN ('ESTIMATED','PROVIDER')),
    created_at TIMESTAMP NOT NULL,
    provider_recorded_at TIMESTAMP,
    CONSTRAINT chk_pae_estimate CHECK (estimated_expires_at = authorization_base_at + INTERVAL '5 days'),
    CONSTRAINT chk_pae_source CHECK ((source = 'PROVIDER') = (provider_recorded_at IS NOT NULL)),
    CONSTRAINT chk_pae_estimated_value CHECK (source <> 'ESTIMATED' OR expires_at = estimated_expires_at),
    CONSTRAINT chk_pae_provider_range CHECK (expires_at > authorization_base_at
        AND expires_at <= authorization_base_at + INTERVAL '61 days')
);
CREATE INDEX idx_payment_authorization_expiries_expires ON payment_authorization_expiries(expires_at);

-- Existing AUTHORIZED payments: conservative estimate from the payment creation time.
INSERT INTO payment_authorization_expiries(payment_id, authorization_base_at, estimated_expires_at, expires_at, source, created_at)
SELECT p.id, p.created_at, p.created_at + INTERVAL '5 days', p.created_at + INTERVAL '5 days', 'ESTIMATED',
       timezone('Asia/Tokyo', clock_timestamp())
FROM payments p WHERE p.status = 'AUTHORIZED';

CREATE FUNCTION f135_expiry_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments%ROWTYPE;
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Authorization expiries cannot be truncated';
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- Only as the cascade of deleting the payment itself.
        IF EXISTS(SELECT 1 FROM payments WHERE id = OLD.payment_id) THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='Authorization expiries are deleted only with their payment';
        END IF;
        RETURN OLD;
    END IF;
    SELECT * INTO p FROM payments WHERE id = NEW.payment_id;
    IF p.id IS NULL OR p.status <> 'AUTHORIZED' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='An authorization expiry is recorded only for an AUTHORIZED payment';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- The values are always derived here; callers cannot choose them.
        NEW.authorization_base_at = p.created_at;
        NEW.estimated_expires_at = p.created_at + INTERVAL '5 days';
        NEW.expires_at = NEW.estimated_expires_at;
        NEW.source = 'ESTIMATED';
        NEW.created_at = timezone('Asia/Tokyo', clock_timestamp());
        NEW.provider_recorded_at = NULL;
        RETURN NEW;
    END IF;
    -- UPDATE: exactly one replacement of the estimate by the provider value.
    IF OLD.source <> 'ESTIMATED' OR NEW.source <> 'PROVIDER'
        OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
        OR NEW.authorization_base_at IS DISTINCT FROM OLD.authorization_base_at
        OR NEW.estimated_expires_at IS DISTINCT FROM OLD.estimated_expires_at
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='An authorization expiry changes only once, from ESTIMATED to PROVIDER';
    END IF;
    NEW.provider_recorded_at = timezone('Asia/Tokyo', clock_timestamp());
    RETURN NEW;
END;
$$;
CREATE TRIGGER f135_expiry_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_authorization_expiries
    FOR EACH ROW EXECUTE FUNCTION f135_expiry_guard();
CREATE TRIGGER f135_expiry_truncate BEFORE TRUNCATE ON payment_authorization_expiries
    FOR EACH STATEMENT EXECUTE FUNCTION f135_expiry_guard();

-- Every payment that becomes AUTHORIZED gets its conservative estimate in the same transaction.
CREATE FUNCTION f135_payment_authorized() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'AUTHORIZED' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'AUTHORIZED') THEN
        INSERT INTO payment_authorization_expiries(payment_id, authorization_base_at, estimated_expires_at, expires_at, source, created_at)
        VALUES (NEW.id, NEW.created_at, NEW.created_at + INTERVAL '5 days', NEW.created_at + INTERVAL '5 days', 'ESTIMATED',
                timezone('Asia/Tokyo', clock_timestamp()))
        ON CONFLICT (payment_id) DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER zz_f135_payment_authorized AFTER INSERT OR UPDATE OF status ON payments
    FOR EACH ROW EXECUTE FUNCTION f135_payment_authorized();

-- True when the payment's recorded authorization is still valid strictly after the given margin.
CREATE FUNCTION f135_authorization_valid_beyond(target BIGINT, margin INTERVAL) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT 1 FROM payment_authorization_expiries e
                  WHERE e.payment_id = target
                    AND e.expires_at > timezone('Asia/Tokyo', clock_timestamp()) + margin);
$$;

-- Warehouse export line: runs after f131_line_guard (trigger name order), which has set NEW.payment_id.
CREATE FUNCTION f135_line_expiry_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.payment_id IS NOT NULL AND NOT f135_authorization_valid_beyond(NEW.payment_id, INTERVAL '24 hours') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The authorization expires too soon for the warehouse export';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f135_line_expiry_guard BEFORE INSERT ON warehouse_export_lines
    FOR EACH ROW EXECUTE FUNCTION f135_line_expiry_guard();

-- Orders: starting shipping preparation of a card order, and recording a card shipment (capture start).
CREATE FUNCTION f135_order_expiry_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.shipping_preparation_started_at IS NULL AND NEW.shipping_preparation_started_at IS NOT NULL
        AND NEW.status = 'ORDERED'
        AND EXISTS(SELECT 1 FROM payments p WHERE p.order_id = NEW.id AND p.status = 'AUTHORIZED'
                   AND NOT f135_authorization_valid_beyond(p.id, INTERVAL '24 hours')) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The authorization expires too soon for shipping preparation';
    END IF;
    IF OLD.shipment_capture_payment_id IS NULL AND NEW.shipment_capture_payment_id IS NOT NULL
        AND OLD.status = 'ORDERED' AND NEW.status = 'SHIPPED'
        AND NOT f135_authorization_valid_beyond(NEW.shipment_capture_payment_id, INTERVAL '0 seconds') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The authorization has expired; the shipment capture cannot start';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f135_order_expiry_guard BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION f135_order_expiry_guard();

-- Warehouse export holds: new reason.
ALTER TABLE warehouse_export_holds DROP CONSTRAINT warehouse_export_holds_reason_check;
ALTER TABLE warehouse_export_holds ADD CONSTRAINT warehouse_export_holds_reason_check CHECK (reason IN (
    'MANUALLY_PREPARED','NOT_EXPORTABLE_STATUS','ORDER_CHANGE_UNRESOLVED','PAYMENT_NOT_READY',
    'AUTHORIZATION_EXPIRING','NOT_DUE','DATABASE_GUARD','FAILED'));
