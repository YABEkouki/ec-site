CREATE TABLE
    email_verification_tokens (
        id BIGSERIAL PRIMARY KEY,
        user_id BIGINT NOT NULL,
        token_hash VARCHAR(64) NOT NULL,
        email VARCHAR(254) NOT NULL,
        expires_at TIMESTAMP NOT NULL,
        used_at TIMESTAMP,
        created_at TIMESTAMP NOT NULL,
        CONSTRAINT fk_email_verification_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
        CONSTRAINT uq_email_verification_tokens_token_hash UNIQUE (token_hash)
    );

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens (user_id);
