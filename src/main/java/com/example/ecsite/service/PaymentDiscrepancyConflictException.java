package com.example.ecsite.service;

/** The administrator submitted a version older than the current record. */
public class PaymentDiscrepancyConflictException extends RuntimeException {
    public PaymentDiscrepancyConflictException(Long discrepancyId) {
        super("Payment discrepancy version conflict: id=" + discrepancyId);
    }
}
