#!/usr/bin/env bash
set -euo pipefail
umask 077
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
command -v docker >/dev/null || { echo 'Docker is required; tests were not started.' >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo 'Docker is unavailable; tests were not started.' >&2; exit 2; }
# No secrets or database URLs may be supplied through command-line overrides.
for argument in "$@"; do
    case "$argument" in
        -Dspring.datasource*|-Dspring.flyway*|-Dspring.application.json*|-Dspring.config*|-Dspring.profiles*|-DargLine*)
            echo 'Unsafe test configuration override rejected.' >&2; exit 2 ;;
    esac
 done
scratch_dir="$(mktemp -d /tmp/ecsite-isolated-tests.XXXXXX)"
launcher_pid=''
cleanup() {
    if [[ -n "$launcher_pid" ]]; then
        kill -TERM "$launcher_pid" 2>/dev/null || true
        wait "$launcher_pid" 2>/dev/null || true
    fi
    rm -rf -- "$scratch_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Compile only: this phase never starts Spring or runs a test.
if ! ./mvnw -q -DskipTests test-compile dependency:build-classpath -DincludeScope=test -Dmdep.outputFile="$scratch_dir/classpath" > "$scratch_dir/compile.log" 2>&1; then
    echo 'Test launcher compilation failed; tests were not started.' >&2; exit 2
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
classpath="target/test-classes:target/classes:$(cat "$scratch_dir/classpath")"
"$java_bin" -cp "$classpath" com.example.ecsite.support.isolation.IsolatedTestLauncher \
    ./mvnw -q -DargLine=-XX:+UseSerialGC -DforkCount=1 -DreuseForks=true \
    -Djunit.jupiter.execution.parallel.enabled=false "$@" test &
launcher_pid=$!
set +e
wait "$launcher_pid"
result=$?
set -e
launcher_pid=''
exit "$result"
