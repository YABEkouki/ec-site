CREATE TABLE
    payment_discrepancies (
        id BIGSERIAL PRIMARY KEY,
        payment_id BIGINT NOT NULL,
        local_status VARCHAR(50) NOT NULL,
        provider_status VARCHAR(50) NOT NULL,
        status VARCHAR(20) NOT NULL,
        first_detected_at TIMESTAMP NOT NULL,
        last_detected_at TIMESTAMP NOT NULL,
        resolved_at TIMESTAMP,
        detection_count INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMP NOT NULL,
        updated_at TIMESTAMP NOT NULL,
        CONSTRAINT fk_payment_discrepancies_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
        CONSTRAINT chk_payment_discrepancies_status CHECK (status IN ('OPEN', 'RESOLVED')),
        CONSTRAINT chk_payment_discrepancies_detection_count CHECK (detection_count > 0)
    );

CREATE INDEX idx_payment_discrepancies_payment_id ON payment_discrepancies (payment_id);

CREATE INDEX idx_payment_discrepancies_status ON payment_discrepancies (status);

CREATE UNIQUE INDEX uq_payment_discrepancies_open ON payment_discrepancies (payment_id, local_status, provider_status)
WHERE
    status = 'OPEN';
