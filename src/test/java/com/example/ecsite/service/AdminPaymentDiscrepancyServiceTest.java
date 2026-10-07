package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatusHistory;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.PaymentDiscrepancyHandlingStatusHistoryRepository;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;

@ExtendWith(MockitoExtension.class)
class AdminPaymentDiscrepancyServiceTest {

    @Mock
    private PaymentDiscrepancyRepository paymentDiscrepancyRepository;

    @Mock
    private PaymentDiscrepancyHandlingStatusHistoryRepository paymentDiscrepancyHandlingStatusHistoryRepository;

    @InjectMocks
    private AdminPaymentDiscrepancyService service;

    @Test
    void searchPassesSearchConditionsToRepository() {

        AdminPaymentDiscrepancySearchForm form = new AdminPaymentDiscrepancySearchForm();

        form.setOrderId(123L);
        form.setUserId(456L);
        form.setLocalStatus(PaymentStatus.PENDING);
        form.setProviderStatus(
                PaymentFlowStatus.REQUIRES_CAPTURE);
        form.setHandlingStatus(
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS);

        Page<AdminPaymentDiscrepancyListProjection> result = new PageImpl<>(java.util.List.of());

        when(paymentDiscrepancyRepository.searchOpenForAdmin(
                123L,
                456L,
                PaymentStatus.PENDING.name(),
                PaymentFlowStatus.REQUIRES_CAPTURE.name(),
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS.name(),
                PageRequest.of(2, 20)))
                .thenReturn(result);

        service.search(form, 2, 20);

        verify(paymentDiscrepancyRepository)
                .searchOpenForAdmin(
                        123L,
                        456L,
                        PaymentStatus.PENDING.name(),
                        PaymentFlowStatus.REQUIRES_CAPTURE.name(),
                        PaymentDiscrepancyHandlingStatus.IN_PROGRESS.name(),
                        PageRequest.of(2, 20));
    }

    @Test
    void searchPassesNullForUnspecifiedConditions() {

        AdminPaymentDiscrepancySearchForm form = new AdminPaymentDiscrepancySearchForm();

        Page<AdminPaymentDiscrepancyListProjection> result = new PageImpl<>(java.util.List.of());

        when(paymentDiscrepancyRepository.searchOpenForAdmin(
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10)))
                .thenReturn(result);

        service.search(form, 0, 10);

        verify(paymentDiscrepancyRepository)
                .searchOpenForAdmin(
                        null,
                        null,
                        null,
                        null,
                        null,
                        PageRequest.of(0, 10));
    }

    @Test
    void countOpenReturnsOpenDiscrepancyCount() {

        when(paymentDiscrepancyRepository.countByStatus(
                PaymentDiscrepancyRecordStatus.OPEN))
                .thenReturn(3L);

        long result = service.countOpen();

        assertThat(result).isEqualTo(3L);

        verify(paymentDiscrepancyRepository)
                .countByStatus(PaymentDiscrepancyRecordStatus.OPEN);
    }

    @Test
    void changeHandlingStatusChangesStatusAndSavesHistory() {

        Payment payment = org.mockito.Mockito.mock(Payment.class);

        PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(
                payment,
                PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE,
                LocalDateTime.of(2026, 10, 7, 10, 0));

        when(paymentDiscrepancyRepository.findById(10L))
                .thenReturn(Optional.of(discrepancy));

        boolean changed = service.changeHandlingStatus(
                10L,
                PaymentDiscrepancyHandlingStatus.CONFIRMED,
                100L,
                "admin");

        assertTrue(changed);

        assertThat(discrepancy.getHandlingStatus())
                .isEqualTo(
                        PaymentDiscrepancyHandlingStatus.CONFIRMED);

        assertThat(discrepancy.getHandlingStatusUpdatedAt())
                .isNotNull();

        ArgumentCaptor<PaymentDiscrepancyHandlingStatusHistory> captor = ArgumentCaptor.forClass(
                PaymentDiscrepancyHandlingStatusHistory.class);

        verify(paymentDiscrepancyHandlingStatusHistoryRepository)
                .save(captor.capture());

        PaymentDiscrepancyHandlingStatusHistory history = captor.getValue();

        assertThat(history.getPaymentDiscrepancy())
                .isSameAs(discrepancy);

        assertThat(history.getFromStatus())
                .isEqualTo(
                        PaymentDiscrepancyHandlingStatus.UNCONFIRMED);

        assertThat(history.getToStatus())
                .isEqualTo(
                        PaymentDiscrepancyHandlingStatus.CONFIRMED);

        assertThat(history.getChangedByAccountId())
                .isEqualTo(100L);

        assertThat(history.getChangedByUsername())
                .isEqualTo("admin");

        assertThat(history.getChangeEventId())
                .isNotNull();
    }

    @Test
    void changeHandlingStatusDoesNothingWhenStatusIsUnchanged() {

        Payment payment = org.mockito.Mockito.mock(Payment.class);

        PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(
                payment,
                PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE,
                LocalDateTime.of(2026, 10, 7, 10, 0));

        when(paymentDiscrepancyRepository.findById(10L))
                .thenReturn(Optional.of(discrepancy));

        boolean changed = service.changeHandlingStatus(
                10L,
                PaymentDiscrepancyHandlingStatus.UNCONFIRMED,
                100L,
                "admin");

        assertFalse(changed);

        verify(paymentDiscrepancyHandlingStatusHistoryRepository, never())
                .save(any());
    }

    @Test
    void changeHandlingStatusAllowsBackwardTransition() {

        Payment payment = org.mockito.Mockito.mock(Payment.class);

        PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(
                payment,
                PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE,
                LocalDateTime.of(2026, 10, 7, 10, 0));

        discrepancy.changeHandlingStatus(
                PaymentDiscrepancyHandlingStatus.COMPLETED,
                LocalDateTime.of(2026, 10, 7, 10, 10));

        when(paymentDiscrepancyRepository.findById(10L))
                .thenReturn(Optional.of(discrepancy));

        boolean changed = service.changeHandlingStatus(
                10L,
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS,
                100L,
                "admin");

        assertTrue(changed);

        assertThat(discrepancy.getHandlingStatus())
                .isEqualTo(
                        PaymentDiscrepancyHandlingStatus.IN_PROGRESS);

        ArgumentCaptor<PaymentDiscrepancyHandlingStatusHistory> captor = ArgumentCaptor.forClass(
                PaymentDiscrepancyHandlingStatusHistory.class);

        verify(paymentDiscrepancyHandlingStatusHistoryRepository)
                .save(captor.capture());

        assertThat(captor.getValue().getFromStatus())
                .isEqualTo(
                        PaymentDiscrepancyHandlingStatus.COMPLETED);

        assertThat(captor.getValue().getToStatus())
                .isEqualTo(
                        PaymentDiscrepancyHandlingStatus.IN_PROGRESS);
    }

    @Test
    void changeHandlingStatusRejectsNullStatus() {

        assertThatThrownBy(() -> service.changeHandlingStatus(
                10L,
                null,
                100L,
                "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("管理者対応状態は必須です。");

        verify(paymentDiscrepancyRepository, never())
                .findById(any());

        verify(paymentDiscrepancyHandlingStatusHistoryRepository, never())
                .save(any());
    }

    @Test
    void changeHandlingStatusThrowsWhenDiscrepancyDoesNotExist() {

        when(paymentDiscrepancyRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeHandlingStatus(
                999L,
                PaymentDiscrepancyHandlingStatus.CONFIRMED,
                100L,
                "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("決済不整合が見つかりません。id=999");

        verify(paymentDiscrepancyHandlingStatusHistoryRepository, never())
                .save(any());
    }

    @Test
    void findByIdReturnsPaymentDiscrepancy() {

        Payment payment = org.mockito.Mockito.mock(Payment.class);

        PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(
                payment,
                PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE,
                LocalDateTime.of(2026, 10, 7, 10, 0));

        when(paymentDiscrepancyRepository.findByIdWithPaymentAndOrder(10L))
                .thenReturn(Optional.of(discrepancy));

        PaymentDiscrepancy result = service.findById(10L);

        assertThat(result).isSameAs(discrepancy);

        verify(paymentDiscrepancyRepository)
                .findByIdWithPaymentAndOrder(10L);
    }

    @Test
    void findHandlingStatusHistoriesReturnsHistories() {

        PaymentDiscrepancyHandlingStatusHistory history = org.mockito.Mockito.mock(
                PaymentDiscrepancyHandlingStatusHistory.class);

        when(paymentDiscrepancyHandlingStatusHistoryRepository
                .findByPaymentDiscrepancyIdOrderByChangedAtAscIdAsc(10L))
                .thenReturn(java.util.List.of(history));

        java.util.List<PaymentDiscrepancyHandlingStatusHistory> result = service.findHandlingStatusHistories(10L);

        assertThat(result)
                .containsExactly(history);

        verify(paymentDiscrepancyHandlingStatusHistoryRepository)
                .findByPaymentDiscrepancyIdOrderByChangedAtAscIdAsc(10L);
    }

}
