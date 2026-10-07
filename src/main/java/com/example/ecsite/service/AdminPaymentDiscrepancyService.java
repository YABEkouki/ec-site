package com.example.ecsite.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;

@Service
public class AdminPaymentDiscrepancyService {

    private final PaymentDiscrepancyRepository paymentDiscrepancyRepository;

    public AdminPaymentDiscrepancyService(
            PaymentDiscrepancyRepository paymentDiscrepancyRepository) {
        this.paymentDiscrepancyRepository = paymentDiscrepancyRepository;
    }

    @Transactional(readOnly = true)
    public Page<AdminPaymentDiscrepancyListProjection> search(
            AdminPaymentDiscrepancySearchForm form,
            int page,
            int size) {

        String localStatus = form.getLocalStatus() == null
                ? null
                : form.getLocalStatus().name();

        String providerStatus = form.getProviderStatus() == null
                ? null
                : form.getProviderStatus().name();

        return paymentDiscrepancyRepository.searchOpenForAdmin(
                form.getOrderId(),
                form.getUserId(),
                localStatus,
                providerStatus,
                PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public long countOpen() {
        return paymentDiscrepancyRepository.countByStatus(
                PaymentDiscrepancyRecordStatus.OPEN);
    }

}
