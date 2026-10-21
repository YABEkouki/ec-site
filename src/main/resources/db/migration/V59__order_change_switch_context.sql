-- Connected reauthorization context. No old rows, requests or financial history are rewritten.
ALTER TABLE order_change_operations ADD COLUMN base_order_fingerprint VARCHAR(64) COLLATE "C"
    CHECK(base_order_fingerprint IS NULL OR base_order_fingerprint ~ '^[0-9a-f]{64}$');
ALTER TABLE order_change_operations ADD COLUMN financial_review_reason VARCHAR(80);
ALTER TABLE order_change_operations ADD COLUMN content_change_history_id BIGINT UNIQUE REFERENCES order_content_change_histories(id);
ALTER TABLE order_content_change_histories ADD CONSTRAINT uq_content_change_order_identity UNIQUE(order_id,id);
ALTER TABLE order_change_operations ADD CONSTRAINT fk_change_content_history_owner FOREIGN KEY(order_id,content_change_history_id)
    REFERENCES order_content_change_histories(order_id,id);
CREATE FUNCTION protect_order_change_base_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.base_order_fingerprint IS NOT NULL AND NEW.base_order_fingerprint IS DISTINCT FROM OLD.base_order_fingerprint THEN
        RAISE EXCEPTION 'Order change base fingerprint is immutable';
    END IF;
    IF OLD.state='COMPLETED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Completed order change is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER order_change_base_context BEFORE UPDATE ON order_change_operations
    FOR EACH ROW EXECUTE FUNCTION protect_order_change_base_context();
