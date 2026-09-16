CREATE TABLE product_search_keywords (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    keyword VARCHAR(50) NOT NULL,

    CONSTRAINT fk_product_search_keywords_product
        FOREIGN KEY (product_id)
        REFERENCES products(id),

    CONSTRAINT uk_product_search_keywords_product_keyword
        UNIQUE (product_id, keyword)
);

CREATE INDEX idx_product_search_keywords_product_id
    ON product_search_keywords(product_id);
