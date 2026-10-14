package com.example.ecsite.entity;

/** Manual-only pre-send cancellation codes; never used as SMTP failure codes.
 * Warning resolution/empty recipients reuse PaymentAuditNotificationCloseReason.
 */
public enum PaymentAuditNotificationManualResendCancellationCode {
    /** Feature117 handling became RESOLVED before SENDING was committed. */
    HANDLING_RESOLVED,
    /** A source/prior-request prohibition was detected in the pre-send check. */
    RESEND_PROHIBITED,
    /** Delivery was disabled; reserved for a future pre-send cancellation policy. */
    NOTIFICATION_DISABLED
}
