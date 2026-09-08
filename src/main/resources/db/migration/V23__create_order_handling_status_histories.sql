CREATE TABLE order_handling_status_histories (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(30) NOT NULL,
    to_status VARCHAR(30) NOT NULL,
    changed_by_account_id BIGINT NOT NULL,
    changed_by_username VARCHAR(100) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_order_handling_status_histories_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
        ON DELETE CASCADE
);
