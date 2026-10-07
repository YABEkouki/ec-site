package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;

@ExtendWith(MockitoExtension.class)
class AdminPaymentDiscrepancyServiceTest {

    @Mock
    private PaymentDiscrepancyRepository paymentDiscrepancyRepository;

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

        Page<AdminPaymentDiscrepancyListProjection> result = new PageImpl<>(java.util.List.of());

        when(paymentDiscrepancyRepository.searchOpenForAdmin(
                123L,
                456L,
                PaymentStatus.PENDING.name(),
                PaymentFlowStatus.REQUIRES_CAPTURE.name(),
                PageRequest.of(2, 20)))
                .thenReturn(result);

        service.search(form, 2, 20);

        verify(paymentDiscrepancyRepository)
                .searchOpenForAdmin(
                        123L,
                        456L,
                        PaymentStatus.PENDING.name(),
                        PaymentFlowStatus.REQUIRES_CAPTURE.name(),
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
                PageRequest.of(0, 10)))
                .thenReturn(result);

        service.search(form, 0, 10);

        verify(paymentDiscrepancyRepository)
                .searchOpenForAdmin(
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

}
