package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.PaymentFlowStatus;

public interface PaymentDiscrepancyRepository
        extends JpaRepository<PaymentDiscrepancy, Long> {

    Optional<PaymentDiscrepancy>
            findByPaymentIdAndLocalStatusAndProviderStatusAndStatus(
                    Long paymentId,
                    PaymentStatus localStatus,
                    PaymentFlowStatus providerStatus,
                    PaymentDiscrepancyRecordStatus status);

    List<PaymentDiscrepancy>
            findByPaymentIdAndStatus(
                    Long paymentId,
                    PaymentDiscrepancyRecordStatus status);
}
