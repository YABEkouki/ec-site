package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentProvider;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);

    Optional<Payment> findByProviderAndProviderPaymentId(
            PaymentProvider provider,
            String providerPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from Payment p
            where p.id = :paymentId
            """)
    Optional<Payment> findByIdForUpdate(
            @Param("paymentId") Long paymentId);
}
