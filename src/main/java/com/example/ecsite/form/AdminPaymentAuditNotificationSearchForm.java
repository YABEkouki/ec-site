package com.example.ecsite.form;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;

/** Typed binding leaves malformed dates/enums/IDs as binding errors for the future controller. */
public class AdminPaymentAuditNotificationSearchForm {
    @Positive(message = "通知IDは正の整数で指定してください。")
    private Long notificationId;
    private PaymentAuditNotificationStatus status;
    private PaymentAuditNotificationWarningType warningType;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    @AssertTrue(message = "作成日FromはTo以前の日付を指定してください。")
    public boolean isDateRangeValid() { return from == null || to == null || !from.isAfter(to); }
    @AssertTrue(message = "日付は0001年から9999年の範囲で指定してください。")
    public boolean isDateBoundsValid() { return supported(from) && supported(to); }
    private static boolean supported(LocalDate date) { return date == null || date.getYear() >= 1 && date.getYear() <= 9999; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
    public PaymentAuditNotificationStatus getStatus() { return status; }
    public void setStatus(PaymentAuditNotificationStatus status) { this.status = status; }
    public PaymentAuditNotificationWarningType getWarningType() { return warningType; }
    public void setWarningType(PaymentAuditNotificationWarningType warningType) { this.warningType = warningType; }
    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }
    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }
}
