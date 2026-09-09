package com.example.ecsite.repository.projection;

import java.time.LocalDateTime;

public interface OrderHandlingStatusUpdatedAtProjection {

    Long getOrderId();

    LocalDateTime getUpdatedAt();
}
