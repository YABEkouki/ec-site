# Feature125 operational runtime

V76 never activates runtime. Do not use these commands on development/production without the explicit approval for that environment. They are management SQL for the existing trusted role, not a new security boundary or external fencing system. No app lifecycle callback enables runtime.

## Prerequisites

PostgreSQL 17 primary; track_commit_timestamp, fsync and synchronous_commit all on. The connection needs the existing business/function privileges and access to pg_control_system/pg_control_checkpoint. Manage settings and restart separately; never initialize the database or run Flyway repair. Stop runtime before applying V76, then run Flyway validate. Existing receipts/evidence/generations are not backfilled.

Verify the cluster/database/timeline, stop old workers and old writers, and establish a single writer through the deployment/DB operating procedure. A copied physical cluster can share local identifiers; SQL cannot prove that another writer does not exist. Confirmation text records the operator's attestation; it does not prove fencing.

## Commands

Use `psql -X -v ON_ERROR_STOP=1 -v runtime_schema=public -v action=... -f scripts/order-edit-runtime.sql` with the environment's secure connection settings. Never put passwords in command arguments or logs. Each invocation is standalone; do not invoke management functions after taking business locks.

1. `action=status`: read storedState and available separately, identity/digest, version, open receipt/dispatch and live lease counts. A stored RUNNING after DB restart can be unavailable.
2. First activation only: `action=new-generation -v expected_version=<status version>`. Keep the returned generation UUID. This refuses any unresolved work. For resume, reuse the original generation; do not create a replacement to evade its receipts.
3. `action=probe -v expected_version=<fresh version>`. DB records its actual full XID and instance identity and commits. Probe increments version. A rollback leaves no usable probe.
4. `action=check` in another Tx: DB checks pg_xact_status and pg_xact_commit_timestamp, full-XID epoch/distance and five-minute freshness. No caller timestamp is accepted.
5. Re-read status and inspect identity. `action=enable -v expected_version=<fresh version> -v generation=<UUID> -v identity_digest=<inspected digest> -v single_writer_confirmation='<operator/change reference and single-writer verification>'`. Only STOPPED can enable. Missing proof, changed identity, foreign-generation work, malformed bindings or live leases reject approval. Never blindly retry an ambiguous commit; read status first.
6. `action=stop -v expected_version=<fresh version> -v reason='<change reference>'`. Takes runtime exclusive lock and CAS. It works even when the old approved identity is invalid. Existing proofs, reservations, tokens and dispatches are not cleared. The reason should also be retained in the operator's change record.

## Restart, uncertain finance and rollback

App restart does not activate or create a new generation. DB restart invalidates approval because boot identity changed. Stop, verify same cluster/database/OID/timeline and single writer, obtain a new committed probe, then explicitly reapprove the same generation. Wait for live leases and ensure old workers are stopped. Resume uses existing expired-owner fencing and GET reconciliation; lease expiry does not prove unapplied work.

STOPPED denies new receipts, claims, POST eligibility, financial-result writes, order application and unapplied closure. Read-only results remain available. Webhook application failures must not become successful acknowledgements. There is no new durable webhook inbox: reconcile the saved operations after approval, not only webhook redelivery. Unknown CREATE without a Flow ID remains manual review; never invent a key or resend it.

The POST boundary is committed READY-to-STARTED. A permit committed before STOP can still reach the provider after STOP. DB locks are not held over HTTP. Preserve STARTED/UNKNOWN even when the response is lost; never return it to READY. Automatic windows, leases and token/version counters are not reset by pause/resume.

Timeline change, restore, DB recreation or a clone is not a normal restart. Do not rebind old receipts or mutate generations; stop and resolve the lineage/financial work explicitly. Long stops can exhaust the existing automatic window: do not extend it as a runtime workaround.

On defects use STOPPED, not migration rollback, evidence deletion or configuration bypass. ISOLATED_TEST retains its original DB-name/PG/settings/full-identity checks. Scope switching and new generations cannot abandon unresolved admissions, attempts or dispatches. The trusted table owner can still administer its tables; this feature does not replace role separation.

## Manual Flyway maintenance (separate approval required)

The `f125-db-maintenance` Maven profile pins Flyway/its PostgreSQL module to 12.4.0 and JDBC to 42.7.13. It has no activation or lifecycle executions. Normal builds and application startup do not activate this profile. Spring Boot's existing startup Flyway is separate: do not start the app to run maintenance.

Use `bash scripts/run-f125-flyway.sh` as the maintenance entry. It rejects missing/empty/blank `FLYWAY_URL`, `FLYWAY_USER`, or `FLYWAY_PASSWORD` before starting Maven. There is no development-database default. The raw Flyway goals do not provide this shell preflight (in particular a password can be optional for some authentication methods); do not bypass the entry. It pins filesystem migrations, public schema and target 76, forbids clean and accepts only info/validate/migrate. It never proves environment approval or isolation by itself.

Before exporting credentials, verify the intended host/port/database/user and cluster identity in a READ ONLY connection. For regression tests use the shared `ecsite_test` procedure below. The existing `IsolatedDatabaseGuard` keeps a separate Testcontainers path with Docker provenance checks; do not apply its non-5432/container requirements to the approved shared test database. Never treat a different schema in `ecsite` as an isolated database. For development maintenance obtain separate approval, stop writers, and verify backup/restoration first.

In a dedicated shell, clear stale `FLYWAY_*` settings, Maven/system-property overrides and custom Flyway configuration files before configuring the reviewed connection. Set URL/user explicitly and enter the password without putting it into command history:

```bash
export FLYWAY_URL='<verified JDBC PostgreSQL URL without credentials>'
export FLYWAY_USER='<verified database user>'
read -r -s -p 'DB password: ' FLYWAY_PASSWORD
export FLYWAY_PASSWORD
bash scripts/run-f125-flyway.sh info
```

Review info: for a V68 development baseline, only V69 through V76 may be pending. Then run these individually, checking each exit code/output before the next:

```bash
bash scripts/run-f125-flyway.sh validate --allow-pending
bash scripts/run-f125-flyway.sh migrate
bash scripts/run-f125-flyway.sh validate
bash scripts/run-f125-flyway.sh info
unset FLYWAY_URL FLYWAY_USER FLYWAY_PASSWORD
```

`--allow-pending` is only for pre-migration validation; it does not suppress checksum/missing/failed errors. Post-migration validation has no ignore patterns. Verify V76 success/zero failures, pre-maintenance business row counts, expected constraints/triggers/functions, and STOPPED runtime. V77 and later are not applied. Do not run help/effective-pom/debug/shell-trace commands with secrets loaded; do not save passwords or connection URLs with embedded credentials to files/logs. Unset variables on failure too.

STOP on unexpected identity, pending versions, checksum mismatch, missing credentials, failed backup or a nonzero goal result. Do not repair/clean/initialize/reverse DDL. Earlier successful migrations may already be committed; inspect history rather than assuming all eight roll back together. Migration does not enable runtime or authorize provider traffic.

Compose declares `track_commit_timestamp=on` for future containers. Editing Compose does not restart the running server. Recreating/restarting PostgreSQL is a separate approved operation and must preserve the existing named volume.


## Regression tests (test profile only)

Use Java 21 and the existing runner. Normal integration tests use only `127.0.0.1:5432/ecsite_test` as `ecuser`; missing connection facts, another database/user, or mismatched DataSource/Flyway settings are rejected. Never use a schema in development `ecsite` as a test target. Do not source the application's `.env` or pass real PAY.JP credentials. In a dedicated shell:

```bash
export EC_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/ecsite_test'
export EC_TEST_DB_USER='ecuser'
read -r -s -p 'Test DB password: ' EC_TEST_DB_PASSWORD
export EC_TEST_DB_PASSWORD
bash scripts/run-isolated-tests.sh --reset-test-db \
  com.example.ecsite.support.isolation.IsolatedTestSmokeTest
unset EC_TEST_DB_URL EC_TEST_DB_USER EC_TEST_DB_PASSWORD
```

Supply explicit fully qualified test class names. The runner makes an independent `/tmp/ecsite-tests.*` source copy, excludes `.env`, Compose and the original `target`, and builds/tests only in that copy. Maven is offline: dependencies and the wrapper distribution must already be cached. Reports and logs remain in the copy for review. Tests run one class at a time, with one fork and JUnit parallel execution disabled. Do not run concurrent runners or other consumers of `ecsite_test`.

`--reset-test-db` is destructive: before **each** class it checks the actual database/user and rejects other sessions, then drops every non-system schema in `ecsite_test` and recreates `public`. Each schema drop commits separately to avoid exhausting shared locks. Failure can leave a partially reset test database; stop, inspect the reported remaining schemas, and do not automatically repair or retry. The session check is not a lock against new clients: exclusive test DB use is an operator prerequisite. Omitting the flag preserves existing test data and is appropriate only when the selected test explicitly supports that state.

Normal tests use the shared server. Only the commit-tracking-off and actual database-restart cases in `OrderEditAdmissionFoundationPostgresTest` and `OrderEditOperationalRuntimePostgresTest` create disposable PostgreSQL 17 containers through existing fixtures. Use the default runner mode for these classes; the retained legacy `--testcontainers` option is not the normal regression procedure. Disposable containers are stopped by their test cleanup. The runner tracks its Maven process and observed descendants, with a 30-minute execution timeout per class. On interruption, exception or timeout it stops descendants first, waits for bounded grace periods, then forcibly terminates remaining owned processes and reports surviving PIDs. Short-process unit tests cover interruption, timeout, a SIGTERM-ignoring child and an unaffected unrelated process. SIGKILL or host failure cannot run shutdown hooks; after such termination, inspect Maven, test forks and owned containers before another reset or run.

Keep `track_commit_timestamp`, `fsync` and `synchronous_commit` **on** on the existing server. Never switch commit tracking off or restart the development PostgreSQL server for these tests: disabling tracking can destroy existing commit timestamp evidence, and a restart invalidates approved runtime boot identity. Disposable cases must not change development runtime/generation or its saved receipt evidence.

The test profile uses identical mandatory `EC_TEST_DB_*` values for DataSource and Flyway, JPA `validate`, four disabled payment/audit/notification scheduler flags, and exclusion of the generated-default-user auto-configuration. Existing test context guards reject real PAY.JP/mail operations; dummy provider/mail endpoints are not permission to contact external services. These controls are not a process-wide network sandbox.

`V77__shared_test_database_name.sql` exists only under `src/test/resources/db/test-migration` and is included only by the test profile. It permits the exact shared test DB name while retaining PostgreSQL 17, primary, commit timestamp and durability checks. Full-profile tests migrate through V77. Historical-schema tests retain their original V71–V76 Flyway targets/history and apply the same DB-name compatibility SQL explicitly as a test fixture. Production migrations and checksums are unchanged; the maintenance entry above still targets V76 and excludes test migrations. Never add the test migration location to application or production maintenance settings.

For display evidence and its limits, see [the existing display preview README](../../src/test/resources/display/README.md). JavaScript-disabled preview results do not certify JavaScript-enabled Chrome, runtime busy states, actual PAY.JP Widget appearance or real 3DS behavior.
