ALTER TABLE products
    ADD CONSTRAINT chk_products_price
        CHECK (price >= 0),

    ADD CONSTRAINT chk_products_stock
        CHECK (stock >= 0);


ALTER TABLE orders
    ADD CONSTRAINT chk_orders_total_amount
        CHECK (total_amount >= 0),

    ADD CONSTRAINT chk_orders_status
        CHECK (
            status IN (
                'ORDERED',
                'PAID',
                'SHIPPED',
                'CANCELLED'
            )
        );