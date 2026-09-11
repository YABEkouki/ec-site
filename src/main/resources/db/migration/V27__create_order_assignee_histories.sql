ALTER TABLE order_handling_status_histories
    ADD COLUMN change_event_id UUID;

CREATE INDEX idx_order_handling_status_histories_change_event_id
    ON order_handling_status_histories (change_event_id);


CREATE TABLE order_assignee_histories (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,

    from_admin_account_id BIGINT,
    from_admin_username VARCHAR(100),

    to_admin_account_id BIGINT,
    to_admin_username VARCHAR(100),

    changed_by_account_id BIGINT NOT NULL,
    changed_by_username VARCHAR(100) NOT NULL,

    change_event_id UUID NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_order_assignee_histories_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_order_assignee_histories_order_changed_at
    ON order_assignee_histories (order_id, changed_at);

CREATE INDEX idx_order_assignee_histories_change_event_id
    ON order_assignee_histories (change_event_id);
