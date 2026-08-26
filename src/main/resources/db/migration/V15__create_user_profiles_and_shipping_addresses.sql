CREATE TABLE user_profiles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    postal_code VARCHAR(8) NOT NULL,
    prefecture VARCHAR(20) NOT NULL,
    city VARCHAR(100) NOT NULL,
    address_line VARCHAR(200) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_user_profiles_user
        FOREIGN KEY (user_id)
        REFERENCES users(id),

    CONSTRAINT uq_user_profiles_user
        UNIQUE (user_id)
);

CREATE TABLE shipping_addresses (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    postal_code VARCHAR(8) NOT NULL,
    prefecture VARCHAR(20) NOT NULL,
    city VARCHAR(100) NOT NULL,
    address_line VARCHAR(200) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_shipping_addresses_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
);

CREATE UNIQUE INDEX uq_shipping_addresses_default
    ON shipping_addresses(user_id)
    WHERE is_default = TRUE;