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

Before exporting credentials, verify the intended host/port/database/user and cluster identity in a READ ONLY connection. For isolated tests use the existing `IsolatedDatabaseGuard` (Docker provenance, non-5432 mapped port, dedicated DB) and compare cluster identity obtained inside the owned container before host JDBC. Never treat a different schema in `ecsite` as an isolated database. For development maintenance obtain separate approval, stop writers, and verify backup/restoration first.

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
