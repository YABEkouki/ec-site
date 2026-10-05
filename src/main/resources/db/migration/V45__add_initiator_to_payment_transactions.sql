ALTER TABLE payment_transactions
ADD COLUMN initiator_type VARCHAR(20),
ADD COLUMN initiator_id BIGINT,
ADD COLUMN initiator_username VARCHAR(255),
ADD COLUMN internal_note VARCHAR(1000);
