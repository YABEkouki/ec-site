package com.example.ecsite.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus;

public class AdminPaymentAuditNotificationHandlingForm {
    @NotNull private PaymentAuditNotificationHandlingStatus status;
    private String comment;
    @NotBlank @Size(max=2048) private String snapshotToken;
    public PaymentAuditNotificationHandlingStatus getStatus() { return status; }
    public void setStatus(PaymentAuditNotificationHandlingStatus value) { status=value; }
    public String getComment() { return comment; }
    public void setComment(String value) { comment=value; }
    public String getSnapshotToken() { return snapshotToken; }
    public void setSnapshotToken(String value) { snapshotToken=value; }
}
