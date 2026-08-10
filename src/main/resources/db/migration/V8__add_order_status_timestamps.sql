ALTER TABLE orders
    ADD COLUMN paid_at TIMESTAMP,
    ADD COLUMN shipped_at TIMESTAMP,
    ADD COLUMN cancelled_at TIMESTAMP;