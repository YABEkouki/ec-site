package com.example.ecsite.dto;

import java.time.LocalDateTime;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;
import com.example.ecsite.entity.PaymentAuditNotificationItemStatus;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunErrorCode;

/** Historical item snapshot. INCLUDED does not assert that the warning is still active.
 * warningCount is the persisted warning-related count, not a separate observation counter. */
public record AdminPaymentAuditNotificationWarningItem(PaymentAuditNotificationWarningType warningType,
        long episodeNo, PaymentAuditNotificationItemStatus itemStatus, LocalDateTime firstObservedAt,
        LocalDateTime relatedAt, Long warningCount, PaymentDiscrepancyAuditRunErrorCode errorCode,
        LocalDateTime resolvedAt, LocalDateTime removedAt) {}
