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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-5, 0, 5})
    void redetectionKeepsTimestampsMonotonicAndAlwaysCountsDetection(int minutes) {
        LocalDateTime first = LocalDateTime.of(2026, 10, 6, 12, 0);
        PaymentDiscrepancy entity = discrepancyAt(first);
        entity.detectAgain(first.plusMinutes(minutes));
        LocalDateTime expected = minutes > 0 ? first.plusMinutes(5) : first;
        assertEquals(first, entity.getFirstDetectedAt());
        assertEquals(first, entity.getCreatedAt());
        assertEquals(expected, entity.getLastDetectedAt());
        assertEquals(expected, entity.getUpdatedAt());
        assertEquals(2, entity.getDetectionCount());
    }

    @Test
    void olderResolutionRecordsActualTimeWithoutMovingUpdatedAtBackwards() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 6, 12, 0);
        PaymentDiscrepancy entity = discrepancyAt(first);
        entity.resolve(first.minusMinutes(5));
        assertEquals(first.minusMinutes(5), entity.getResolvedAt());
        assertEquals(first, entity.getUpdatedAt());
        assertEquals(first, entity.getFirstDetectedAt());
        assertEquals(first, entity.getLastDetectedAt());
        assertEquals(1, entity.getDetectionCount());
        entity.resolve(first.plusMinutes(10));
        assertEquals(first.minusMinutes(5), entity.getResolvedAt());
        assertEquals(first, entity.getUpdatedAt());
    }

    @Test
    void handlingChangesKeepHandlingTimestampMonotonicAndAuditTimestampsUnchanged() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 6, 12, 0);
        PaymentDiscrepancy entity = discrepancyAt(first);
        entity.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.CONFIRMED, first.plusMinutes(5));
        entity.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.UNCONFIRMED, first.minusMinutes(5));
        assertEquals(PaymentDiscrepancyHandlingStatus.UNCONFIRMED, entity.getHandlingStatus());
        assertEquals(first.plusMinutes(5), entity.getHandlingStatusUpdatedAt());
        assertEquals(first, entity.getUpdatedAt());
        assertEquals(first, entity.getFirstDetectedAt());
        assertEquals(first, entity.getLastDetectedAt());
        assertEquals(1, entity.getDetectionCount());
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
