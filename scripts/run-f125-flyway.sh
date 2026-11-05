#!/usr/bin/env bash
# Explicit maintenance only. This command does not prove approval or database isolation.
set -euo pipefail
umask 077
case "${1:-}" in
    info|validate|migrate) action="$1" ;;
    *) echo 'Use: bash scripts/run-f125-flyway.sh info|validate|migrate [--allow-pending]' >&2; exit 2 ;;
esac
pending=''
if [[ "$#" == 2 && "$action" == validate && "$2" == --allow-pending ]]; then
    pending='versioned:pending'
elif [[ "$#" != 1 ]]; then
    echo 'Only validate accepts --allow-pending.' >&2; exit 2
fi
for name in FLYWAY_URL FLYWAY_USER FLYWAY_PASSWORD; do
    if [[ -z "${!name:-}" || "${!name}" =~ ^[[:space:]]*$ ]]; then
        echo 'Required connection environment is missing; Maven was not started.' >&2
        exit 2
    fi
done
if [[ "$FLYWAY_URL" != jdbc:postgresql://* ]]; then
    echo 'An explicit PostgreSQL JDBC URL is required; Maven was not started.' >&2; exit 2
fi
if [[ "$FLYWAY_URL" == *@* || "$FLYWAY_URL" == *\#* ||
      ( "$FLYWAY_URL" == *\?* && "${FLYWAY_URL#*\?}" != loggerLevel=OFF ) ]]; then
    echo 'Use a JDBC URL without embedded credentials or connection overrides.' >&2; exit 2
fi
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
# Command properties pin the reviewed destination/configuration above stale Flyway config files.
# Password is never put into a command argument. Do not run this script with shell tracing.
exec ./mvnw --batch-mode --no-transfer-progress -Pf125-db-maintenance \
    "-Dflyway.url=$FLYWAY_URL" "-Dflyway.user=$FLYWAY_USER" \
    "-Dflyway.locations=filesystem:$project_dir/src/main/resources/db/migration" \
    -Dflyway.defaultSchema=public -Dflyway.schemas=public -Dflyway.target=76 \
    -Dflyway.cleanDisabled=true -Dflyway.baselineOnMigrate=false \
    -Dflyway.outOfOrder=false -Dflyway.createSchemas=false \
    "-Dflyway.ignoreMigrationPatterns=$pending" \
    "org.flywaydb:flyway-maven-plugin:12.4.0:$action"
