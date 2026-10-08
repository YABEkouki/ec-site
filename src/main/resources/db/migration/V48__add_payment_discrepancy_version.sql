ALTER TABLE payment_discrepancies
ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
