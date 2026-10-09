package com.example.ecsite.web;

import org.springframework.stereotype.Component;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
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
}
