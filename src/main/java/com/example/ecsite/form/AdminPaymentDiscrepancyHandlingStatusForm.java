package com.example.ecsite.form;

import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

public class AdminPaymentDiscrepancyHandlingStatusForm {

    @NotNull
    private PaymentDiscrepancyHandlingStatus handlingStatus;

    @NotNull
    @Min(0)
    private Long expectedVersion;

    public Long getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public PaymentDiscrepancyHandlingStatus getHandlingStatus() {
        return handlingStatus;
    }

    public void setHandlingStatus(
            PaymentDiscrepancyHandlingStatus handlingStatus) {
        this.handlingStatus = handlingStatus;
    }
}
