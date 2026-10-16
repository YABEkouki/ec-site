package com.example.ecsite.service;

import java.time.LocalDateTime;

/** Immutable snapshot of the persisted preparation starter. Times are Asia/Tokyo wall time. */
public record ShippingPreparationStartResult(
        Status status,
        LocalDateTime startedAt,
        Long startedByAccountId,
        String startedByUsername) {

    public enum Status {
        STARTED,
        ALREADY_STARTED
    }
}
