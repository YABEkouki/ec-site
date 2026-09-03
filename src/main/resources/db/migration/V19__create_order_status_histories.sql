CREATE TABLE order_status_histories (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    changed_by_type VARCHAR(20) NOT NULL,
    changed_by_account_id BIGINT,
    changed_by_username VARCHAR(100) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_order_status_histories_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
);

CREATE INDEX idx_order_status_histories_order_changed_at
    ON order_status_histories (
        order_id,
        changed_at,
        id
    );
