package com.example.ecsite.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus;

public record AdminPaymentAuditNotificationHandlingDetail(boolean actionable,
    PaymentAuditNotificationHandlingStatus handlingStatus, boolean requiresRecheck,
    LocalDateTime handlingUpdatedAt, Long lastActorUserId, String lastActorUsername,
    List<AdminPaymentAuditNotificationHandlingHistoryItem> histories,
    List<Integer> confirmedUnknowns, List<Integer> currentUnknowns,
    String snapshotToken, boolean handlingOperationAvailable) {
    public AdminPaymentAuditNotificationHandlingDetail {
        histories=List.copyOf(histories); confirmedUnknowns=List.copyOf(confirmedUnknowns); currentUnknowns=List.copyOf(currentUnknowns);
    }
    @Override public String toString() {
        return "AdminPaymentAuditNotificationHandlingDetail[handlingStatus="+handlingStatus+", requiresRecheck="+requiresRecheck+"]";
    }
}
