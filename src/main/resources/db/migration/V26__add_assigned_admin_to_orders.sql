ALTER TABLE orders
    ADD COLUMN assigned_admin_account_id BIGINT;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_assigned_admin_account
    FOREIGN KEY (assigned_admin_account_id)
    REFERENCES admin_accounts(id);

CREATE INDEX idx_orders_assigned_admin_account_id
    ON orders (assigned_admin_account_id);
