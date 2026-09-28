CREATE TABLE
    order_shipping_address_histories (
        id BIGSERIAL PRIMARY KEY,
        order_id BIGINT NOT NULL,
        changed_by_type VARCHAR(20) NOT NULL,
        changed_by_account_id BIGINT,
        changed_by_username VARCHAR(100) NOT NULL,
        old_shipping_name VARCHAR(100),
        old_shipping_postal_code VARCHAR(8),
        old_shipping_prefecture VARCHAR(20),
        old_shipping_city VARCHAR(100),
        old_shipping_address_line VARCHAR(200),
        old_shipping_phone VARCHAR(20),
        new_shipping_name VARCHAR(100),
        new_shipping_postal_code VARCHAR(8),
        new_shipping_prefecture VARCHAR(20),
        new_shipping_city VARCHAR(100),
        new_shipping_address_line VARCHAR(200),
        new_shipping_phone VARCHAR(20),
        changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_order_shipping_address_histories_order FOREIGN KEY (order_id) REFERENCES orders (id)
    );

CREATE INDEX idx_order_shipping_address_histories_order_changed_at ON order_shipping_address_histories (order_id, changed_at, id);