#!/usr/bin/env bash
# Explicit customer/admin actions can call PAY.JP TEST; this script disables background work only.
# .env is trusted, owner-only shell input, exactly as with start-dev.sh.
set +x
set +v
set -euo pipefail
umask 077
fail() { echo "HOLD: $1" >&2; exit 1; }
[[ $# -eq 0 ]] || fail 'Additional arguments are not accepted.'
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
ENV_FILE="${SCRIPT_DIR}/.env"
readonly ENV_FILE
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail 'A regular .env file is required.'
[[ "$(stat -c %u -- "$ENV_FILE")" == "$(id -u)" && "$(stat -c %a -- "$ENV_FILE")" == 600 ]] || fail '.env must be owned by the current user with mode 600.'
# Suppress shell tracing and diagnostics while sourcing; never print environment values.
load_env() {
    set -a
    if source "$ENV_FILE"; then
        set +a
        set +x
        set +v
        return 0
    else
        set +a
        set +x
        set +v
        return 1
    fi
}
if ! load_env >/dev/null 2>&1; then
    fail 'Could not load .env.'
fi
unset DEBUG TRACE
# Reject competing launch/config injection rather than silently changing unrelated options.
for option in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS MAVEN_OPTS MAVEN_ARGS SPRING_APPLICATION_JSON SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_CONFIG_IMPORT SPRING_CONFIG_NAME SPRING_PROFILES_ACTIVE SPRING_PROFILES_INCLUDE LOGGING_CONFIG; do
    [[ -z "${!option:-}" ]] || fail 'Conflicting JVM/Maven/Spring override is set; use a clean terminal.'
done
for option in "${!LOGGING_LEVEL_@}"; do
    [[ -z "${!option:-}" ]] || fail 'Conflicting logger override is set; use a clean terminal.'
done
for option in "${!PAYJP_@}"; do
    case "$option" in
        PAYJP_PUBLIC_KEY|PAYJP_SECRET_KEY|PAYJP_WEBHOOK_TOKEN|PAYJP_CAPTURECONNECTION_CONNECTIONID|PAYJP_CAPTURECONNECTION_PROVIDERACCOUNTREF|PAYJP_CAPTURECONNECTION_MODE|PAYJP_CAPTURECONNECTION_APIORIGIN) ;;
        *) fail 'Unreviewed PAY.JP override is set.' ;;
    esac
done
[[ "${PAYJP_PUBLIC_KEY:-}" =~ ^pk_test_[a-zA-Z0-9_]+$ ]] || fail 'A TEST public key is required.'
[[ "${PAYJP_SECRET_KEY:-}" =~ ^sk_test_[a-zA-Z0-9_]+$ ]] || fail 'A TEST secret key is required; LIVE is forbidden.'
[[ -n "${PAYJP_WEBHOOK_TOKEN:-}" && ! "$PAYJP_WEBHOOK_TOKEN" =~ [[:space:]] ]] || fail 'A nonempty webhook token is required.'
for option in PAYJP_CAPTURECONNECTION_CONNECTIONID PAYJP_CAPTURECONNECTION_PROVIDERACCOUNTREF; do
    [[ -n "${!option:-}" && ! "${!option}" =~ [[:space:]] ]] || fail 'Capture Connection identity is missing or invalid.'
done
[[ "${PAYJP_CAPTURECONNECTION_MODE:-}" == TEST ]] || fail 'Capture Connection mode must be TEST.'
[[ "${PAYJP_CAPTURECONNECTION_APIORIGIN:-}" == https://api.pay.jp ]] || fail 'Capture Connection origin must be https://api.pay.jp.'
cd -- "$SCRIPT_DIR"
[[ ! -e .mvn/maven.config && ! -e .mvn/jvm.config ]] || fail 'Unreviewed Maven configuration file exists.'
[[ -x ./mvnw ]] || fail 'Maven wrapper is unavailable.'
# The proof below covers the reviewed five jobs. Source changes require a new review.
command -v python3 >/dev/null || fail 'python3 is required for the scheduler source guard.'
if ! python3 - <<'PY_SCHEDULERS'
from pathlib import Path
import hashlib,re,sys
expected = {'src/main/java/com/example/ecsite/service/payment/PaymentReconciliationScheduler.java': '6eb36f26a8aee32a0c4c301a35476db19b1410fda26ad76fcaa9e529fbd6fcd7', 'src/main/java/com/example/ecsite/service/orderchange/NewFlowClosureScheduler.java': '8fe398f42d1682284b41f18ed169a5059a7a72207273c869f8a6dd8b55ac2f84', 'src/main/java/com/example/ecsite/service/payment/PaymentDiscrepancyAuditScheduler.java': 'f7b1b97e29f28c45d42f1e3d05f3cc458a8500993a15c2ad2b163ac34438c6cb', 'src/main/java/com/example/ecsite/service/payment/PaymentAuditNotificationScheduler.java': '352ad50ff74a47b4418b6648804d2358d24befdae05add5978f573332a682ffe', 'src/main/java/com/example/ecsite/service/payment/PaymentAuditNotificationManualResendScheduler.java': 'fef27abeeae1ede240e2d4151004c67a813a5cbe78ccbacf3642e0676d0b123c'}
found = set()
for path in Path('src/main/java').rglob('*.java'):
    body = path.read_text()
    if re.search(r'@Scheduled\b|\.scheduleWithFixedDelay\(', body):
        found.add(str(path))
if found != set(expected) or any(hashlib.sha256(Path(p).read_bytes()).hexdigest() != digest for p,digest in expected.items()):
    sys.exit(1)
PY_SCHEDULERS
then
    fail 'Scheduler sources changed or an unreviewed scheduled job exists; review is required.'
fi
command -v ss >/dev/null || fail 'ss is required for the port check.'
if ! listeners="$(ss -H -ltn 'sport = :8080' 2>/dev/null)"; then
    fail 'Could not inspect port 8080.'
fi
[[ -z "$listeners" ]] || fail 'Port 8080 is in use; no existing process was stopped.'
unset listeners
# Only public safety settings enter arguments. Credentials remain in the environment.
application_args=(
    --server.address=127.0.0.1
    --server.port=8080
    --app.base-url=http://localhost:8080
    --spring.flyway.enabled=false
    --spring.jpa.hibernate.ddl-auto=validate
    --spring.sql.init.mode=never
    --app.payment.reconciliation.enabled=false
    --app.payment.discrepancy-audit.enabled=false
    --app.payment.discrepancy-audit.notification.enabled=false
    --app.payment.discrepancy-audit.notification.manual-resend-scheduler-enabled=false
    --spring.jpa.show-sql=false
    --spring.mvc.log-request-details=false
    --server.tomcat.accesslog.enabled=false
    --debug=false
    --trace=false
    --logging.level.root=WARN
    --logging.level.org.springframework=WARN
    --logging.level.org.springframework.web=WARN
    --logging.level.org.springframework.web.servlet.DispatcherServlet=WARN
    --logging.level.org.springframework.security=WARN
    --logging.level.org.apache.catalina=WARN
    --logging.level.org.apache.coyote=WARN
    --logging.level.org.apache.tomcat=WARN
    --logging.level.org.hibernate=WARN
    --logging.level.org.hibernate.SQL=OFF
    --logging.level.org.hibernate.orm.jdbc.bind=OFF
    --logging.level.org.hibernate.orm.jdbc.extract=OFF
    --logging.level.org.springframework.jdbc=OFF
    --logging.level.com.example.ecsite.payment=WARN
    --logging.level.com.example.ecsite.service.payment=WARN
    --logging.level.com.example.ecsite.service.orderchange=WARN
    --payjp.api-base-url=https://api.pay.jp
    --payjp.capture-connection.mode=TEST
    --payjp.capture-connection.api-origin=https://api.pay.jp
)
jvm_args='-Djava.net.preferIPv4Stack=true -Dspring.devtools.restart.enabled=false'
printf -v app_args '%s ' "${application_args[@]}"
echo 'Acceptance launch: loopback only, TEST only, Flyway/init off, payment schedulers off.'
exec ./mvnw spring-boot:run "-Dspring-boot.run.arguments=${app_args% }" "-Dspring-boot.run.jvmArguments=$jvm_args"
