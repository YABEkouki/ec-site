package com.example.ecsite.form;

import com.example.ecsite.entity.OrderHandlingStatus;

import jakarta.validation.constraints.NotNull;

public class AdminOrderHandlingStatusForm {

    @NotNull
    private OrderHandlingStatus handlingStatus;

    public OrderHandlingStatus getHandlingStatus() {
        return handlingStatus;
    }

    public void setHandlingStatus(OrderHandlingStatus handlingStatus) {
        this.handlingStatus = handlingStatus;
    }

    private Long assignedAdminAccountId;

    public Long getAssignedAdminAccountId() {
        return assignedAdminAccountId;
    }

    public void setAssignedAdminAccountId(Long assignedAdminAccountId) {
        this.assignedAdminAccountId = assignedAdminAccountId;
    }

}
