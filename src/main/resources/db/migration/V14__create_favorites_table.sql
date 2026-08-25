CREATE TABLE favorites (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_favorites_user
        FOREIGN KEY (user_id)
        REFERENCES users(id),

    CONSTRAINT fk_favorites_product
        FOREIGN KEY (product_id)
        REFERENCES products(id),

    CONSTRAINT uq_favorites_user_product
        UNIQUE (user_id, product_id)
);