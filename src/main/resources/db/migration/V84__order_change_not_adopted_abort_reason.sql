-- Feature129-A: a SYSTEM abort intent recorded when a confirmed NOT_ADOPTED order change
-- later succeeds at PAY.JP. Same intent shape as V67 (the latest prior definition); only one
-- new reason code is allowed.
ALTER TABLE order_change_operations DROP CONSTRAINT ck_change_abort_intent;
ALTER TABLE order_change_operations ADD CONSTRAINT ck_change_abort_intent CHECK (
    (abort_requested_at IS NULL AND abort_request_id IS NULL AND abort_requester_type IS NULL
        AND abort_reason_code IS NULL AND abort_expected_version IS NULL)
    OR (abort_requested_at IS NOT NULL AND abort_request_id IS NOT NULL
        AND abort_requester_type IS NOT NULL AND abort_requester_type IN ('CUSTOMER','ADMINISTRATOR','SYSTEM')
        AND abort_reason_code IS NOT NULL AND abort_reason_code IN
            ('CUSTOMER_REQUEST','BROWSER_INTERRUPTED','AUTHENTICATION_INTERRUPTED','COMMUNICATION_FAILURE','PAYMENT_FAILURE','CHANGE_NOT_ADOPTED')
        AND abort_expected_version IS NOT NULL AND abort_expected_version>=0 AND switch_decided_at IS NULL
        AND content_change_history_id IS NULL
        AND state IN ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED','MANUAL_REVIEW','ABORTING','ABORT_UNKNOWN','ABORTED'))
);
