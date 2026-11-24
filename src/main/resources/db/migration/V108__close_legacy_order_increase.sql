-- Feature140-A: the legacy quantity increase (Feature124, execution_version IS NULL) accepts nothing new.
-- Existing operations keep their state changes, reconciliation, withdrawal and reservation settlement; only a new
-- operation, a new reservation and the start of a new authorization are refused. Order edits (execution_version=1)
-- are not affected: they are inserted by the V74/V75 functions and hold stock in order_edit_inventory_plans.

CREATE FUNCTION f140_legacy_increase_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'order_change_operations' THEN
        IF NEW.execution_version IS NOT NULL THEN
            RETURN NEW;
        END IF;
        IF TG_OP = 'INSERT' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The legacy quantity increase is closed';
        END IF;
        IF OLD.new_payment_id IS NULL AND NEW.new_payment_id IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='The legacy quantity increase is closed; no new authorization can start';
        END IF;
        RETURN NEW;
    END IF;
    -- order_change_items and inventory_reservations are written only when a legacy increase is accepted.
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The legacy quantity increase is closed';
END;
$$;

CREATE TRIGGER f140_legacy_increase_guard BEFORE INSERT OR UPDATE ON order_change_operations
    FOR EACH ROW EXECUTE FUNCTION f140_legacy_increase_guard();
CREATE TRIGGER f140_legacy_increase_guard BEFORE INSERT ON order_change_items
    FOR EACH ROW EXECUTE FUNCTION f140_legacy_increase_guard();
CREATE TRIGGER f140_legacy_increase_guard BEFORE INSERT ON inventory_reservations
    FOR EACH ROW EXECUTE FUNCTION f140_legacy_increase_guard();
