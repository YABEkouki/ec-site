#!/usr/bin/env bash
set -euo pipefail
umask 077
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Accept class names, never arbitrary Maven properties/goals. Validate before any build.
class_count=0
containers=false
reset=false
for argument in "$@"; do
    case "$argument" in
        --testcontainers) containers=true ;;
        --reset-test-db) reset=true ;;
        *)
            if [[ ! "$argument" =~ ^com\.example\.ecsite(\.[A-Za-z_\$][A-Za-z0-9_\$]*)+Test$ ]]; then
                echo 'Use: run-isolated-tests.sh [--testcontainers | --reset-test-db] fully.qualified.TestClass ...' >&2; exit 2
            fi
            class_count=$((class_count + 1)) ;;
    esac
done
if [[ "$class_count" == 0 || ( "$containers" == true && "$reset" == true ) ]]; then
    echo 'Explicit test classes and compatible options are required.' >&2; exit 2
fi
# Do not source .env, shell startup overrides or application/provider credentials.
for environment_name in $(compgen -e); do
    case "$environment_name" in
        PATH|JAVA_HOME|HOME|USER|LANG|LC_ALL|EC_TEST_DB_URL|EC_TEST_DB_USER|EC_TEST_DB_PASSWORD) ;;
        *) unset "$environment_name" ;;
    esac
done
export MAVEN_SKIP_RC=true MAVEN_OPTS=-XX:+UseSerialGC
if [[ "$containers" == true ]]; then
    command -v docker >/dev/null || { echo 'Docker is required for container tests.' >&2; exit 2; }
else
    if [[ "${EC_TEST_DB_URL:-}" != jdbc:postgresql://127.0.0.1:5432/ecsite_test ||
          "${EC_TEST_DB_USER:-}" != ecuser || -z "${EC_TEST_DB_PASSWORD:-}" || "${EC_TEST_DB_PASSWORD}" =~ ^[[:space:]]*$ ]]; then
        echo 'Explicit ecsite_test connection facts are required; nothing was started.' >&2; exit 2
    fi
fi
scratch_dir="$(mktemp -d /tmp/ecsite-tests.XXXXXX)"
launcher_pid=''
cleanup() {
    if [[ -n "$launcher_pid" ]]; then
        kill -TERM "$launcher_pid" 2>/dev/null || true
        wait "$launcher_pid" 2>/dev/null || true
    fi
    # Keep isolated reports/logs for review; never touch the original target.
    echo "Test copy and evidence retained: $scratch_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$scratch_dir/project"
# A fixed source allowlist excludes .env, .git, uploads, compose.yaml and the live target.
tar -C "$project_dir" -cf - src scripts .mvn pom.xml mvnw | tar -C "$scratch_dir/project" -xf -
if [[ -n "$(find "$scratch_dir/project" -type l -print -quit)" ]]; then
    echo 'Source symlinks are not supported by this test copy.' >&2; exit 2
fi
printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n' > "$scratch_dir/settings.xml"
cd "$scratch_dir/project"
if ! ./mvnw -o --batch-mode --no-transfer-progress -s "$scratch_dir/settings.xml" -gs "$scratch_dir/settings.xml" \
    -DskipTests test-compile dependency:build-classpath -DincludeScope=test \
    -Dmdep.outputFile="$scratch_dir/classpath" > "$scratch_dir/compile.log" 2>&1; then
    echo 'Compilation failed; tests and database initialization were not started.' >&2; exit 2
fi
export EC_TEST_ORIGINAL_PROJECT="$project_dir" EC_TEST_MAVEN_SETTINGS="$scratch_dir/settings.xml"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
classpath="target/test-classes:target/classes:$(cat "$scratch_dir/classpath")"
"$java_bin" -cp "$classpath" com.example.ecsite.support.isolation.IsolatedTestLauncher "$@" &
launcher_pid=$!
set +e
wait "$launcher_pid"
result=$?
set -e
launcher_pid=''
exit "$result"
