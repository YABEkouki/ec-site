ALTER TABLE order_items
ADD COLUMN tax_category_id BIGINT NOT NULL,
ADD COLUMN tax_category_code VARCHAR(30) NOT NULL,
ADD COLUMN tax_category_name VARCHAR(100) NOT NULL,
ADD COLUMN tax_rate NUMERIC(5, 2) NOT NULL;

ALTER TABLE order_items ADD CONSTRAINT fk_order_items_tax_category FOREIGN KEY (tax_category_id) REFERENCES tax_categories (id);

CREATE INDEX idx_order_items_tax_category_id ON order_items (tax_category_id);