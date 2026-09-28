ALTER TABLE orders
    ADD COLUMN change_deadline_at TIMESTAMP;

UPDATE orders
SET change_deadline_at =
    CASE
        WHEN ordered_at::time < TIME '14:00'
            THEN ordered_at::date + TIME '14:00'
        ELSE
            ordered_at::date + INTERVAL '1 day' + TIME '14:00'
    END;

ALTER TABLE orders
    ALTER COLUMN change_deadline_at SET NOT NULL;