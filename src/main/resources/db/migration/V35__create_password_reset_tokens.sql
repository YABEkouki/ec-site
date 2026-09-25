CREATE TABLE
    password_reset_tokens (
        id BIGSERIAL PRIMARY KEY,
        user_id BIGINT NOT NULL,
        token_hash VARCHAR(64) NOT NULL,
        email VARCHAR(254) NOT NULL,
        expires_at TIMESTAMP NOT NULL,
        used_at TIMESTAMP,
        created_at TIMESTAMP NOT NULL,
        CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
        CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash)
    );

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);