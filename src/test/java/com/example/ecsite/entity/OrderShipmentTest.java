package com.example.ecsite.entity;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import com.example.ecsite.exception.InvalidOrderStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;

class OrderShipmentTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 15, 0);
    private Order order(boolean manual, boolean prepared) {
        Order order = new Order(1L, 200, NOW.withHour(10), NOW.withHour(14));
        order.addItem(new OrderItem(1L, "item", 1L, "category", 1L, "tax", "tax", BigDecimal.ZERO, 100, 2));
        order.incrementContentRevision();
        if (manual) order.markAsPaid();
        if (prepared) order.beginShippingPreparation(NOW.minusHours(1), 7L, "preparer");
        return order;
    }
    private void ship(Order order, boolean manual, LocalDateTime at, Long actor, String username) {
        if (manual) order.confirmManualShipment(at, actor, username);
        else order.confirmCardShipment(at, actor, username, 52L);
    }
    private List<Object> snapshot(Order order) {
        return Arrays.asList(order.getStatus(), order.getPaidAt(), order.getShippedAt(),
                order.getShippedByAccountId(), order.getShippedByUsername(), order.getShipmentCapturePaymentId(),
                order.getShippingPreparationStartedAt(), order.getShippingPreparationStartedByAccountId(),
                order.getShippingPreparationStartedByUsername(), order.getTotalAmount(), order.getItemSubtotal(),
                order.getChargeTotal(), order.getTaxAmount(), order.getContentRevision(), order.getHandlingStatus(),
                order.getItems().getFirst().getQuantity(), order.getItems().getFirst().getPrice());
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void recordsShipmentAtomicallyAndPreservesPreparationAmountsRevisionAndItems(boolean manual) {
        Order order = order(manual, true);
        var before = snapshot(order);
        ship(order, manual, NOW, 9L, "x".repeat(100));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getShippedAt()).isEqualTo(NOW);
        assertThat(order.getShippedByAccountId()).isEqualTo(9L);
        assertThat(order.getShippedByUsername()).isEqualTo("x".repeat(100));
        assertThat(order.getShipmentCapturePaymentId()).isEqualTo(manual ? null : 52L);
        assertThat(order.getPaidAt()).isEqualTo(before.get(1));
        assertThat(snapshot(order).subList(6, before.size())).isEqualTo(before.subList(6, before.size()));
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unpreparedIsRejectedWithoutChanges(boolean manual) {
        Order order = order(manual, false); var before = snapshot(order);
        assertThatThrownBy(() -> ship(order, manual, NOW, 9L, "shipper")).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(order)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void partialPreparationCannotShipByEitherNewApi(int mask) {
        for (boolean manual : List.of(true, false)) {
            Order order = order(manual, false);
            if ((mask & 1) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedAt", NOW);
            if ((mask & 2) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedByAccountId", 7L);
            if ((mask & 4) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", "preparer");
            var before = snapshot(order);
            assertThatThrownBy(() -> ship(order, manual, NOW, 9L, "shipper")).isInstanceOf(IllegalStateException.class);
            assertThat(snapshot(order)).isEqualTo(before);
        }
    }
    @ParameterizedTest @EnumSource(OrderStatus.class)
    void eachShipmentApiRequiresItsOwnSourceStatus(OrderStatus status) {
        for (boolean manual : List.of(true, false)) {
            if (status == (manual ? OrderStatus.PAID : OrderStatus.ORDERED)) continue;
            Order order = order(manual, true); ReflectionTestUtils.setField(order, "status", status);
            var before = snapshot(order);
            assertThatThrownBy(() -> ship(order, manual, NOW, 9L, "shipper")).isInstanceOf(InvalidOrderStatusException.class);
            assertThat(snapshot(order)).isEqualTo(before);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"shippedAt", "shippedByAccountId", "shippedByUsername", "shipmentCapturePaymentId"})
    void anyExistingShipmentRecordPreventsBothApisFromOverwriting(String field) {
        for (boolean manual : List.of(true, false)) {
            Order order = order(manual, true);
            ReflectionTestUtils.setField(order, field, switch (field) {
                case "shippedAt" -> NOW.minusMinutes(1);
                case "shippedByUsername" -> "original";
                default -> 11L;
            });
            var before = snapshot(order);
            assertThatThrownBy(() -> ship(order, manual, NOW, 9L, "new")).isInstanceOf(IllegalStateException.class);
            assertThat(snapshot(order)).isEqualTo(before);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"time", "actor-null", "actor-zero", "actor-negative", "username-null", "username-blank", "username-long"})
    void invalidShipmentArgumentsNeverPartiallyMutate(String invalid) {
        for (boolean manual : List.of(true, false)) {
            Order order = order(manual, true); var before = snapshot(order);
            LocalDateTime at = invalid.equals("time") ? null : NOW;
            Long id = switch (invalid) { case "actor-null" -> null; case "actor-zero" -> 0L; case "actor-negative" -> -1L; default -> 9L; };
            String name = switch (invalid) { case "username-null" -> null; case "username-blank" -> "　\t"; case "username-long" -> "x".repeat(101); default -> "shipper"; };
            assertThatIllegalArgumentException().isThrownBy(() -> ship(order, manual, at, id, name));
            assertThat(snapshot(order)).isEqualTo(before);
        }
    }
    @ParameterizedTest @NullSource @ValueSource(longs = {0, -1})
    void invalidFixedPaymentIsRejectedWithoutChanges(Long id) {
        Order order = order(false, true); var before = snapshot(order);
        assertThatIllegalArgumentException().isThrownBy(() -> order.confirmCardShipment(NOW, 9L, "shipper", id));
        assertThat(snapshot(order)).isEqualTo(before);
    }
    @Test void cardShipmentRejectsUnexpectedPreexistingPaidAtWithoutChanges() {
        Order order = order(false, true);
        ReflectionTestUtils.setField(order, "paidAt", NOW.minusMinutes(1));
        var before = snapshot(order);
        assertThatIllegalStateException().isThrownBy(() -> order.confirmCardShipment(NOW, 9L, "shipper", 52L));
        assertThat(snapshot(order)).isEqualTo(before);
    }
    @Test void failedCaptureTransactionDoesNotUndoShipmentOrRecordPaymentSuccess() {
        Order order = order(false, true);
        Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 200, NOW);
        ReflectionTestUtils.setField(payment, "id", 52L);
        payment.setProviderPaymentId("provider-id", NOW);
        payment.markAuthorized(NOW);
        PaymentTransaction capture = new PaymentTransaction(payment, PaymentTransactionType.CAPTURE,
                200, order.getContentRevision(), "capture-key", NOW);
        order.confirmCardShipment(NOW, 9L, "shipper", 52L);
        var before = snapshot(order);
        // Real domain failure record only; orchestration/rollback will be tested in later steps.
        capture.markFailed("provider-id", "declined", "capture failed", NOW.plusMinutes(1));
        assertThat(capture.getStatus()).isEqualTo(PaymentTransactionStatus.FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getPaidAt()).isNull();
        assertThat(snapshot(order)).isEqualTo(before);
    }
    @Test void captureSuccessSetsOnlyPaidAtAndRetryPreservesFirstValue() {
        Order order = order(false, true); order.confirmCardShipment(NOW, 9L, "shipper", 52L);
        var before = snapshot(order);
        assertThat(order.getPaidAt()).isNull(); // Shipment is final while capture is still unresolved.
        order.recordShipmentCaptureSucceeded(52L, NOW.plusMinutes(1));
        var expected = new ArrayList<>(before); expected.set(1, NOW.plusMinutes(1));
        assertThat(snapshot(order)).isEqualTo(expected);
        order.recordShipmentCaptureSucceeded(52L, NOW.plusMinutes(2));
        assertThat(snapshot(order)).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(longs = {-1, 0, 1})
    void captureTimeBoundaryRejectsBeforeShipmentAndAllowsEquality(long seconds) {
        Order order = order(false, true); order.confirmCardShipment(NOW, 9L, "shipper", 52L);
        var before = snapshot(order);
        LocalDateTime capturedAt = NOW.plusSeconds(seconds);
        if (seconds < 0) {
            assertThatIllegalArgumentException().isThrownBy(() -> order.recordShipmentCaptureSucceeded(52L, capturedAt));
            assertThat(order.getPaidAt()).isNull();
            assertThat(snapshot(order)).isEqualTo(before);
        } else {
            order.recordShipmentCaptureSucceeded(52L, capturedAt);
            var expected = new ArrayList<>(before); expected.set(1, capturedAt);
            assertThat(snapshot(order)).isEqualTo(expected);
        }
    }
    @Test void invalidDuplicateCaptureTimeIsRejectedWithoutChangingFirstSuccess() {
        Order order = order(false, true); order.confirmCardShipment(NOW, 9L, "shipper", 52L);
        order.recordShipmentCaptureSucceeded(52L, NOW.plusSeconds(1));
        var before = snapshot(order);
        order.recordShipmentCaptureSucceeded(52L, NOW);
        assertThat(snapshot(order)).isEqualTo(before);
        assertThatIllegalArgumentException().isThrownBy(() -> order.recordShipmentCaptureSucceeded(52L, NOW.minusSeconds(1)));
        assertThat(snapshot(order)).isEqualTo(before);
        assertThatIllegalArgumentException().isThrownBy(() -> order.recordShipmentCaptureSucceeded(52L, null));
        assertThat(snapshot(order)).isEqualTo(before);
    }
    @Test void equalMicrosecondAndNanosecondTimesAreAcceptedWithoutTolerance() {
        for (int nanos : List.of(123456000, 123456789)) {
            Order order = order(false, true);
            LocalDateTime shippedAt = NOW.withNano(nanos);
            order.confirmCardShipment(shippedAt, 9L, "shipper", 52L);
            var before = snapshot(order);
            assertThatIllegalArgumentException().isThrownBy(() -> order.recordShipmentCaptureSucceeded(52L, shippedAt.minusNanos(1)));
            assertThat(snapshot(order)).isEqualTo(before);
            order.recordShipmentCaptureSucceeded(52L, shippedAt);
            assertThat(order.getPaidAt()).isEqualTo(shippedAt);
        }
    }
    @ParameterizedTest @NullSource @ValueSource(longs = {0, -1, 53})
    void captureCannotChangeFixedTarget(Long id) {
        Order order = order(false, true); order.confirmCardShipment(NOW, 9L, "shipper", 52L);
        var before = snapshot(order);
        assertThatThrownBy(() -> order.recordShipmentCaptureSucceeded(id, NOW.plusMinutes(1)))
                .isInstanceOf(id == null || id <= 0 ? IllegalArgumentException.class : IllegalStateException.class);
        assertThat(snapshot(order)).isEqualTo(before);
    }
    @Test void captureRequiresTimeAndCompletedCardShipment() {
        Order card = order(false, true);
        assertThatThrownBy(() -> card.recordShipmentCaptureSucceeded(52L, NOW)).isInstanceOf(IllegalStateException.class);
        Order manual = order(true, true); manual.confirmManualShipment(NOW, 9L, "shipper");
        var manualBefore = snapshot(manual);
        assertThatThrownBy(() -> manual.recordShipmentCaptureSucceeded(52L, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(manual)).isEqualTo(manualBefore);
        card.confirmCardShipment(NOW, 9L, "shipper", 52L); var before = snapshot(card);
        assertThatIllegalArgumentException().isThrownBy(() -> card.recordShipmentCaptureSucceeded(52L, null));
        assertThat(snapshot(card)).isEqualTo(before);
    }
}
