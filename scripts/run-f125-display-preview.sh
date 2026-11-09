#!/usr/bin/env bash
# Display-only MVC fixtures. Never builds the working repository or loads .env.
set -euo pipefail
umask 077
mode="${1:---check}"
[[ $# -le 1 && ( "$mode" == --check || "$mode" == --serve ) ]] || { echo 'Use --check or --serve.' >&2; exit 2; }
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch_dir="$(mktemp -d /tmp/f125-display-preview.XXXXXX)"
child=''
cleanup() {
    if [[ -n "$child" ]]; then kill -TERM "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; fi
    rm -rf -- "$scratch_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$scratch_dir/project"
for directory in src scripts .mvn; do cp -a -- "$project_dir/$directory" "$scratch_dir/project/"; done
for file in pom.xml mvnw; do cp -a -- "$project_dir/$file" "$scratch_dir/project/"; done
cd "$scratch_dir/project"
# Remove inherited application/DB/provider/JVM configuration without printing any values.
for key in $(compgen -e); do
    case "$key" in SPRING_*|PAYJP_*|PAYMENT_*|EC_TEST_*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|MAVEN_OPTS|MAVEN_ARGS) unset "$key";; esac
done
if ! ./mvnw -q -Dtest=Feature125DisplayRenderingTest,DisplayHttpServerTest \
    -Df125.display.output="$scratch_dir/pages" test dependency:build-classpath \
    -DincludeScope=test -Dmdep.outputFile="$scratch_dir/classpath" > "$scratch_dir/render.log" 2>&1; then
    echo 'Display fixture preparation failed; no development DB fallback.' >&2
    # Logs are intentionally not dumped because rendering failures can contain synthetic form data.
    exit 2
fi
if [[ "$mode" == --check ]]; then echo 'Display fixtures and server safety checks passed in an independent copy.'; exit 0; fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$java_bin" -cp "target/test-classes:target/classes:$(cat "$scratch_dir/classpath")" \
    com.example.ecsite.support.display.DisplayBundle "$scratch_dir/pages/pages.json" &
child=$!
set +e
wait "$child"
result=$?
set -e
child=''
exit "$result"
