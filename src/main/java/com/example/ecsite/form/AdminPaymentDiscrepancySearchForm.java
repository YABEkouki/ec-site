package com.example.ecsite.form;

import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.PaymentFlowStatus;

public class AdminPaymentDiscrepancySearchForm {

    private Long orderId;

    private Long userId;

    private PaymentStatus localStatus;

    private PaymentFlowStatus providerStatus;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public PaymentStatus getLocalStatus() {
        return localStatus;
    }

    public void setLocalStatus(PaymentStatus localStatus) {
        this.localStatus = localStatus;
    }

    public PaymentFlowStatus getProviderStatus() {
        return providerStatus;
    }

    public void setProviderStatus(PaymentFlowStatus providerStatus) {
        this.providerStatus = providerStatus;
    }
}
