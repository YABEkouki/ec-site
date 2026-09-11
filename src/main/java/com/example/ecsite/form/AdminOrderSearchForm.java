package com.example.ecsite.form;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderStatus;

public class AdminOrderSearchForm {

    private Long orderId;

    private Long userId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    private OrderStatus status;

    private OrderHandlingStatus handlingStatus;

    private AdminOrderAssigneeFilter assigneeFilter = AdminOrderAssigneeFilter.ALL;

    private Long assignedAdminAccountId;

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

    public LocalDate getFrom() {
        return from;
    }

    public void setFrom(LocalDate from) {
        this.from = from;
    }

    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public OrderHandlingStatus getHandlingStatus() {
        return handlingStatus;
    }

    public void setHandlingStatus(OrderHandlingStatus handlingStatus) {
        this.handlingStatus = handlingStatus;
    }

    public AdminOrderAssigneeFilter getAssigneeFilter() {
        return assigneeFilter;
    }

    public void setAssigneeFilter(
            AdminOrderAssigneeFilter assigneeFilter) {
        this.assigneeFilter = assigneeFilter;
    }

    public Long getAssignedAdminAccountId() {
        return assignedAdminAccountId;
    }

    public void setAssignedAdminAccountId(
            Long assignedAdminAccountId) {
        this.assignedAdminAccountId = assignedAdminAccountId;
    }
}
