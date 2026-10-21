-- One first-confirm screen per operation, across tabs, sessions and process restarts.
-- Stores no credential or browser result. Losing the response must not permit another confirm.
ALTER TABLE order_change_operations ADD COLUMN browser_confirmation_issued_at TIMESTAMP;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_browser_confirmation
    CHECK (browser_confirmation_issued_at IS NULL OR new_payment_id IS NOT NULL);
CREATE FUNCTION protect_order_change_browser_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.browser_confirmation_issued_at IS NOT NULL
        AND NEW.browser_confirmation_issued_at IS DISTINCT FROM OLD.browser_confirmation_issued_at THEN
        RAISE EXCEPTION 'Issued browser confirmation cannot be reset';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_change_browser_confirmation_immutable BEFORE UPDATE ON order_change_operations
    FOR EACH ROW EXECUTE FUNCTION protect_order_change_browser_confirmation();
