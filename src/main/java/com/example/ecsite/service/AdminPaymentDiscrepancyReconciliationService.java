package com.example.ecsite.service;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditItemService;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditResult;

@Service
public class AdminPaymentDiscrepancyReconciliationService {

    private final PaymentDiscrepancyRepository discrepancyRepository;
    private final PaymentDiscrepancyAuditItemService auditItemService;

    public AdminPaymentDiscrepancyReconciliationService(
            PaymentDiscrepancyRepository discrepancyRepository,
            PaymentDiscrepancyAuditItemService auditItemService) {
        this.discrepancyRepository = discrepancyRepository;
        this.auditItemService = auditItemService;
    }

    // 外側のトランザクションを作らず、監査ItemServiceの境界で実行する。
    public PaymentDiscrepancyAuditResult reconcile(Long discrepancyId) {
        PaymentDiscrepancy discrepancy = discrepancyRepository
                .findByIdWithPaymentAndOrder(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "決済不整合が見つかりません。id=" + discrepancyId));

        return auditItemService.auditWithResult(discrepancy.getPayment().getId());
    }
}
