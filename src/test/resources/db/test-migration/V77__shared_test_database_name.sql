-- Test-only compatibility. Production migration files and checksums remain unchanged.
-- All PostgreSQL version, primary, commit timestamp and durability checks remain intact.
CREATE OR REPLACE FUNCTION f125_admission_test_environment() RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF (current_database()<>'ecsite_test' AND current_database() !~ '^isolated_f125_admission_[0-9a-f]{32}$')
        OR current_setting('server_version_num')::INTEGER NOT BETWEEN 170000 AND 179999
        OR pg_is_in_recovery() OR current_setting('track_commit_timestamp')<>'on'
        OR current_setting('fsync')<>'on' OR current_setting('synchronous_commit')<>'on' THEN
        RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='Only explicitly isolated PostgreSQL 17 test execution is implemented';
    END IF;
END;
$$;
