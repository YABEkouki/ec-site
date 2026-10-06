package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.ecsite.payment.PaymentFlowStatus;

class PaymentDiscrepancyTest {

    @Test
    void createsOpenDiscrepancy() {
        Payment payment = new Payment();

        LocalDateTime detectedAt =
                LocalDateTime.of(2026, 10, 6, 12, 0);

        PaymentDiscrepancy discrepancy =
                new PaymentDiscrepancy(
                        payment,
                        PaymentStatus.AUTHORIZED,
                        PaymentFlowStatus.SUCCEEDED,
                        detectedAt);

        assertEquals(
                PaymentDiscrepancyRecordStatus.OPEN,
                discrepancy.getStatus());
        assertEquals(
                PaymentStatus.AUTHORIZED,
                discrepancy.getLocalStatus());
        assertEquals(
                PaymentFlowStatus.SUCCEEDED,
                discrepancy.getProviderStatus());
        assertEquals(1, discrepancy.getDetectionCount());
        assertEquals(
                detectedAt,
                discrepancy.getFirstDetectedAt());
        assertEquals(
                detectedAt,
                discrepancy.getLastDetectedAt());
        assertEquals(
                detectedAt,
                discrepancy.getCreatedAt());
        assertEquals(
                detectedAt,
                discrepancy.getUpdatedAt());
        assertNull(discrepancy.getResolvedAt());
    }

    @Test
    void incrementsDetectionCountWhenDetectedAgain() {
        PaymentDiscrepancy discrepancy =
                discrepancyAt(
                        LocalDateTime.of(
                                2026, 10, 6, 12, 0));

        LocalDateTime detectedAgainAt =
                LocalDateTime.of(
                        2026, 10, 6, 12, 5);

        discrepancy.detectAgain(detectedAgainAt);

        assertEquals(
                PaymentDiscrepancyRecordStatus.OPEN,
                discrepancy.getStatus());
        assertEquals(2, discrepancy.getDetectionCount());
        assertEquals(
                LocalDateTime.of(
                        2026, 10, 6, 12, 0),
                discrepancy.getFirstDetectedAt());
        assertEquals(
                detectedAgainAt,
                discrepancy.getLastDetectedAt());
        assertEquals(
                detectedAgainAt,
                discrepancy.getUpdatedAt());
        assertNull(discrepancy.getResolvedAt());
    }

    @Test
    void resolvesOpenDiscrepancy() {
        PaymentDiscrepancy discrepancy =
                discrepancyAt(
                        LocalDateTime.of(
                                2026, 10, 6, 12, 0));

        LocalDateTime resolvedAt =
                LocalDateTime.of(
                        2026, 10, 6, 12, 10);

        discrepancy.resolve(resolvedAt);

        assertEquals(
                PaymentDiscrepancyRecordStatus.RESOLVED,
                discrepancy.getStatus());
        assertEquals(
                resolvedAt,
                discrepancy.getResolvedAt());
        assertEquals(
                resolvedAt,
                discrepancy.getUpdatedAt());
    }

    @Test
    void resolvingAgainDoesNotOverwriteResolution() {
        PaymentDiscrepancy discrepancy =
                discrepancyAt(
                        LocalDateTime.of(
                                2026, 10, 6, 12, 0));

        LocalDateTime firstResolvedAt =
                LocalDateTime.of(
                        2026, 10, 6, 12, 10);

        LocalDateTime secondResolvedAt =
                LocalDateTime.of(
                        2026, 10, 6, 12, 20);

        discrepancy.resolve(firstResolvedAt);
        discrepancy.resolve(secondResolvedAt);

        assertEquals(
                PaymentDiscrepancyRecordStatus.RESOLVED,
                discrepancy.getStatus());
        assertEquals(
                firstResolvedAt,
                discrepancy.getResolvedAt());
        assertEquals(
                firstResolvedAt,
                discrepancy.getUpdatedAt());
    }

    private PaymentDiscrepancy discrepancyAt(
            LocalDateTime detectedAt) {

        return new PaymentDiscrepancy(
                new Payment(),
                PaymentStatus.AUTHORIZED,
                PaymentFlowStatus.SUCCEEDED,
                detectedAt);
    }
}
