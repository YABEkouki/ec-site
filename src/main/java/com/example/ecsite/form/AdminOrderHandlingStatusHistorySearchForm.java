package com.example.ecsite.form;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.example.ecsite.entity.OrderHandlingStatus;

public class AdminOrderHandlingStatusHistorySearchForm {

    private Long orderId;

    private OrderHandlingStatus fromStatus;

    private OrderHandlingStatus toStatus;

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

    public OrderHandlingStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(OrderHandlingStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public OrderHandlingStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(OrderHandlingStatus toStatus) {
        this.toStatus = toStatus;
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
