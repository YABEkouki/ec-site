package com.example.ecsite.form;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

public class AdminOrderAssigneeHistorySearchForm {

    private Long orderId;

    private AdminOrderAssigneeHistoryFilter fromAssigneeFilter =
            AdminOrderAssigneeHistoryFilter.ALL;

    private Long fromAdminAccountId;

    private AdminOrderAssigneeHistoryFilter toAssigneeFilter =
            AdminOrderAssigneeHistoryFilter.ALL;

    private Long toAdminAccountId;

    private String changedByUsername;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public AdminOrderAssigneeHistoryFilter getFromAssigneeFilter() {
        return fromAssigneeFilter;
    }

    public void setFromAssigneeFilter(
            AdminOrderAssigneeHistoryFilter fromAssigneeFilter) {
        this.fromAssigneeFilter = fromAssigneeFilter;
    }

    public Long getFromAdminAccountId() {
        return fromAdminAccountId;
    }

    public void setFromAdminAccountId(Long fromAdminAccountId) {
        this.fromAdminAccountId = fromAdminAccountId;
    }

    public AdminOrderAssigneeHistoryFilter getToAssigneeFilter() {
        return toAssigneeFilter;
    }

    public void setToAssigneeFilter(
            AdminOrderAssigneeHistoryFilter toAssigneeFilter) {
        this.toAssigneeFilter = toAssigneeFilter;
    }

    public Long getToAdminAccountId() {
        return toAdminAccountId;
    }

    public void setToAdminAccountId(Long toAdminAccountId) {
        this.toAdminAccountId = toAdminAccountId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public void setChangedByUsername(String changedByUsername) {
        this.changedByUsername = changedByUsername;
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
}
