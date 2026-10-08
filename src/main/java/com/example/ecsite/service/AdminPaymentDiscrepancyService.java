package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatusHistory;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.repository.PaymentDiscrepancyHandlingStatusHistoryRepository;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;

@Service
public class AdminPaymentDiscrepancyService {

    private final PaymentDiscrepancyRepository paymentDiscrepancyRepository;
    private final PaymentDiscrepancyHandlingStatusHistoryRepository paymentDiscrepancyHandlingStatusHistoryRepository;

    public AdminPaymentDiscrepancyService(
            PaymentDiscrepancyRepository paymentDiscrepancyRepository,
            PaymentDiscrepancyHandlingStatusHistoryRepository paymentDiscrepancyHandlingStatusHistoryRepository) {

        this.paymentDiscrepancyRepository = paymentDiscrepancyRepository;
        this.paymentDiscrepancyHandlingStatusHistoryRepository = paymentDiscrepancyHandlingStatusHistoryRepository;
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

        String handlingStatus = form.getHandlingStatus() == null
                ? null
                : form.getHandlingStatus().name();

        return paymentDiscrepancyRepository.searchOpenForAdmin(
                form.getOrderId(),
                form.getUserId(),
                localStatus,
                providerStatus,
                handlingStatus,
                PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public long countOpen() {
        return paymentDiscrepancyRepository.countByStatus(
                PaymentDiscrepancyRecordStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public PaymentDiscrepancy findById(Long discrepancyId) {
        return paymentDiscrepancyRepository
                .findByIdWithPaymentAndOrder(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済不整合が見つかりません。id=" + discrepancyId));
    }

    @Transactional(readOnly = true)
    public List<PaymentDiscrepancyHandlingStatusHistory> findHandlingStatusHistories(
            Long discrepancyId) {

        return paymentDiscrepancyHandlingStatusHistoryRepository
                .findByPaymentDiscrepancyIdOrderByChangedAtAscIdAsc(
                        discrepancyId);
    }

    @Transactional
    public boolean changeHandlingStatus(
            Long discrepancyId,
            Long expectedVersion,
            PaymentDiscrepancyHandlingStatus newStatus,
            Long changedByAccountId,
            String changedByUsername) {

        if (newStatus == null) {
            throw new IllegalArgumentException("管理者対応状態は必須です。");
        }

        if (expectedVersion == null || expectedVersion < 0) {
            throw new IllegalArgumentException("対象データのバージョンが不正です。");
        }

        PaymentDiscrepancy discrepancy = paymentDiscrepancyRepository
                .findById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済不整合が見つかりません。id=" + discrepancyId));

        if (!expectedVersion.equals(discrepancy.getVersion())) {
            throw new PaymentDiscrepancyConflictException(discrepancyId);
        }

        PaymentDiscrepancyHandlingStatus currentStatus = discrepancy.getHandlingStatus();

        if (currentStatus == newStatus) {
            return false;
        }

        LocalDateTime changedAt = LocalDateTime.now();
        UUID changeEventId = UUID.randomUUID();

        discrepancy.changeHandlingStatus(newStatus, changedAt);

        paymentDiscrepancyHandlingStatusHistoryRepository.save(
                PaymentDiscrepancyHandlingStatusHistory.create(
                        discrepancy,
                        currentStatus,
                        newStatus,
                        changedByAccountId,
                        changedByUsername,
                        changeEventId));

        return true;
    }

}
