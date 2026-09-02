ALTER TABLE order_items
    ADD COLUMN category_id BIGINT,
    ADD COLUMN category_name VARCHAR(100);

UPDATE order_items oi
SET category_id = c.id,
    category_name = c.name
FROM products p
JOIN categories c ON c.id = p.category_id
WHERE oi.product_id = p.id;

ALTER TABLE order_items
    ALTER COLUMN category_id SET NOT NULL,
    ALTER COLUMN category_name SET NOT NULL;
