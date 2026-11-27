package com.example.ecsite.service.operations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature145-B: read-only view of the scheduled processes the production runbook enables one by one
 * (docs/operations/production-enabling.md). Never shows keys, passwords or mail addresses: recipients are a count.
 */
@Service
public class OperationsStatusService {
    private static final Logger log = LoggerFactory.getLogger(OperationsStatusService.class);

    public record Setting(String label, String value) {}
    public record Count(String label, long count) {}
    /** One scheduled process. {@code lastActivity} is Asia/Tokyo local time, null when nothing is recorded. */
    public record Process(int order, String name, String variable, boolean enabled, boolean externalEffect,
            String effect, List<Setting> settings, String lastActivityLabel, LocalDateTime lastActivity,
            List<Count> backlog, String screenUrl) {}
    /** Feature125 runtime as {@code f125_runtime_status()} reports it, or {@code readable=false}. */
    public record RuntimeStatus(boolean readable, String storedState, boolean available, Long version, String generation,
            long openAdmissions, long unresolvedDispatches, long activeLeases) {}
    public record Connection(String mode, String apiOrigin, boolean live) {}
    public record Status(List<Process> enabling, List<Process> alwaysOn, RuntimeStatus runtime, Connection connection,
            List<String> warnings, LocalDateTime readAt) {}

    private final JdbcTemplate jdbc;
    private final Environment env;

    public OperationsStatusService(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.env = env;
    }

    @Transactional(readOnly = true)
    public Status read() {
        var enabling = List.of(auditNotification(), customerNotification(), expiryRefresh(), unpaidClosure(),
                changeClosure(), warehouseExport(), warehouseFile(), shipmentImport());
        var alwaysOn = List.of(reconciliation(), discrepancyAudit());
        var runtime = runtime();
        var connection = connection();
        return new Status(enabling, alwaysOn, runtime, connection, warnings(enabling, runtime, connection),
                jdbc.queryForObject("select timezone('Asia/Tokyo', clock_timestamp())", LocalDateTime.class));
    }

    private Process auditNotification() {
        boolean on = flag("app.payment.discrepancy-audit.notification.enabled", false);
        return new Process(1, "決済照合の通知メール（運用担当あて）", "PAYMENT_AUDIT_NOTIFICATION_ENABLED", on, true, "メール",
                List.of(new Setting("宛先", recipients() + "件"),
                        setting("間隔", "app.payment.discrepancy-audit.notification.fixed-delay", "1m"),
                        setting("再試行の間隔", "app.payment.discrepancy-audit.notification.retry-delay", "5m"),
                        setting("試行の上限", "app.payment.discrepancy-audit.notification.max-attempts", "3")),
                "最後の送信", time("select max(timezone('Asia/Tokyo', sent_at)) from payment_audit_notifications"),
                List.of(count("送信待ち・送信中", "select count(*) from payment_audit_notifications where status in ('PENDING','CLAIMED','SENDING','RETRY_WAIT')"),
                        count("送信できず（上限）", "select count(*) from payment_audit_notifications where status='EXHAUSTED'")),
                "/admin/payment-audit-notifications");
    }

    private Process customerNotification() {
        boolean on = flag("app.customer-notification.enabled", false);
        return new Process(2, "お客様への注文メール", "CUSTOMER_NOTIFICATION_ENABLED", on, true, "メール",
                List.of(setting("間隔", "app.customer-notification.fixed-delay", "30s"),
                        setting("送る期限（作成から）", "app.customer-notification.max-age", "24h")),
                "最後の送信", time("select max(timezone('Asia/Tokyo', sent_at)) from customer_order_notifications"),
                List.of(count("送信待ち・送信中", "select count(*) from customer_order_notifications where status in ('PENDING','CLAIMED','SENDING','RETRY_WAIT')"),
                        count("送信できず（上限）", "select count(*) from customer_order_notifications where status='EXHAUSTED'")),
                "/admin/customer-notifications");
    }

    private Process expiryRefresh() {
        boolean on = flag("app.payment.authorization-expiry-refresh.enabled", false);
        return new Process(3, "与信期限の更新", "AUTHORIZATION_EXPIRY_REFRESH_ENABLED", on, true, "PAY.JP GET",
                List.of(setting("間隔", "app.payment.authorization-expiry-refresh.fixed-delay", "10m"),
                        setting("1回の件数", "app.payment.authorization-expiry-refresh.batch-size", "20")),
                "最後の更新", time("select max(provider_recorded_at) from payment_authorization_expiries"),
                List.of(count("推定値のままの与信（与信済みの決済）", """
                        select count(*) from payment_authorization_expiries x join payments p on p.id=x.payment_id
                        where x.source='ESTIMATED' and p.status='AUTHORIZED'""")),
                "/admin/orders");
    }

    private Process unpaidClosure() {
        boolean on = flag("app.unpaid-order-closure.enabled", false);
        return new Process(4, "未払い注文の自動終了", "UNPAID_ORDER_CLOSURE_ENABLED", on, true, "PAY.JP POST（取消）",
                List.of(setting("終了までの時間", "app.unpaid-order-closure.after", "24h"),
                        setting("間隔", "app.unpaid-order-closure.fixed-delay", "5m")),
                "最後の自動終了の受付", time("select max(created_at) from unpaid_order_closures where requested_by_type='SYSTEM'"),
                List.of(count("受付済み・送信中", "select count(*) from unpaid_order_closures where status in ('REQUESTED','SENDING')")),
                "/admin/unpaid-orders");
    }

    private Process changeClosure() {
        boolean on = flag("app.order-change-closure.enabled", false);
        return new Process(5, "終わらなかった注文変更の自動終了", "ORDER_CHANGE_CLOSURE_ENABLED", on, true, "PAY.JP POST（取消）",
                List.of(setting("自動処理の期限からの待ち", "app.order-change-closure.after", "60m"),
                        setting("間隔", "app.order-change-closure.fixed-delay", "5m")),
                "最後の自動終了の受付", time("select max(created_at) from order_change_flow_closures where requested_by_type='SYSTEM'"),
                List.of(count("受付済み・送信中", "select count(*) from order_change_flow_closures where status in ('REQUESTED','SENDING')")),
                null);
    }

    private Process warehouseExport() {
        boolean on = flag("app.warehouse-export.enabled", false);
        String start = env.getProperty("app.warehouse-export.start-date", "");
        return new Process(6, "14:05 の倉庫向け出力と自動の出荷準備", "WAREHOUSE_EXPORT_ENABLED", on, true, "注文の内容が固まる",
                List.of(new Setting("開始日", start.isBlank() ? "未設定" : start),
                        setting("間隔", "app.warehouse-export.fixed-delay", "1m")),
                "最後のバッチの作成", time("select max(opened_at) from warehouse_export_batches"),
                List.of(count("作成中のバッチ", "select count(*) from warehouse_export_batches where state='OPEN'"),
                        count("最後のバッチの保留", """
                        select count(*) from warehouse_export_holds
                        where batch_id=(select max(id) from warehouse_export_batches)""")),
                "/admin/warehouse-exports");
    }

    private Process warehouseFile() {
        boolean on = flag("app.warehouse-file.enabled", false);
        return new Process(7, "倉庫ファイルの書き出し", "WAREHOUSE_FILE_ENABLED", on, true, "ファイル",
                List.of(new Setting("出力フォルダー", configured("app.warehouse-file.output-dir")),
                        new Setting("作業フォルダー", configured("app.warehouse-file.work-dir")),
                        setting("間隔", "app.warehouse-file.fixed-delay", "1m")),
                "最後の書き出し", time("select max(written_at) from warehouse_file_outputs"),
                List.of(count("書き出し待ち", "select count(*) from warehouse_file_outputs where state='PENDING'"),
                        count("書き出しの失敗", "select count(*) from warehouse_file_outputs where state='FAILED'")),
                "/admin/warehouse-exports");
    }

    private Process shipmentImport() {
        boolean on = flag("app.warehouse-shipment-import.enabled", false);
        return new Process(8, "出荷済み CSV の取り込みと出荷", "WAREHOUSE_SHIPMENT_IMPORT_ENABLED", on, true, "PAY.JP POST（売上確定）",
                List.of(setting("間隔", "app.warehouse-shipment-import.fixed-delay", "1m"),
                        setting("試行の上限", "app.warehouse-shipment-import.max-attempts", "3")),
                "最後の出荷処理", time("select max(processed_at) from warehouse_shipment_import_lines"),
                List.of(count("出荷待ち", "select count(*) from warehouse_shipment_import_lines where state='PENDING'"),
                        count("売上確定の確認中", "select count(*) from warehouse_shipment_import_lines where state='CAPTURE_UNCONFIRMED'")),
                "/admin/warehouse-shipments");
    }

    private Process reconciliation() {
        boolean on = flag("app.payment.reconciliation.enabled", false);
        return new Process(0, "決済の照合と注文変更の後片付け", "app.payment.reconciliation.enabled", on, true, "PAY.JP GET・POST",
                List.of(setting("間隔", "app.payment.reconciliation.fixed-delay", "1m")),
                "実行の記録", null, List.of(), null);
    }

    private Process discrepancyAudit() {
        boolean on = flag("app.payment.discrepancy-audit.enabled", false);
        return new Process(0, "決済照合の監査", "app.payment.discrepancy-audit.enabled", on, true, "PAY.JP GET",
                List.of(setting("間隔", "app.payment.discrepancy-audit.fixed-delay", "5m")),
                "最後の実行の開始", time("select max(timezone('Asia/Tokyo', started_at)) from payment_discrepancy_audit_runs"),
                List.of(count("直近24時間の失敗・一部失敗", """
                        select count(*) from payment_discrepancy_audit_runs
                        where status in ('FAILED','PARTIAL_FAILURE') and started_at>clock_timestamp()-interval '24 hours'""")),
                "/admin/payment-discrepancy-audits");
    }

    private RuntimeStatus runtime() {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    select s->>'storedState' stored, coalesce((s->>'available')::boolean,false) available,
                           (s->>'version')::bigint version, s->>'generation' generation,
                           coalesce((s->>'openAdmissions')::bigint,0) admissions,
                           coalesce((s->>'unresolvedDispatches')::bigint,0) dispatches,
                           coalesce((s->>'activeLeases')::bigint,0) leases
                    from (select f125_runtime_status() s) t""");
            return new RuntimeStatus(true, (String) row.get("stored"), Boolean.TRUE.equals(row.get("available")),
                    (Long) row.get("version"), (String) row.get("generation"), (Long) row.get("admissions"),
                    (Long) row.get("dispatches"), (Long) row.get("leases"));
        } catch (DataAccessException unreadable) {
            log.warn("Feature125 runtime status could not be read: {}", unreadable.getClass().getSimpleName());
            return new RuntimeStatus(false, null, false, null, null, 0, 0, 0);
        }
    }

    private Connection connection() {
        String mode = env.getProperty("payjp.capture-connection.mode", "");
        return new Connection(mode.isBlank() ? "未設定" : mode, env.getProperty("payjp.api-base-url", ""), "LIVE".equals(mode));
    }

    private static List<String> warnings(List<Process> p, RuntimeStatus runtime, Connection connection) {
        var warnings = new ArrayList<String>();
        if (!connection.live()) warnings.add("PAY.JP の売上確定の接続が LIVE ではありません（" + connection.mode() + "）。本番では LIVE にします。");
        if (!runtime.readable()) warnings.add("一括編集（Feature125）の runtime の状態を読み取れません。");
        else if (!runtime.available()) {
            warnings.add("一括編集（Feature125）の runtime が使えません（" + runtime.storedState() + "）。"
                    + (runtime.openAdmissions() > 0 ? "処理中の一括編集が " + runtime.openAdmissions() + " 件あります。" : "")
                    + "DB の再起動のあとは承認し直します。");
            if (p.get(4).enabled()) warnings.add("終わらなかった注文変更の自動終了は有効ですが、runtime が使えないため何もしません。");
        }
        backlogWhileOff(warnings, p.get(1), 0, "有効にすると、作成から送る期限内のものだけが送られ、古いものは期限切れになります。");
        backlogWhileOff(warnings, p.get(6), 0, "有効にすると古い順に書き出します。");
        backlogWhileOff(warnings, p.get(7), 0, "有効にするまで出荷と売上の確定は行われません。");
        if (p.get(6).enabled() && !p.get(5).enabled())
            warnings.add("倉庫ファイルの書き出しは有効ですが、14:05 の倉庫向け出力は無効です（緊急出荷の分だけを書き出します）。");
        if (p.get(7).enabled() && !p.get(6).enabled())
            warnings.add("出荷済み CSV の取り込みは有効ですが、倉庫ファイルの書き出しは無効です。");
        for (Process process : p)
            if (process.enabled()) for (Count c : process.backlog())
                if (c.label().contains("失敗") || c.label().contains("上限")) if (c.count() > 0)
                    warnings.add(process.name() + "：" + c.label() + "が " + c.count() + " 件あります。");
        return List.copyOf(warnings);
    }

    private static void backlogWhileOff(List<String> warnings, Process process, int index, String consequence) {
        if (process.enabled() || process.backlog().isEmpty()) return;
        long waiting = process.backlog().get(index).count();
        if (waiting > 0) warnings.add(process.name() + "は無効ですが、" + process.backlog().get(index).label() + "が " + waiting + " 件あります。" + consequence);
    }

    private boolean flag(String key, boolean fallback) {
        return env.getProperty(key, Boolean.class, fallback);
    }

    private Setting setting(String label, String key, String fallback) {
        return new Setting(label, env.getProperty(key, fallback));
    }

    /** Only whether a folder is configured: paths can reveal the host layout. */
    private String configured(String key) {
        return env.getProperty(key, "").isBlank() ? "未設定" : "設定あり";
    }

    private int recipients() {
        return (int) Arrays.stream(env.getProperty("app.payment.discrepancy-audit.notification.recipients", "").split(","))
                .filter(s -> !s.isBlank()).count();
    }

    private LocalDateTime time(String sql) {
        return jdbc.queryForObject(sql, LocalDateTime.class);
    }

    private Count count(String label, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return new Count(label, n == null ? 0 : n);
    }
}
