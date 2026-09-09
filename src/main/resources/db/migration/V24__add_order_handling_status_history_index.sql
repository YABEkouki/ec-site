CREATE INDEX idx_order_handling_status_histories_order_changed_at
    ON order_handling_status_histories (order_id, changed_at DESC);
