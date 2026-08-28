ALTER TABLE stock_movements
    DROP CONSTRAINT fk_stock_movements_changed_by_user;

ALTER TABLE stock_movements
    RENAME COLUMN changed_by_user_id
    TO changed_by_account_id;

ALTER TABLE stock_movements
    ALTER COLUMN changed_by_account_id DROP NOT NULL;

ALTER TABLE stock_movements
    ADD COLUMN changed_by_type VARCHAR(20);

UPDATE stock_movements
SET changed_by_type = 'ADMIN';

ALTER TABLE stock_movements
    ALTER COLUMN changed_by_type SET NOT NULL;

ALTER TABLE stock_movements
    ADD COLUMN order_id BIGINT;

ALTER TABLE stock_movements
    ADD CONSTRAINT fk_stock_movements_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id);

CREATE INDEX idx_stock_movements_order_id
    ON stock_movements(order_id);
