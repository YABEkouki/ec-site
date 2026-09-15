ALTER TABLE admin_accounts
    ADD COLUMN created_at TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP,
    ADD COLUMN previous_login_at TIMESTAMP,
    ADD COLUMN last_login_at TIMESTAMP;

UPDATE admin_accounts
SET created_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE admin_accounts
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;
