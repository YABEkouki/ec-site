# Feature117 isolated browser validation

This is a test-classpath-only environment, not a development or production launcher.
It does not source `.env` or call `start-dev.sh`. Requires Docker and Java 21.

## Limited preparation check

```bash
./scripts/run-isolated-browser.sh --check
```

Creates a disposable PostgreSQL 17 with a unique `browser117_...` database and a
random published port. Validates Docker provenance and actual database name before
DataSource/Flyway initialization, applies V1–V51, validates Hibernate and prepares
24 synthetic notifications. Then closes the app, removes the container and deletes
private login information. It does not perform HTTP/browser requests or handling
state-change operations.

## Keep the environment for an authorized browser session

```bash
./scripts/run-isolated-browser.sh --serve
```

The app binds only to `127.0.0.1` on a random port. The console prints the path of
`/tmp/feature117-browser-.../access.txt`, not the password. That directory is mode
700 and the file is mode 600. Read it locally to obtain the URL and temporary login
credentials; do not copy it into Git or logs. The username and password are generated per launch. The account uses the
application's PasswordEncoder and is loaded
by the existing AdminUserDetailsService with ROLE_ADMIN. Migration-seeded admins
are disabled only in this private DB. Security/CSRF are not bypassed.

The HMAC key is random per launch and exists only in the launcher/application
memory. Restart creates a new DB, credentials and key. Previous tokens are invalid.

Notification, audit and reconciliation schedulers are OFF. Real PAY.JP and mail
beans are blocked using the test isolation customizer. Do not replace this command
with a normal app launch or enable schedulers. No PAY.JP/SMTP endpoints are needed.

## Fixtures

`BrowserNotificationFixtures` owns the initial synthetic inserts in one transaction.
There are two groups of 12 notifications (24 total; paging 20 + 4):

- SENT, delivery certain, outside handling scope
- EXHAUSTED, no UNKNOWN, no handling row
- SENT with UNKNOWN and delivery_uncertain
- CANCELLED with UNKNOWN and delivery_uncertain
- EXHAUSTED / IN_PROGRESS with a handling history
- EXHAUSTED / RESOLVED, UNKNOWN {1}, confirmed {1}
- SENT / RESOLVED, UNKNOWN {1,2}, confirmed {1}: requires recheck
- EXHAUSTED / explicit UNREVIEWED without history
- PENDING, CLAIMED, SENDING, RETRY_WAIT outside handling scope

All six warning types are represented. Some notifications have multiple items and
REMOVED_RESOLVED items. These are saved historical snapshots; INCLUDED is not a
statement about the current warning state. Shared state rows are never modified.
No observation, delivery or administrator update service is invoked by the fixture.

## Cleanup

Stop the foreground script with Ctrl-C. The app is closed, PostgreSQL is removed,
and private credentials are deleted. Do not delete individual fixture rows; the
entire dedicated DB is disposable. In case of SIGKILL or Docker failure, inspect
only containers labeled `ecsite.browser117=true` and the printed private directory.
Manually remove only the identified browser container/directory after verifying
ownership. Never use global Docker prune or delete the existing development DB.
