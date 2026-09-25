CREATE TABLE
    user_enabled_histories (
        id BIGSERIAL PRIMARY KEY,
        user_id BIGINT NOT NULL,
        from_enabled BOOLEAN NOT NULL,
        to_enabled BOOLEAN NOT NULL,
        changed_by_account_id BIGINT NOT NULL,
        changed_by_username VARCHAR(100) NOT NULL,
        changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_user_enabled_histories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    );

CREATE INDEX idx_user_enabled_histories_user_changed_at ON user_enabled_histories (user_id, changed_at);