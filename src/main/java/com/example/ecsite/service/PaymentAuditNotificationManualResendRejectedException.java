package com.example.ecsite.service;

/** Fixed safe reason codes for subsequent controller messages; no request contents. */
public class PaymentAuditNotificationManualResendRejectedException extends RuntimeException {
    public enum Reason { NOT_EXHAUSTED, DELIVERY_UNCERTAIN, UNKNOWN_PRESENT, HANDLING_RESOLVED, NOTIFICATION_DISABLED,
        LIMIT_REACHED, TOO_SOON, ACTIVE_REQUEST, PREVIOUS_RESULT, RISK_ACKNOWLEDGEMENT_REQUIRED, STALE_SNAPSHOT, NONCE_CONFLICT }
    private final Reason reason;
    public PaymentAuditNotificationManualResendRejectedException(Reason reason){super("Manual resend request rejected: "+reason.name());this.reason=reason;}
    public Reason getReason(){return reason;}
}
