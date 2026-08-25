CREATE TABLE reviews (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating INTEGER NOT NULL,
    comment TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_reviews_product
        FOREIGN KEY (product_id)
        REFERENCES products(id),

    CONSTRAINT fk_reviews_user
        FOREIGN KEY (user_id)
        REFERENCES users(id),

    CONSTRAINT chk_reviews_rating
        CHECK (rating BETWEEN 1 AND 5),

    CONSTRAINT uq_reviews_product_user
        UNIQUE (product_id, user_id)
);