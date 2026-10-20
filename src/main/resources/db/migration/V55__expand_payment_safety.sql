-- Expand only: nullable columns preserve existing writers. No classification or backfill.
ALTER TABLE orders ADD COLUMN active_payment_id BIGINT;
ALTER TABLE payment_transactions ADD COLUMN purpose VARCHAR(40) COLLATE "C";

-- This identity supports order ownership, while allowing multiple payments per order.
ALTER TABLE payments ADD CONSTRAINT uq_payments_order_identity UNIQUE (order_id, id);
ALTER TABLE orders ADD CONSTRAINT fk_orders_active_payment_owner
    FOREIGN KEY (id, active_payment_id) REFERENCES payments (order_id, id)
    MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT NOT VALID;

-- Future order-change purposes remain disabled. Existing writers may leave purpose NULL.
ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transactions_purpose_type_1b CHECK (
    purpose IS NULL
    OR purpose = 'LEGACY_UNKNOWN'
    OR (transaction_type COLLATE "C" = 'AUTHORIZE' AND purpose = 'INITIAL_ORDER_AUTHORIZATION')
    OR (transaction_type COLLATE "C" = 'CANCEL' AND purpose = 'ORDER_CANCELLATION')
    OR (transaction_type COLLATE "C" = 'CAPTURE' AND purpose = 'SHIPMENT_CAPTURE')
) NOT VALID;

CREATE INDEX idx_orders_active_payment ON orders (active_payment_id)
    WHERE active_payment_id IS NOT NULL;
