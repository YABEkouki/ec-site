#!/usr/bin/env bash
set -euo pipefail
umask 077
mode="${1:---check}"
[[ $# -le 1 && ( "$mode" == '--check' || "$mode" == '--serve' ) ]] || { echo 'Use --check or --serve.' >&2; exit 2; }
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
command -v docker >/dev/null || { echo 'Docker is required.' >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo 'Docker unavailable; browser environment was not started.' >&2; exit 2; }
scratch_dir="$(mktemp -d /tmp/ecsite-browser117-launch.XXXXXX)"
launcher_pid=''
cleanup() {
    if [[ -n "$launcher_pid" ]]; then kill -TERM "$launcher_pid" 2>/dev/null || true; wait "$launcher_pid" 2>/dev/null || true; fi
    rm -rf -- "$scratch_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Do not load .env or start-dev.sh; both DB credentials and HMAC key are ephemeral.
if ! ./mvnw -q -DskipTests test-compile dependency:build-classpath -DincludeScope=test -Dmdep.outputFile="$scratch_dir/classpath" > "$scratch_dir/compile.log" 2>&1; then
    echo 'Browser launcher compilation failed; environment was not started.' >&2; exit 2
fi
classpath="target/test-classes:target/classes:$(cat "$scratch_dir/classpath")"
mockito_agent=''
IFS=: read -ra jars <<< "$(cat "$scratch_dir/classpath")"
for jar in "${jars[@]}"; do [[ "$jar" == */mockito-core-*.jar ]] && mockito_agent="$jar"; done
[[ -n "$mockito_agent" ]] || { echo 'Required outbound-operation blocker unavailable.' >&2; exit 2; }
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$java_bin" -XX:+UseSerialGC -javaagent:"$mockito_agent" -cp "$classpath" \
    com.example.ecsite.support.browser.IsolatedBrowserLauncher "$mode" &
launcher_pid=$!
set +e
wait "$launcher_pid"
result=$?
set -e
launcher_pid=''
exit "$result"
