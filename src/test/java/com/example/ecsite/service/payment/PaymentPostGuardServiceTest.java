package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Optional;
import com.example.ecsite.config.PayJpSafetyProperties;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentPostGuardServiceTest {
    Clock clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    PayJpSafetyProperties properties = new PayJpSafetyProperties(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofHours(23));
    PaymentPostGuardService guard = new PaymentPostGuardService(repository, clock, properties);
    PaymentTransaction operation(LocalDateTime created) {
        Order order = new Order(1L, 3000, LocalDateTime.now(clock), LocalDateTime.now(clock).plusDays(1));
        ReflectionTestUtils.setField(order, "id", 10L);
        Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 3000, LocalDateTime.now(clock));
        ReflectionTestUtils.setField(payment, "id", 20L);
        var transaction = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, 3000, 0, "fixed-key", LocalDateTime.now(clock));
        ReflectionTestUtils.setField(transaction, "id", 30L);
        ReflectionTestUtils.setField(transaction, "createdAt", created);
        when(repository.findById(30L)).thenReturn(Optional.of(transaction));
        return transaction;
    }
    @Test void lastNanosecondBeforeWindowIsAllowed() {
        var t = operation(LocalDateTime.now(clock).minusHours(23).plusNanos(1));
        assertThatCode(() -> guard.beforeNewPost(PaymentOperationSnapshot.from(t))).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(longs={23,24,100})
    void exactWindowAndOlderAreStoppedWithoutMutation(long hours) {
        var t = operation(LocalDateTime.now(clock).minusHours(hours));
        assertThatThrownBy(() -> guard.beforeNewPost(PaymentOperationSnapshot.from(t)))
                .isInstanceOf(PaymentSafetyStopException.class);
        assertThat(t.getStatus()).isEqualTo(PaymentTransactionStatus.PENDING);
        assertThat(t.getIdempotencyKey()).isEqualTo("fixed-key");
        verify(repository, never()).save(any());
    }
    @Test void missingDateStops() {
        var t = operation(null);
        assertThatThrownBy(() -> guard.beforeNewPost(PaymentOperationSnapshot.from(t))).isInstanceOf(PaymentSafetyStopException.class);
    }
    @Test void futureDateStops() {
        var t = operation(LocalDateTime.now(clock).plusNanos(1));
        assertThatThrownBy(() -> guard.beforeNewPost(PaymentOperationSnapshot.from(t))).isInstanceOf(PaymentSafetyStopException.class);
    }
    @Test void persistedParametersAreRechecked() {
        var t = operation(LocalDateTime.now(clock)); var snapshot = PaymentOperationSnapshot.from(t);
        ReflectionTestUtils.setField(t, "amount", 2500);
        assertThatThrownBy(() -> guard.beforeNewPost(snapshot)).isInstanceOf(PaymentSafetyStopException.class);
    }
    @Test void completedOperationCannotPost() {
        var t = operation(LocalDateTime.now(clock)); var snapshot = PaymentOperationSnapshot.from(t);
        t.markSucceeded("pf", LocalDateTime.now(clock));
        assertThatThrownBy(() -> guard.beforeNewPost(snapshot)).isInstanceOf(PaymentSafetyStopException.class);
    }
    @Test void missingOperationStops() {
        var t = operation(LocalDateTime.now(clock)); var snapshot = PaymentOperationSnapshot.from(t);
        when(repository.findById(30L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.beforeNewPost(snapshot)).isInstanceOf(PaymentSafetyStopException.class);
    }
    @Test void longProviderlessPendingIsIdentifiableWithoutStateChange() {
        var t = operation(LocalDateTime.now(clock).minusDays(2));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PaymentPostGuardService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            guard.identifyStoppedPending(30L);
            assertThat(appender.list).singleElement().satisfies(e -> {
                assertThat(e.getFormattedMessage()).contains("LONG_PENDING_WITHOUT_PROVIDER_ID", "orderId=10", "transactionId=30").doesNotContain("fixed-key");
            });
            assertThat(t.getStatus()).isEqualTo(PaymentTransactionStatus.PENDING);
            verify(repository, never()).save(any());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void clockZoneCannotExtendPersistedJstPostWindow() {
        var t = operation(LocalDateTime.now(clock).minusHours(24));
        var utcGuard = new PaymentPostGuardService(repository, clock.withZone(ZoneOffset.UTC), properties);
        assertThatThrownBy(() -> utcGuard.beforeNewPost(PaymentOperationSnapshot.from(t)))
                .isInstanceOf(PaymentSafetyStopException.class);
    }
}
