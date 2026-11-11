package com.example.ecsite.service.order.evidence;
import java.util.*;
/** Explicit V1 audit DTO. Times are UTC Unix microseconds except saved local timestamps.
 * It records local facts, never asserts external authorization validity. */
public record OrderAdmissionFinancialSnapshot(String domain, Integer version, UUID admissionId, Long orderId, Long customerId, UUID dbGeneration, String originalXid, String requestDigest, String candidateDigest, Integer revision, String status, Long deadline, UUID epoch, Long head, String fingerprint, String epochDigest, String headDigest, Long activePayment, Integer orderAmount, Long payment, Long authorization, Integer authorizedAmount, Integer authorizationRevision, List<Payment> payments, List<Transaction> transactions, List<Operation> operations, List<Request> requests, List<Dispatch> dispatches, List<Capture> captures) {
    public OrderAdmissionFinancialSnapshot {
        payments=List.copyOf(payments); transactions=List.copyOf(transactions); operations=List.copyOf(operations);
        requests=List.copyOf(requests); dispatches=List.copyOf(dispatches); captures=List.copyOf(captures);
    }
    @Override public String toString(){return "AdmissionFinancialSnapshot[redacted]";}
    public record Payment(Long id, Long orderId, String provider, String method, String status, Integer amount, String providerId) {}
    public record Transaction(Long id, Long paymentId, String type, String status, Integer amount, Integer revision, String purpose, UUID operation, String providerId, String completed) {}
    public record Operation(UUID id, Long orderId, String state, Long version, Integer baseRevision, Long oldPayment, Long newPayment, Integer beforeAmount, Integer afterAmount, String switchDecided, Long history, Integer executionVersion, String route) {}
    public record Request(Long id, UUID operation, Long orderId, Long payment, Long transaction, String purpose, String kind, String type, Integer amount, String flow, String idempotency, String connection, String account, String mode, String origin, String method, String path, String contentType, Integer bodyVersion, String bodyDigest, Integer fingerprintVersion, String fingerprint, Long created, Long replayUntil) {}
    public record Dispatch(Long request, String state, Long created, Long started, Long observed) {}
    public record Capture(Long id, Long payment, Long transaction, String type, String provider, String method, String flow, String connection, String account, String mode, String origin, String httpMethod, String path, String contentType, Integer amount, Integer revision, String idempotency, Integer bodyVersion, String bodyDigest, Integer fingerprintVersion, String fingerprint, Long created, Long replayUntil) {}
}
