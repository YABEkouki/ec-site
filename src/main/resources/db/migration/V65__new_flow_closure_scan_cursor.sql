-- Operational scan progress only. No financial requests or existing operation backfill.
CREATE TABLE order_change_closure_scan_cursor (
    id SMALLINT PRIMARY KEY CHECK (id=1),
    after_id UUID,
    upper_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (upper_id IS NOT NULL OR after_id IS NULL),
    CHECK (after_id IS NULL OR after_id<=upper_id)
);
INSERT INTO order_change_closure_scan_cursor(id) VALUES(1);
CREATE INDEX idx_change_closure_scan ON order_change_operations(id)
    WHERE abort_requested_at IS NOT NULL AND abort_flow_closed_at IS NULL
      AND state IN ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED','ABORTING','ABORT_UNKNOWN');
