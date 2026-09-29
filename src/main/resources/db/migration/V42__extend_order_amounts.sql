ALTER TABLE orders
ADD COLUMN item_subtotal INTEGER NOT NULL DEFAULT 0,
ADD COLUMN charge_total INTEGER NOT NULL DEFAULT 0,
ADD COLUMN tax_amount INTEGER NOT NULL DEFAULT 0;

ALTER TABLE orders ADD CONSTRAINT chk_orders_item_subtotal CHECK (item_subtotal >= 0),
ADD CONSTRAINT chk_orders_charge_total CHECK (charge_total >= 0),
ADD CONSTRAINT chk_orders_tax_amount CHECK (tax_amount >= 0);