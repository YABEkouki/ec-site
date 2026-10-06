package com.example.ecsite.service.payment;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.config.PaymentReconciliationProperties;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PaymentReconciliationServiceTest {

    private PaymentTransactionRepository paymentTransactionRepository;
    private PaymentReconciliationItemService itemService;
    private PaymentReconciliationService service;

    @BeforeEach
    void setUp() {

        paymentTransactionRepository = mock(PaymentTransactionRepository.class);

        itemService = mock(PaymentReconciliationItemService.class);

        PaymentReconciliationProperties properties = new PaymentReconciliationProperties(
                true,
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                100);

        Clock clock = Clock.fixed(
                Instant.parse("2026-10-06T01:00:00Z"),
                ZoneId.of("Asia/Tokyo"));

        service = new PaymentReconciliationService(
                paymentTransactionRepository,
                itemService,
                properties,
                clock);
    }

    @Test
    void reconcilePendingTransactionsUsesPendingAgeAndBatchSize() {

        when(paymentTransactionRepository
                .findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
                        eq(PaymentTransactionStatus.PENDING),
                        eq(java.time.LocalDateTime.of(
                                2026, 10, 6, 9, 55)),
                        org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of());

        service.reconcilePendingTransactions();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(paymentTransactionRepository)
                .findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
                        eq(PaymentTransactionStatus.PENDING),
                        eq(java.time.LocalDateTime.of(
                                2026, 10, 6, 9, 55)),
                        pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals(
                100,
                pageable.getPageSize());

        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                pageable.getPageNumber());
    }

    @Test
    void reconcilePendingTransactionsPassesTransactionIdsToItemService() {

        PaymentTransaction first = mock(PaymentTransaction.class);
        PaymentTransaction second = mock(PaymentTransaction.class);

        when(first.getId()).thenReturn(101L);
        when(second.getId()).thenReturn(102L);

        when(paymentTransactionRepository
                .findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(first, second));

        service.reconcilePendingTransactions();

        verify(itemService).reconcile(101L);
        verify(itemService).reconcile(102L);
    }

    @Test
    void reconcilePendingTransactionsContinuesAfterItemFailure() {

        PaymentTransaction first = mock(PaymentTransaction.class);
        PaymentTransaction second = mock(PaymentTransaction.class);
        PaymentTransaction third = mock(PaymentTransaction.class);

        when(first.getId()).thenReturn(101L);
        when(second.getId()).thenReturn(102L);
        when(third.getId()).thenReturn(103L);

        when(paymentTransactionRepository
                .findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        first,
                        second,
                        third));

        org.mockito.Mockito.doThrow(
                new RuntimeException("PAY.JP error"))
                .when(itemService)
                .reconcile(102L);

        service.reconcilePendingTransactions();

        verify(itemService).reconcile(101L);
        verify(itemService).reconcile(102L);
        verify(itemService).reconcile(103L);
    }
}
