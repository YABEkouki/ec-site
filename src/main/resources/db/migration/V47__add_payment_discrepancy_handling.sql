ALTER TABLE payment_discrepancies
ADD COLUMN handling_status VARCHAR(30) NOT NULL DEFAULT 'UNCONFIRMED',
ADD COLUMN handling_status_updated_at TIMESTAMP;

ALTER TABLE payment_discrepancies ADD CONSTRAINT chk_payment_discrepancies_handling_status CHECK (
    handling_status IN (
        'UNCONFIRMED',
        'CONFIRMED',
        'IN_PROGRESS',
        'COMPLETED'
    )
);

CREATE INDEX idx_payment_discrepancies_handling_status ON payment_discrepancies (handling_status);

CREATE TABLE
    payment_discrepancy_handling_status_histories (
        id BIGSERIAL PRIMARY KEY,
        payment_discrepancy_id BIGINT NOT NULL,
        from_status VARCHAR(30) NOT NULL,
        to_status VARCHAR(30) NOT NULL,
        changed_by_account_id BIGINT NOT NULL,
        changed_by_username VARCHAR(100) NOT NULL,
        change_event_id UUID NOT NULL,
        changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_payment_discrepancy_handling_histories_discrepancy FOREIGN KEY (payment_discrepancy_id) REFERENCES payment_discrepancies (id) ON DELETE CASCADE,
        CONSTRAINT chk_payment_discrepancy_handling_history_from_status CHECK (
            from_status IN (
                'UNCONFIRMED',
                'CONFIRMED',
                'IN_PROGRESS',
                'COMPLETED'
            )
        ),
        CONSTRAINT chk_payment_discrepancy_handling_history_to_status CHECK (
            to_status IN (
                'UNCONFIRMED',
                'CONFIRMED',
                'IN_PROGRESS',
                'COMPLETED'
            )
        )
    );

CREATE INDEX idx_payment_discrepancy_handling_histories_discrepancy_changed_at ON payment_discrepancy_handling_status_histories (payment_discrepancy_id, changed_at);

CREATE INDEX idx_payment_discrepancy_handling_histories_change_event_id ON payment_discrepancy_handling_status_histories (change_event_id);
