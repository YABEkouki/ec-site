-- Feature141-A: the legacy order item change (/orders/{id}/items, decreases only) is closed. Existing rows are kept.
-- Order content is changed only by an order change operation, which adopts its history in the same transaction:
-- an order edit (V74/V75) or the completion of a legacy quantity increase accepted before V108. The history is
-- inserted before the operation refers to it, so that rule is checked at commit.

CREATE FUNCTION f141_legacy_item_change_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'stock_movements' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='The legacy order item change is closed';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM order_change_operations
            WHERE content_change_history_id = NEW.id AND order_id = NEW.order_id) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='An order content change requires its order change operation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER f141_legacy_item_change_guard BEFORE INSERT ON stock_movements
    FOR EACH ROW WHEN (NEW.movement_type = 'ORDER_ITEM_CHANGE') EXECUTE FUNCTION f141_legacy_item_change_guard();
-- Rows recorded before V109 may still be touched; only a change into the closed type is refused.
CREATE TRIGGER f141_legacy_item_change_update_guard BEFORE UPDATE OF movement_type ON stock_movements
    FOR EACH ROW WHEN (NEW.movement_type = 'ORDER_ITEM_CHANGE' AND OLD.movement_type IS DISTINCT FROM NEW.movement_type)
    EXECUTE FUNCTION f141_legacy_item_change_guard();
CREATE CONSTRAINT TRIGGER f141_content_change_operation AFTER INSERT ON order_content_change_histories
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f141_legacy_item_change_guard();
