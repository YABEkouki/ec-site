-- MANUAL ONLY. Not a Flyway migration or application startup resource.
-- Caller owns BEGIN and COMMIT/ROLLBACK. Read README.md before executing.
-- Requires a same-transaction ON COMMIT DROP gate; the .psql wrapper defaults to ROLLBACK.
DO $$
BEGIN
    IF to_regclass('pg_temp.order_payment_reset_gate') IS NULL THEN
        RAISE EXCEPTION 'Reset requires an explicit transaction gate';
    END IF;
    IF (SELECT count(*) FROM pg_temp.order_payment_reset_gate) <> 1 OR NOT EXISTS (
        SELECT 1 FROM pg_temp.order_payment_reset_gate
        WHERE expected_database = current_database()
          AND expected_user = current_user
          AND expected_server_addr IS NOT DISTINCT FROM inet_server_addr()
          AND expected_server_port IS NOT DISTINCT FROM inet_server_port()
          AND approval = 'APPROVE_ORDER_PAYMENT_RESET'
          AND backup_reference IS NOT NULL AND btrim(backup_reference) <> ''
    ) THEN
        RAISE EXCEPTION 'Reset target, approval or backup_reference is invalid';
    END IF;
    IF current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Reset requires a writable transaction';
    END IF;
END $$;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET CONSTRAINTS ALL DEFERRED;

-- Offline maintenance only. Fail immediately if another session still uses a table.
DO $$
DECLARE tables text;
BEGIN
    SELECT string_agg(format('public.%I', tablename), ', ' ORDER BY tablename)
      INTO tables FROM pg_tables WHERE schemaname = 'public';
    EXECUTE 'LOCK TABLE ' || tables || ' IN ACCESS EXCLUSIVE MODE NOWAIT';
END $$;

CREATE TEMP TABLE reset_targets(name text PRIMARY KEY, clear_all boolean NOT NULL) ON COMMIT DROP;
INSERT INTO reset_targets VALUES
    ('orders', true),
    ('order_items', true),
    ('order_charges', true),
    ('order_status_histories', true),
    ('order_handling_status_histories', true),
    ('order_assignee_histories', true),
    ('order_notes', true),
    ('order_shipping_address_histories', true),
    ('order_content_change_histories', true),
    ('order_content_change_items', true),
    ('order_content_change_charges', true),
    ('payments', true),
    ('payment_transactions', true),
    ('payment_discrepancies', true),
    ('payment_discrepancy_handling_status_histories', true),
    ('payment_discrepancy_audit_runs', true),
    ('payment_audit_notifications', true),
    ('payment_audit_notification_items', true),
    ('payment_audit_notification_attempts', true),
    ('payment_audit_notification_handlings', true),
    ('payment_audit_notification_handling_histories', true),
    ('payment_audit_notification_handling_history_unknowns', true),
    ('payment_audit_notification_manual_resends', true),
    ('payment_audit_notification_manual_resend_attempts', true),
    ('payment_audit_notification_handling_history_manual_unknowns', true),
    ('stock_movements', false),
    ('payment_audit_notification_states', false);

CREATE TEMP TABLE reset_expected_fks(child text, parent text, PRIMARY KEY(child,parent)) ON COMMIT DROP;
INSERT INTO reset_expected_fks VALUES
    ('orders', 'users'),
    ('orders', 'admin_accounts'),
    ('orders', 'payments'),
    ('order_items', 'orders'),
    ('order_items', 'products'),
    ('order_items', 'tax_categories'),
    ('order_charges', 'orders'),
    ('order_charges', 'tax_categories'),
    ('order_status_histories', 'orders'),
    ('order_handling_status_histories', 'orders'),
    ('order_assignee_histories', 'orders'),
    ('order_notes', 'orders'),
    ('order_shipping_address_histories', 'orders'),
    ('order_content_change_histories', 'orders'),
    ('order_content_change_items', 'order_content_change_histories'),
    ('order_content_change_charges', 'order_content_change_histories'),
    ('stock_movements', 'products'),
    ('stock_movements', 'orders'),
    ('payments', 'orders'),
    ('payment_transactions', 'payments'),
    ('payment_discrepancies', 'payments'),
    ('payment_discrepancy_handling_status_histories', 'payment_discrepancies'),
    ('payment_audit_notification_items', 'payment_audit_notifications'),
    ('payment_audit_notification_items', 'payment_audit_notification_states'),
    ('payment_audit_notification_attempts', 'payment_audit_notifications'),
    ('payment_audit_notification_handling_histories', 'payment_audit_notifications'),
    ('payment_audit_notification_handlings', 'payment_audit_notifications'),
    ('payment_audit_notification_handlings', 'payment_audit_notification_handling_histories'),
    ('payment_audit_notification_handling_history_unknowns', 'payment_audit_notification_handling_histories'),
    ('payment_audit_notification_handling_history_unknowns', 'payment_audit_notification_attempts'),
    ('payment_audit_notification_manual_resends', 'payment_audit_notifications'),
    ('payment_audit_notification_manual_resend_attempts', 'payment_audit_notification_manual_resends'),
    ('payment_audit_notification_handling_history_manual_unknowns', 'payment_audit_notification_handling_histories'),
    ('payment_audit_notification_handling_history_manual_unknowns', 'payment_audit_notification_manual_resend_attempts');

DO $$
BEGIN
    IF (SELECT max(installed_rank) FROM public.flyway_schema_history WHERE success) IS DISTINCT FROM
       (SELECT installed_rank FROM public.flyway_schema_history WHERE version = '53' AND success)
       OR EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'Expected successfully migrated V53 schema';
    END IF;
    IF EXISTS (
        WITH actual AS (
            SELECT c.relname::text AS child, p.relname::text AS parent, count(*) AS n,
                   bool_and(f.convalidated) AS validated
            FROM pg_constraint f
            JOIN pg_class c ON c.oid=f.conrelid JOIN pg_namespace cn ON cn.oid=c.relnamespace
            JOIN pg_class p ON p.oid=f.confrelid JOIN pg_namespace pn ON pn.oid=p.relnamespace
            WHERE f.contype='f' AND (
                (cn.nspname='public' AND c.relname IN (SELECT name FROM reset_targets)) OR
                (pn.nspname='public' AND p.relname IN (SELECT name FROM reset_targets)))
            GROUP BY c.relname,p.relname,cn.nspname,pn.nspname
            HAVING cn.nspname <> 'public' OR pn.nspname <> 'public' OR count(*) <> 1 OR NOT bool_and(f.convalidated)
            UNION ALL
            SELECT c.relname::text,p.relname::text,1,true
            FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid JOIN pg_namespace cn ON cn.oid=c.relnamespace
            JOIN pg_class p ON p.oid=f.confrelid JOIN pg_namespace pn ON pn.oid=p.relnamespace
            WHERE f.contype='f' AND cn.nspname='public' AND pn.nspname='public'
              AND (c.relname IN (SELECT name FROM reset_targets) OR p.relname IN (SELECT name FROM reset_targets))
              AND NOT EXISTS (SELECT 1 FROM reset_expected_fks e WHERE e.child=c.relname AND e.parent=p.relname)
        ) SELECT 1 FROM actual
    ) OR EXISTS (
        SELECT 1 FROM reset_expected_fks e WHERE NOT EXISTS (
            SELECT 1 FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid JOIN pg_namespace cn ON cn.oid=c.relnamespace
            JOIN pg_class p ON p.oid=f.confrelid JOIN pg_namespace pn ON pn.oid=p.relnamespace
            WHERE f.contype='f' AND cn.nspname='public' AND pn.nspname='public' AND c.relname=e.child AND p.relname=e.parent)
    ) THEN RAISE EXCEPTION 'Unexpected or missing foreign key dependency'; END IF;
    IF (SELECT count(*) FROM public.payment_audit_notification_states) <> 6 THEN
        RAISE EXCEPTION 'Expected six notification state rows';
    END IF;
    IF EXISTS (SELECT 1 FROM public.stock_movements WHERE
        (order_id IS NULL AND movement_type <> 'ADMIN_ADJUSTMENT') OR
        (order_id IS NOT NULL AND movement_type NOT IN ('ORDER_PLACEMENT','ORDER_CANCELLATION','ORDER_ITEM_CHANGE'))) THEN
        RAISE EXCEPTION 'Unexpected stock movement classification';
    END IF;
END $$;

-- Whole preserved tables, plus order-independent inventory movements, must be identical.
CREATE TEMP TABLE reset_preserved_before(name text PRIMARY KEY, fingerprint jsonb NOT NULL) ON COMMIT DROP;
DO $$
DECLARE t text; content jsonb;
BEGIN
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='public'
             AND tablename NOT IN (SELECT name FROM reset_targets) ORDER BY tablename LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text), ''[]''::jsonb) FROM public.%I x',t) INTO content;
        INSERT INTO reset_preserved_before VALUES(t,content);
    END LOOP;
    SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb) INTO content
      FROM public.stock_movements x WHERE order_id IS NULL;
    INSERT INTO reset_preserved_before VALUES('stock_movements',content);
END $$;
CREATE TEMP TABLE reset_sequences_before ON COMMIT DROP AS
SELECT schemaname,sequencename,last_value FROM pg_sequences WHERE schemaname='public';

-- Explicit child-first deletion. No TRUNCATE, CASCADE, trigger disabling or identity reset.
DELETE FROM public.payment_audit_notification_handling_history_manual_unknowns;
DELETE FROM public.payment_audit_notification_handling_history_unknowns;
DELETE FROM public.payment_audit_notification_handlings;
DELETE FROM public.payment_audit_notification_handling_histories;
DELETE FROM public.payment_audit_notification_manual_resend_attempts;
DELETE FROM public.payment_audit_notification_manual_resends;
DELETE FROM public.payment_audit_notification_items;
DELETE FROM public.payment_audit_notification_attempts;
DELETE FROM public.payment_audit_notifications;
DELETE FROM public.payment_discrepancy_audit_runs;
UPDATE public.payment_audit_notification_states
SET active=false,episode_no=0,first_observed_at=NULL,last_evaluated_at=NULL,
    last_observed_at=NULL,resolved_at=NULL,version=version+1;
DELETE FROM public.payment_discrepancy_handling_status_histories;
DELETE FROM public.payment_discrepancies;
DELETE FROM public.payment_transactions;
UPDATE public.orders SET shipment_capture_payment_id=NULL WHERE shipment_capture_payment_id IS NOT NULL;
DELETE FROM public.payments;
DELETE FROM public.order_content_change_items;
DELETE FROM public.order_content_change_charges;
DELETE FROM public.order_content_change_histories;
DELETE FROM public.order_shipping_address_histories;
DELETE FROM public.order_notes;
DELETE FROM public.order_assignee_histories;
DELETE FROM public.order_handling_status_histories;
DELETE FROM public.order_status_histories;
DELETE FROM public.order_charges;
DELETE FROM public.order_items;
DELETE FROM public.stock_movements WHERE order_id IS NOT NULL;
DELETE FROM public.orders;
SET CONSTRAINTS ALL IMMEDIATE;

-- Pre-COMMIT assertions. Any mismatch raises an exception and the caller must ROLLBACK.
DO $$
DECLARE t record; n bigint; content jsonb;
BEGIN
    FOR t IN SELECT name FROM reset_targets WHERE clear_all LOOP
        EXECUTE format('SELECT count(*) FROM public.%I',t.name) INTO n;
        IF n <> 0 THEN RAISE EXCEPTION 'Reset rows remain in %',t.name; END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM public.stock_movements WHERE order_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Order stock movement rows remain';
    END IF;
    IF (SELECT count(*) FROM public.payment_audit_notification_states) <> 6 OR EXISTS (
        SELECT 1 FROM public.payment_audit_notification_states WHERE active OR episode_no<>0
        OR first_observed_at IS NOT NULL OR last_evaluated_at IS NOT NULL OR last_observed_at IS NOT NULL OR resolved_at IS NOT NULL
    ) THEN RAISE EXCEPTION 'Notification states were not reset'; END IF;
    FOR t IN SELECT * FROM reset_preserved_before LOOP
        IF t.name='stock_movements' THEN
            SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb) INTO content
            FROM public.stock_movements x WHERE order_id IS NULL;
        ELSE
            EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text), ''[]''::jsonb) FROM public.%I x',t.name) INTO content;
        END IF;
        IF content IS DISTINCT FROM t.fingerprint THEN RAISE EXCEPTION 'Preserved data changed: %',t.name; END IF;
    END LOOP;
    IF EXISTS (
        (SELECT * FROM reset_sequences_before EXCEPT SELECT schemaname,sequencename,last_value FROM pg_sequences WHERE schemaname='public')
        UNION ALL
        (SELECT schemaname,sequencename,last_value FROM pg_sequences WHERE schemaname='public' EXCEPT SELECT * FROM reset_sequences_before)
    ) THEN RAISE EXCEPTION 'Sequences changed'; END IF;
END $$;
