ALTER TABLE orders
ADD COLUMN shipping_name VARCHAR(100),
ADD COLUMN shipping_postal_code VARCHAR(8),
ADD COLUMN shipping_prefecture VARCHAR(20),
ADD COLUMN shipping_city VARCHAR(100),
ADD COLUMN shipping_address_line VARCHAR(200),
ADD COLUMN shipping_phone VARCHAR(20);