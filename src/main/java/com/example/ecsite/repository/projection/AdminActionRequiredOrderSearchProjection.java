package com.example.ecsite.repository.projection;

import java.time.LocalDateTime;

public interface AdminActionRequiredOrderSearchProjection {

    Long getOrderId();

    LocalDateTime getHandlingStatusUpdatedAt();
}
