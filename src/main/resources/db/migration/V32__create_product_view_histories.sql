CREATE TABLE
    product_view_histories (
        id BIGSERIAL PRIMARY KEY,
        user_id BIGINT NOT NULL,
        product_id BIGINT NOT NULL,
        last_viewed_at TIMESTAMP NOT NULL,
        CONSTRAINT fk_product_view_histories_user FOREIGN KEY (user_id) REFERENCES users (id),
        CONSTRAINT fk_product_view_histories_product FOREIGN KEY (product_id) REFERENCES products (id),
        CONSTRAINT uq_product_view_histories_user_product UNIQUE (user_id, product_id)
    );

CREATE INDEX idx_product_view_histories_user_last_viewed_at ON product_view_histories (user_id, last_viewed_at DESC);