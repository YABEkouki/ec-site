package com.example.ecsite.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus;

public record AdminPaymentAuditNotificationHandlingDetail(boolean actionable,
    PaymentAuditNotificationHandlingStatus handlingStatus, boolean requiresRecheck,
    LocalDateTime handlingUpdatedAt, Long lastActorUserId, String lastActorUsername,
    List<AdminPaymentAuditNotificationHandlingHistoryItem> histories,
    List<Integer> confirmedUnknowns, List<Integer> currentUnknowns,
    List<Long> confirmedManualUnknowns, List<Long> currentManualUnknowns,
    String snapshotToken, boolean handlingOperationAvailable) {
    public AdminPaymentAuditNotificationHandlingDetail(boolean actionable,PaymentAuditNotificationHandlingStatus status,boolean recheck,
        LocalDateTime updatedAt,Long actorId,String actorUsername,List<AdminPaymentAuditNotificationHandlingHistoryItem> histories,
        List<Integer> confirmed,List<Integer> current,String token,boolean available) {
        this(actionable,status,recheck,updatedAt,actorId,actorUsername,histories,confirmed,current,List.of(),List.of(),token,available);
    }
    public AdminPaymentAuditNotificationHandlingDetail {
        histories=List.copyOf(histories); confirmedUnknowns=List.copyOf(confirmedUnknowns); currentUnknowns=List.copyOf(currentUnknowns);
        confirmedManualUnknowns=List.copyOf(confirmedManualUnknowns); currentManualUnknowns=List.copyOf(currentManualUnknowns);
    }
    @Override public String toString() {
        return "AdminPaymentAuditNotificationHandlingDetail[handlingStatus="+handlingStatus+", requiresRecheck="+requiresRecheck+"]";
    }
}
