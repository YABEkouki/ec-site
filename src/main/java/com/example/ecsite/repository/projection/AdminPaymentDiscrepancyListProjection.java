package com.example.ecsite.repository.projection;

import java.time.LocalDateTime;

public interface AdminPaymentDiscrepancyListProjection {

    Long getDiscrepancyId();

    Long getOrderId();

    Long getUserId();

    String getUsername();

    String getLocalStatus();

    String getProviderStatus();

    String getHandlingStatus();

    LocalDateTime getHandlingStatusUpdatedAt();

    LocalDateTime getFirstDetectedAt();

    LocalDateTime getLastDetectedAt();

    Integer getDetectionCount();
}
