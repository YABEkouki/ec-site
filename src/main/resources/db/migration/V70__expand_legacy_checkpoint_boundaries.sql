-- V69 remains immutable. Locate exactly its generated, unnamed origin-boundary CHECK.
DO $$
DECLARE names TEXT[];
BEGIN
    SELECT array_agg(conname) INTO names FROM pg_constraint
    WHERE conrelid='order_payment_evidence_epochs'::regclass AND contype='c'
      AND pg_get_constraintdef(oid) LIKE '%LEGACY_CURRENT_CHECKPOINT%'
      AND pg_get_constraintdef(oid) LIKE '%start_content_revision > authorization_revision%'
      AND pg_get_constraintdef(oid) LIKE '%start_total_amount < authorized_amount%';
    IF coalesce(cardinality(names),0)<>1 THEN
        RAISE EXCEPTION 'Expected exactly the V69 checkpoint boundary constraint';
    END IF;
    EXECUTE format('ALTER TABLE order_payment_evidence_epochs DROP CONSTRAINT %I',names[1]);
END;
$$;
ALTER TABLE order_payment_evidence_epochs ADD CONSTRAINT ck_f125_epoch_origin_boundaries CHECK (
    (origin_kind IN ('INITIAL_AUTH','REAUTH_COMPLETED') AND start_content_revision=authorization_revision AND start_total_amount=authorized_amount)
    OR (origin_kind='LEGACY_CURRENT_CHECKPOINT' AND start_content_revision>=authorization_revision AND start_total_amount<=authorized_amount)
);
