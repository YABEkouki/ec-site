-- Quantity-increase candidates only. No changes to payment purposes, active references or V54.
ALTER TABLE orders ADD CONSTRAINT uq_orders_change_customer UNIQUE (id,user_id);
CREATE TABLE order_change_operations (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    customer_id BIGINT NOT NULL REFERENCES users(id),
    submission_key UUID NOT NULL,
    request_digest VARCHAR(64) COLLATE "C" NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    base_content_revision INTEGER NOT NULL CHECK (base_content_revision >= 0),
    old_payment_id BIGINT NOT NULL,
    before_amount INTEGER NOT NULL CHECK (before_amount > 0),
    after_amount INTEGER NOT NULL CHECK (after_amount > 0),
    item_subtotal INTEGER NOT NULL CHECK (item_subtotal >= 0),
    charge_total INTEGER NOT NULL CHECK (charge_total >= 0),
    tax_amount INTEGER NOT NULL CHECK (tax_amount >= 0),
    shipping_name VARCHAR(100) NOT NULL,
    shipping_amount INTEGER NOT NULL CHECK (shipping_amount >= 0),
    shipping_tax_category_id BIGINT NOT NULL,
    shipping_tax_category_code VARCHAR(30) NOT NULL,
    shipping_tax_category_name VARCHAR(100) NOT NULL,
    shipping_tax_rate NUMERIC(5,2) NOT NULL,
    shipping_display_order INTEGER NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    deadline_at TIMESTAMP NOT NULL,
    grace_until TIMESTAMP NOT NULL,
    switch_decided_at TIMESTAMP,
    state VARCHAR(30) NOT NULL CHECK (state IN ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN',
        'AUTHORIZED','OLD_CANCEL_PENDING','OLD_CANCEL_UNKNOWN','OLD_CANCEL_REJECTED','COMMIT_PENDING',
        'COMPLETED','ABORTING','ABORT_UNKNOWN','ABORTED','MANUAL_REVIEW')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_order_change_submission UNIQUE (order_id, submission_key),
    CONSTRAINT uq_order_change_owner UNIQUE (id, order_id),
    CONSTRAINT fk_order_change_customer FOREIGN KEY (order_id,customer_id) REFERENCES orders(id,user_id),
    CONSTRAINT fk_order_change_old_payment FOREIGN KEY (order_id,old_payment_id) REFERENCES payments(order_id,id),
    CONSTRAINT ck_order_change_amount CHECK (after_amount = item_subtotal::BIGINT + charge_total AND tax_amount <= after_amount AND charge_total = shipping_amount),
    CONSTRAINT ck_order_change_deadlines CHECK (accepted_at < deadline_at AND grace_until = deadline_at + INTERVAL '10 minutes'),
    CONSTRAINT ck_order_change_decision CHECK (switch_decided_at IS NULL OR state IN
        ('OLD_CANCEL_PENDING','OLD_CANCEL_UNKNOWN','OLD_CANCEL_REJECTED','COMMIT_PENDING','COMPLETED','MANUAL_REVIEW'))
);
CREATE UNIQUE INDEX uq_order_change_unresolved ON order_change_operations(order_id)
    WHERE state NOT IN ('COMPLETED','ABORTED');
CREATE INDEX idx_order_change_reserved_expiry ON order_change_operations(grace_until,order_id)
    WHERE state = 'RESERVED';
CREATE TABLE order_change_items (
    operation_id UUID NOT NULL,
    order_id BIGINT NOT NULL,
    -- Historical item ID: checked under Order lock on acceptance. No live-item FK,
    -- so a terminal candidate does not prevent a later ordinary item removal.
    order_item_id BIGINT NOT NULL CHECK (order_item_id > 0),
    product_id BIGINT NOT NULL REFERENCES products(id),
    before_quantity INTEGER NOT NULL CHECK (before_quantity > 0),
    after_quantity INTEGER NOT NULL CHECK (after_quantity > before_quantity),
    increase_quantity INTEGER NOT NULL CHECK (increase_quantity = after_quantity::BIGINT - before_quantity),
    unit_price INTEGER NOT NULL CHECK (unit_price >= 0),
    tax_category_id BIGINT NOT NULL,
    tax_category_code VARCHAR(30) NOT NULL,
    tax_category_name VARCHAR(100) NOT NULL,
    tax_rate NUMERIC(5,2) NOT NULL,
    before_subtotal INTEGER NOT NULL CHECK (before_subtotal = unit_price::BIGINT * before_quantity),
    after_subtotal INTEGER NOT NULL CHECK (after_subtotal = unit_price::BIGINT * after_quantity),
    PRIMARY KEY (operation_id,order_item_id),
    CONSTRAINT fk_change_item_operation FOREIGN KEY(operation_id,order_id) REFERENCES order_change_operations(id,order_id),
    CONSTRAINT uq_change_item_reservation UNIQUE(operation_id,order_item_id,product_id,increase_quantity)
);
CREATE TABLE inventory_reservations (
    operation_id UUID NOT NULL,
    order_item_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    state VARCHAR(10) NOT NULL CHECK (state IN ('HELD','CONSUMED','RELEASED')),
    hold_movement_id BIGINT NOT NULL UNIQUE REFERENCES stock_movements(id),
    release_movement_id BIGINT UNIQUE REFERENCES stock_movements(id),
    held_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    released_at TIMESTAMP,
    PRIMARY KEY(operation_id,order_item_id),
    CONSTRAINT fk_reservation_candidate FOREIGN KEY(operation_id,order_item_id,product_id,quantity)
        REFERENCES order_change_items(operation_id,order_item_id,product_id,increase_quantity),
    CONSTRAINT ck_reservation_state CHECK (
        (state='HELD' AND consumed_at IS NULL AND released_at IS NULL AND release_movement_id IS NULL) OR
        (state='CONSUMED' AND consumed_at IS NOT NULL AND released_at IS NULL AND release_movement_id IS NULL) OR
        (state='RELEASED' AND consumed_at IS NULL AND released_at IS NOT NULL AND release_movement_id IS NOT NULL))
);
CREATE INDEX idx_inventory_reservations_product ON inventory_reservations(product_id) WHERE state='HELD';
-- Once cancellation has been decided, its decision cannot be erased to permit an abort.
CREATE FUNCTION protect_order_change_decision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.switch_decided_at IS NOT NULL AND NEW.switch_decided_at IS DISTINCT FROM OLD.switch_decided_at THEN
        RAISE EXCEPTION 'Order change switch decision is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tr_order_change_decision BEFORE UPDATE ON order_change_operations
    FOR EACH ROW EXECUTE FUNCTION protect_order_change_decision();
