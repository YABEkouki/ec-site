ALTER TABLE users
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN email_verified_at TIMESTAMP;

CREATE UNIQUE INDEX uq_users_email
    ON users (email)
    WHERE email IS NOT NULL;
