package com.example.ecsite.form;

import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;

import jakarta.validation.constraints.NotNull;

public class AdminPaymentDiscrepancyHandlingStatusForm {

    @NotNull
    private PaymentDiscrepancyHandlingStatus handlingStatus;

    public PaymentDiscrepancyHandlingStatus getHandlingStatus() {
        return handlingStatus;
    }

    public void setHandlingStatus(
            PaymentDiscrepancyHandlingStatus handlingStatus) {
        this.handlingStatus = handlingStatus;
    }
}
