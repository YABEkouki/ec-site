package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Pageable;
import com.example.ecsite.config.PaymentReconciliationProperties;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PaymentReconciliationServiceTest {
    PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    PaymentReconciliationItemService item = mock(PaymentReconciliationItemService.class);
    PaymentPostGuardService diagnostics = mock(PaymentPostGuardService.class);
    String blankCharacters = PaymentTransactionRepository.PROVIDER_ID_BLANK_CHARACTERS;
    LocalDateTime cutoff = LocalDateTime.of(2026,10,6,9,55);
    PaymentReconciliationService service = new PaymentReconciliationService(repository,item,
            new PaymentReconciliationProperties(true,Duration.ofMinutes(1),Duration.ofMinutes(5),100),
            Clock.fixed(Instant.parse("2026-10-06T01:00:00Z"),ZoneId.of("Asia/Tokyo")), diagnostics);

    @Test void usesPendingAgeAndBoundedScalarIdQuery() {
        when(repository.reconciliationUpperId(cutoff,false,blankCharacters)).thenReturn(200L);
        when(repository.reconciliationIds(eq(cutoff),eq(false),eq(0L),eq(200L),eq(blankCharacters),any())).thenAnswer(inv -> {
            Pageable page=inv.getArgument(5);
            assertThat(page.getPageSize()).isEqualTo(100); assertThat(page.getPageNumber()).isZero(); return List.of();
        });
        service.reconcilePendingTransactions();verifyNoInteractions(item);
    }
    @Test void passesFixedIdsAndContinuesAfterItemFailure() {
        when(repository.reconciliationUpperId(cutoff,false,blankCharacters)).thenReturn(103L);
        when(repository.reconciliationIds(eq(cutoff),eq(false),eq(0L),eq(103L),eq(blankCharacters),any())).thenReturn(List.of(101L,102L,103L));
        doThrow(new RuntimeException("test")).when(item).reconcile(102L);
        service.reconcilePendingTransactions();
        verify(item).reconcile(101L);verify(item).reconcile(102L);verify(item).reconcile(103L);
    }
    @Test void classifiedConflictsStaleAndDuplicatesDoNotStopCandidates() {
        when(repository.reconciliationUpperId(cutoff,false,blankCharacters)).thenReturn(103L);
        when(repository.reconciliationIds(eq(cutoff),eq(false),eq(0L),eq(103L),eq(blankCharacters),any())).thenReturn(List.of(101L,102L,103L));
        when(item.reconcile(101L)).thenReturn(PaymentSynchronizationOutcome.CONFLICT);
        when(item.reconcile(102L)).thenReturn(PaymentSynchronizationOutcome.IGNORED_STALE);
        when(item.reconcile(103L)).thenReturn(PaymentSynchronizationOutcome.ALREADY_APPLIED);
        service.reconcilePendingTransactions();
        var order=inOrder(item);order.verify(item).reconcile(101L);order.verify(item).reconcile(102L);order.verify(item).reconcile(103L);
    }
    @Test void providerlessDiagnosticsNeverConsumeGetSlots() {
        when(repository.reconciliationUpperId(cutoff,true,blankCharacters)).thenReturn(1L);
        when(repository.reconciliationIds(eq(cutoff),eq(true),eq(0L),eq(1L),eq(blankCharacters),any())).thenAnswer(inv->{
            Pageable page=inv.getArgument(5);assertThat(page.getPageSize()).isEqualTo(10);return List.of(1L);
        });
        service.reconcilePendingTransactions();verifyNoInteractions(item);verify(diagnostics).identifyStoppedPending(1L);
    }
}
