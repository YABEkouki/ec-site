package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatusHistory;

public interface PaymentDiscrepancyHandlingStatusHistoryRepository
        extends JpaRepository<PaymentDiscrepancyHandlingStatusHistory, Long> {

    List<PaymentDiscrepancyHandlingStatusHistory>
            findByPaymentDiscrepancyIdOrderByChangedAtAscIdAsc(Long paymentDiscrepancyId);
}
