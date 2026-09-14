package com.example.ecsite.repository.projection;

import java.time.LocalDateTime;

public interface AdminCustomerPurchaseSummaryProjection {

    long getOrderCount();

    long getPurchaseAmount();

    LocalDateTime getLastOrderedAt();
}
