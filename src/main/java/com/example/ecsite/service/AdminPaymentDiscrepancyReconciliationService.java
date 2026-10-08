package com.example.ecsite.service;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRetryFacade;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditResult;

@Service
public class AdminPaymentDiscrepancyReconciliationService {

    private final PaymentDiscrepancyRepository discrepancyRepository;
    private final PaymentDiscrepancyAuditRetryFacade auditItemService;

    public AdminPaymentDiscrepancyReconciliationService(
            PaymentDiscrepancyRepository discrepancyRepository,
            PaymentDiscrepancyAuditRetryFacade auditItemService) {
        this.discrepancyRepository = discrepancyRepository;
        this.auditItemService = auditItemService;
    }

    // 外側のトランザクションを作らず、共通Facadeが試行ごとのTxを完了する。
    public PaymentDiscrepancyAuditResult reconcile(Long discrepancyId) {
        PaymentDiscrepancy discrepancy = discrepancyRepository
                .findByIdWithPaymentAndOrder(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済不整合が見つかりません。id=" + discrepancyId));

        return auditItemService.auditWithResult(discrepancy.getPayment().getId());
    }
}
