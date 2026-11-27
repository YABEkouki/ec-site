package com.example.ecsite.service.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import com.example.ecsite.service.operations.OperationsStatusService.Process;

/** Feature145-B: settings, secrets and warnings, with every count chosen per query text. */
class OperationsStatusServiceTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 10, 0);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    MockEnvironment env = new MockEnvironment();
    Map<String, Long> counts = new HashMap<>();
    Map<String, Object> runtime = new HashMap<>();

    @BeforeEach void stubs() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            return counts.entrySet().stream().filter(e -> sql.contains(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(0L);
        });
        when(jdbc.queryForObject(anyString(), eq(LocalDateTime.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("clock_timestamp")) return NOW;
            return sql.contains("customer_order_notifications") ? NOW.minusMinutes(3) : null;
        });
        runtime.putAll(Map.of("stored", "RUNNING", "available", true, "version", 19L, "generation", "417c973d-a2f3-4fee-a866-58acac69efef",
                "admissions", 0L, "dispatches", 0L, "leases", 0L));
        when(jdbc.queryForMap(anyString())).thenAnswer(call -> runtime);
        env.setProperty("payjp.capture-connection.mode", "LIVE");
        env.setProperty("payjp.api-base-url", "https://api.pay.jp");
        env.setProperty("app.payment.reconciliation.enabled", "true");
        env.setProperty("app.payment.discrepancy-audit.enabled", "true");
    }

    OperationsStatusService service() { return new OperationsStatusService(jdbc, env); }
    static Process named(OperationsStatusService.Status s, int order) { return s.enabling().get(order - 1); }

    @Test void everythingOffShowsTheDefaultsWithoutWarnings() {
        var s = service().read();
        assertThat(s.enabling()).extracting(Process::order).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(s.enabling()).allSatisfy(p -> assertThat(p.enabled()).isFalse());
        assertThat(s.alwaysOn()).allSatisfy(p -> assertThat(p.enabled()).isTrue());
        assertThat(s.warnings()).isEmpty();
        assertThat(s.readAt()).isEqualTo(NOW);
        assertThat(named(s, 2).lastActivity()).isEqualTo(NOW.minusMinutes(3));
        assertThat(named(s, 1).lastActivity()).isNull();
        assertThat(named(s, 6).settings()).extracting(OperationsStatusService.Setting::value).contains("未設定", "1m");
        assertThat(named(s, 4).settings()).extracting(OperationsStatusService.Setting::value).contains("24h", "5m");
        assertThat(s.runtime().readable()).isTrue();
        assertThat(s.runtime().available()).isTrue();
        assertThat(s.connection().live()).isTrue();
    }

    @Test void neitherRecipientsNorFoldersAreShown() {
        env.setProperty("app.payment.discrepancy-audit.notification.enabled", "true");
        env.setProperty("app.payment.discrepancy-audit.notification.recipients", "ops@example.com, second@example.com");
        env.setProperty("app.warehouse-file.enabled", "true");
        env.setProperty("app.warehouse-file.output-dir", "/srv/secret-layout/out");
        env.setProperty("app.warehouse-file.work-dir", "");
        env.setProperty("app.warehouse-export.enabled", "true");
        env.setProperty("app.warehouse-export.start-date", "2026-12-01");
        env.setProperty("app.unpaid-order-closure.after", "36h");
        var s = service().read();
        assertThat(named(s, 1).enabled()).isTrue();
        assertThat(named(s, 1).settings()).extracting(OperationsStatusService.Setting::value).contains("2件");
        assertThat(named(s, 7).settings()).extracting(OperationsStatusService.Setting::value).contains("設定あり", "未設定");
        assertThat(named(s, 6).settings()).extracting(OperationsStatusService.Setting::value).contains("2026-12-01");
        assertThat(named(s, 4).settings()).extracting(OperationsStatusService.Setting::value).contains("36h");
        assertThat(s.toString()).doesNotContain("example.com").doesNotContain("secret-layout");
    }

    @Test void warnsAboutTestModeAndAStoppedRuntime() {
        env.setProperty("payjp.capture-connection.mode", "TEST");
        env.setProperty("app.order-change-closure.enabled", "true");
        runtime.put("stored", "STOPPED");
        runtime.put("available", false);
        runtime.put("admissions", 2L);
        var s = service().read();
        assertThat(s.connection().live()).isFalse();
        assertThat(s.warnings()).anySatisfy(w -> assertThat(w).contains("LIVE ではありません（TEST）"))
                .anySatisfy(w -> assertThat(w).contains("runtime が使えません（STOPPED）").contains("処理中の一括編集が 2 件"))
                .anySatisfy(w -> assertThat(w).contains("自動終了は有効ですが、runtime が使えない"));
    }

    @Test void anUnreadableRuntimeIsAWarningNotAFailure() {
        when(jdbc.queryForMap(anyString())).thenThrow(new DataAccessResourceFailureException("no pg_control"));
        var s = service().read();
        assertThat(s.runtime().readable()).isFalse();
        assertThat(s.warnings()).anySatisfy(w -> assertThat(w).contains("runtime の状態を読み取れません"));
    }

    @Test void warnsAboutWorkWaitingForADisabledProcess() {
        counts.put("from customer_order_notifications where status in", 4L);
        counts.put("from warehouse_file_outputs where state='PENDING'", 1L);
        counts.put("from warehouse_shipment_import_lines where state='PENDING'", 3L);
        var s = service().read();
        assertThat(s.warnings()).anySatisfy(w -> assertThat(w).startsWith("お客様への注文メールは無効ですが、送信待ち・送信中が 4 件"))
                .anySatisfy(w -> assertThat(w).startsWith("倉庫ファイルの書き出しは無効ですが、書き出し待ちが 1 件"))
                .anySatisfy(w -> assertThat(w).startsWith("出荷済み CSV の取り込みと出荷は無効ですが、出荷待ちが 3 件"));
    }

    @Test void warnsAboutFailuresOnlyForEnabledProcesses() {
        counts.put("from customer_order_notifications where status='EXHAUSTED'", 2L);
        counts.put("from warehouse_file_outputs where state='FAILED'", 5L);
        assertThat(service().read().warnings()).noneSatisfy(w -> assertThat(w).contains("上限")).noneSatisfy(w -> assertThat(w).contains("失敗"));
        env.setProperty("app.customer-notification.enabled", "true");
        env.setProperty("app.warehouse-file.enabled", "true");
        env.setProperty("app.warehouse-export.enabled", "true");
        assertThat(service().read().warnings())
                .anySatisfy(w -> assertThat(w).isEqualTo("お客様への注文メール：送信できず（上限）が 2 件あります。"))
                .anySatisfy(w -> assertThat(w).isEqualTo("倉庫ファイルの書き出し：書き出しの失敗が 5 件あります。"));
    }

    @Test void warnsAboutAnEnablingOrderThatSkipsAStep() {
        env.setProperty("app.warehouse-shipment-import.enabled", "true");
        assertThat(service().read().warnings()).anySatisfy(w -> assertThat(w).contains("倉庫ファイルの書き出しは無効です"));
        env.setProperty("app.warehouse-file.enabled", "true");
        assertThat(service().read().warnings()).anySatisfy(w -> assertThat(w).contains("14:05 の倉庫向け出力は無効です"))
                .noneSatisfy(w -> assertThat(w).contains("倉庫ファイルの書き出しは無効です"));
    }
}
