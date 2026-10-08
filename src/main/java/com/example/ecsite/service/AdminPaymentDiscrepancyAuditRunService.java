package com.example.ecsite.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.example.ecsite.config.PaymentDiscrepancyAuditProperties;
import com.example.ecsite.config.PaymentDiscrepancyAuditMonitoringProperties;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning.Type;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditMonitoringSummary;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditRunListItem;
import com.example.ecsite.entity.PaymentDiscrepancyAuditExecutionType;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancyAuditRunSearchForm;
import com.example.ecsite.repository.PaymentDiscrepancyAuditRunRepository;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;

@Service
@Transactional(readOnly = true)
public class AdminPaymentDiscrepancyAuditRunService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final PaymentDiscrepancyAuditRunRepository runs;
    private final PaymentDiscrepancyRepository discrepancies;
    private final Clock clock;
    private final PaymentDiscrepancyAuditProperties auditProperties;
    private final PaymentDiscrepancyAuditMonitoringProperties monitoringProperties;
    public AdminPaymentDiscrepancyAuditRunService(PaymentDiscrepancyAuditRunRepository runs,
            PaymentDiscrepancyRepository discrepancies, Clock clock,
            PaymentDiscrepancyAuditProperties auditProperties,
            PaymentDiscrepancyAuditMonitoringProperties monitoringProperties) {
        this.runs = runs; this.discrepancies = discrepancies; this.clock = clock;
        this.auditProperties = auditProperties;
        this.monitoringProperties = monitoringProperties;
    }
    public Page<AdminPaymentDiscrepancyAuditRunListItem> search(
            AdminPaymentDiscrepancyAuditRunSearchForm form, int page, int size) {
        var from = form.getFrom() == null ? null : form.getFrom().atStartOfDay(TOKYO).toInstant();
        var to = form.getTo() == null ? null : form.getTo().plusDays(1).atStartOfDay(TOKYO).toInstant();
        int safeSize = Math.clamp(size, 1, 100);
        int safePage = Math.max(page, 0);
        boolean exceedsJpaOffset = (long) safePage * safeSize > Integer.MAX_VALUE;
        // A bounded first page provides the total for exactly the same filters without
        // passing an unsupported offset to JPA. Ordinary requests keep their existing path.
        var result = runs.searchForAdmin(PaymentDiscrepancyAuditExecutionType.SCHEDULED,
            form.getStatus(), from, to, PageRequest.of(exceedsJpaOffset ? 0 : safePage, safeSize));
        if (exceedsJpaOffset) {
            int targetPage = Math.max(0, result.getTotalPages() - 1);
            if (targetPage > 0) {
                result = runs.searchForAdmin(PaymentDiscrepancyAuditExecutionType.SCHEDULED,
                    form.getStatus(), from, to, PageRequest.of(targetPage, safeSize));
            }
        }
        int lastPage = Math.max(0, result.getTotalPages() - 1);
        if (result.getNumber() > lastPage) {
            result = runs.searchForAdmin(PaymentDiscrepancyAuditExecutionType.SCHEDULED,
                form.getStatus(), from, to, PageRequest.of(lastPage, safeSize));
        }
        return result.map(AdminPaymentDiscrepancyAuditRunListItem::from);
    }
    public AdminPaymentDiscrepancyAuditMonitoringSummary monitoringSummary() {
        Instant now = clock.instant();
        var latest = runs.findFirstByExecutionTypeOrderByStartedAtDescIdDesc(PaymentDiscrepancyAuditExecutionType.SCHEDULED)
            .map(AdminPaymentDiscrepancyAuditRunListItem::from).orElse(null);
        var lastSuccess = runs.findFirstByExecutionTypeAndStatusOrderByFinishedAtDescIdDesc(
            PaymentDiscrepancyAuditExecutionType.SCHEDULED, PaymentDiscrepancyAuditRunStatus.SUCCESS)
            .map(run -> LocalDateTime.ofInstant(run.getFinishedAt(), TOKYO)).orElse(null);
        var cutoff = tokyo(now.minus(monitoringProperties.longUnhandledAge()));
        long openCount = discrepancies.countByStatus(PaymentDiscrepancyRecordStatus.OPEN);
        long longUnhandledCount = discrepancies.countLongUnhandled(PaymentDiscrepancyRecordStatus.OPEN,
            PaymentDiscrepancyHandlingStatus.COMPLETED, cutoff);
        var longRunning = runs.summarizeLongRunning(PaymentDiscrepancyAuditExecutionType.SCHEDULED,
            now.minus(monitoringProperties.runningThreshold()));
        var recentFinished = runs.findRecentFinishedForMonitoring(PaymentDiscrepancyAuditExecutionType.SCHEDULED,
            PageRequest.of(0, monitoringProperties.consecutiveFailures()));
        List<AdminPaymentDiscrepancyAuditWarning> warnings = new ArrayList<>();
        // Keep failures first, followed by execution timing and unresolved discrepancies.
        if (recentFinished.size() == monitoringProperties.consecutiveFailures()
                && recentFinished.stream().allMatch(run -> run.getStatus() == PaymentDiscrepancyAuditRunStatus.FAILED)) {
            warnings.add(new AdminPaymentDiscrepancyAuditWarning(Type.CONSECUTIVE_FAILURES,
                "直近の終了済み定期監査が連続してFAILEDです。監査処理の状況を確認してください。",
                tokyo(recentFinished.getFirst().getFinishedAt()), (long) recentFinished.size(), null));
        }
        if (latest != null && (latest.status() == PaymentDiscrepancyAuditRunStatus.FAILED
                || latest.status() == PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE)) {
            warnings.add(new AdminPaymentDiscrepancyAuditWarning(Type.LATEST_FAILURE,
                latest.status() == PaymentDiscrepancyAuditRunStatus.FAILED
                    ? "直近の定期監査処理が失敗しました（FAILED）。"
                    : "直近の定期監査の一部の監査処理が失敗しました（PARTIAL_FAILURE）。",
                latest.startedAt(), null, latest.errorCode()));
        }
        if (longRunning.getCount() > 0) {
            warnings.add(new AdminPaymentDiscrepancyAuditWarning(Type.LONG_RUNNING,
                "設定時間以上、終了記録がない定期監査があります。処理中か、プロセス停止・終了記録失敗による残留かは未確認です。関連日時は最も古い開始日時です。",
                tokyo(longRunning.getOldestStartedAt()), longRunning.getCount(), null));
        }
        if (auditProperties.enabled() && latest != null
                && latest.status() != PaymentDiscrepancyAuditRunStatus.RUNNING
                && tokyo(now).isAfter(latest.finishedAt().plus(auditProperties.fixedDelay())
                    .plus(monitoringProperties.delayGrace()))) {
            warnings.add(new AdminPaymentDiscrepancyAuditWarning(Type.DELAYED,
                "最新監査の終了日時からfixedDelayと猶予を超過しています。運用上の目安であり、Schedulerの停止を断定するものではありません。関連日時は基準となる終了日時です。",
                latest.finishedAt(), null, null));
        }
        if (auditProperties.enabled() && latest == null) {
            warnings.add(new AdminPaymentDiscrepancyAuditWarning(Type.NO_HISTORY,
                "定期監査の実行履歴がありません。", null, null, null));
        }
        if (longUnhandledCount > 0) {
            warnings.add(new AdminPaymentDiscrepancyAuditWarning(Type.LONG_UNHANDLED,
                "設定された長期未対応時間以上経過した、OPENかつ管理者対応がCOMPLETED以外の決済不整合があります。",
                cutoff, longUnhandledCount, null));
        }
        return new AdminPaymentDiscrepancyAuditMonitoringSummary(latest, lastSuccess,
            openCount, longUnhandledCount, auditProperties.enabled(), tokyo(now),
            monitoringProperties.longUnhandledAge(), warnings);
    }
    private static LocalDateTime tokyo(Instant time) {
        return LocalDateTime.ofInstant(time, TOKYO);
    }
}
