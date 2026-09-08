CREATE TABLE order_notes (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    note VARCHAR(1000) NOT NULL,
    created_by_account_id BIGINT NOT NULL,
    created_by_username VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_order_notes_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
);

CREATE INDEX idx_order_notes_order_id
    ON order_notes(order_id);
    