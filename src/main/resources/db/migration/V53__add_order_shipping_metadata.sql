ALTER TABLE orders
    ADD COLUMN shipping_preparation_started_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN shipping_preparation_started_by_account_id BIGINT,
    ADD COLUMN shipping_preparation_started_by_username VARCHAR(100),
    ADD COLUMN shipped_by_account_id BIGINT,
    ADD COLUMN shipped_by_username VARCHAR(100),
    ADD COLUMN shipment_capture_payment_id BIGINT,
    ADD CONSTRAINT chk_orders_shipping_preparation_metadata CHECK (
        (shipping_preparation_started_at IS NULL
            AND shipping_preparation_started_by_account_id IS NULL
            AND shipping_preparation_started_by_username IS NULL)
        OR
        (shipping_preparation_started_at IS NOT NULL
            AND shipping_preparation_started_by_account_id IS NOT NULL
            AND shipping_preparation_started_by_username IS NOT NULL
            AND shipping_preparation_started_by_username ~ '[^[:space:]]')
    ),
    ADD CONSTRAINT chk_orders_shipment_metadata CHECK (
        (shipped_by_account_id IS NULL
            AND shipped_by_username IS NULL
            AND shipment_capture_payment_id IS NULL)
        OR
        (shipped_by_account_id IS NOT NULL
            AND shipped_by_username IS NOT NULL
            AND shipped_by_username ~ '[^[:space:]]'
            AND status IS NOT NULL AND status = 'SHIPPED'
            AND shipped_at IS NOT NULL
            AND shipping_preparation_started_at IS NOT NULL)
    ),
    ADD CONSTRAINT fk_orders_shipment_capture_payment
        FOREIGN KEY (shipment_capture_payment_id) REFERENCES payments(id) ON DELETE RESTRICT;

CREATE INDEX idx_orders_shipment_capture_payment ON orders(id)
    WHERE shipment_capture_payment_id IS NOT NULL;
