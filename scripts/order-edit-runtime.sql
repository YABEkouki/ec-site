-- psql -X -v ON_ERROR_STOP=1 -v action=status -v runtime_schema=public -f scripts/order-edit-runtime.sql
-- No default activation. Connection credentials come from the operator's normal secure connection mechanism.
\set ON_ERROR_STOP on
\if :{?runtime_schema}
\else
\set runtime_schema public
\endif
\if :{?action}
\else
\set action status
\endif
SET search_path TO pg_catalog, :"runtime_schema", pg_temp;
SELECT :'action'='status' AS do_status, :'action'='check' AS do_check,
 :'action'='new-generation' AS do_generation, :'action'='probe' AS do_probe,
 :'action'='enable' AS do_enable, :'action'='stop' AS do_stop \gset
\if :do_status
SELECT jsonb_pretty(f125_runtime_status());
\elif :do_check
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT jsonb_pretty(f125_runtime_check());
COMMIT;
\elif :do_generation
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT f125_runtime_new_generation(:'expected_version'::bigint,'OPERATIONAL');
COMMIT;
\elif :do_probe
-- This COMMIT must finish before running action=enable in a separate transaction.
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT f125_runtime_probe(:'expected_version'::bigint);
COMMIT;
\elif :do_enable
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT f125_runtime_enable(:'expected_version'::bigint,:'generation'::uuid,:'identity_digest',:'single_writer_confirmation');
COMMIT;
\elif :do_stop
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT f125_runtime_stop(:'expected_version'::bigint,:'reason');
COMMIT;
\else
\echo 'Unknown action. Use status/check/new-generation/probe/enable/stop.'
\quit 2
\endif
