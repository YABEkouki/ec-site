package com.example.ecsite.form;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;

public class AdminPaymentDiscrepancyAuditRunSearchForm {
    private PaymentDiscrepancyAuditRunStatus status;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;
    public PaymentDiscrepancyAuditRunStatus getStatus() { return status; }
    public void setStatus(PaymentDiscrepancyAuditRunStatus status) { this.status = status; }
    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }
    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }
}
