package com.example.ecsite.web;

import org.springframework.stereotype.Component;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationCloseReason;
import com.example.ecsite.entity.PaymentAuditNotificationItemStatus;
import com.example.ecsite.entity.PaymentAuditNotificationAttemptResult;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning;

/** Fixed, safe labels shared by the history view. */
@Component("notificationDisplay")
public class AdminPaymentAuditNotificationDisplay {
    public String statusLabel(PaymentAuditNotificationStatus status) {
        return switch (status) {
            case PENDING -> "送信待ち";
            case CLAIMED -> "配送準備中";
            case SENDING -> "送信処理中";
            case RETRY_WAIT -> "再試行待ち";
            case SENT -> "送信済み";
            case EXHAUSTED -> "再試行上限到達";
            case CANCELLED -> "送信取消";
        };
    }
    public String statusClass(PaymentAuditNotificationStatus status) {
        return switch (status) {
            case PENDING, CLAIMED -> "status-ordered";
            case SENDING -> "status-shipped";
            case SENT -> "status-paid";
            case RETRY_WAIT, EXHAUSTED, CANCELLED -> "status-cancelled";
        };
    }
    public String warningLabel(PaymentAuditNotificationWarningType type) {
        return AdminPaymentDiscrepancyAuditWarning.Type.valueOf(type.name()).getTitle();
    }
    public String closeReasonLabel(PaymentAuditNotificationCloseReason reason) {
        if (reason == null) return "—";
        return switch (reason) {
            case RESOLVED -> "対象警告の解消";
            case NO_VALID_RECIPIENTS -> "有効な送信先なし";
            case MAX_ATTEMPTS -> "最大試行回数到達";
        };
    }
    public String itemStatusLabel(PaymentAuditNotificationItemStatus status) {
        return switch (status) {
            case INCLUDED -> "通知対象に含む";
            case REMOVED_RESOLVED -> "解消により除外";
        };
    }
    public String attemptResultLabel(PaymentAuditNotificationAttemptResult result) {
        return switch (result) {
            case IN_PROGRESS -> "処理中";
            case SUCCESS -> "送信成功";
            case FAILURE -> "送信失敗";
            case UNKNOWN -> "結果不明";
        };
    }

}
