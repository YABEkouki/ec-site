package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;

public interface PaymentDiscrepancyRepository extends JpaRepository<PaymentDiscrepancy, Long> {

    Optional<PaymentDiscrepancy> findByPaymentIdAndLocalStatusAndProviderStatusAndStatus(
            Long paymentId,
            PaymentStatus localStatus,
            PaymentFlowStatus providerStatus,
            PaymentDiscrepancyRecordStatus status);

    List<PaymentDiscrepancy> findByPaymentIdAndStatus(
            Long paymentId,
            PaymentDiscrepancyRecordStatus status);

    @Query(value = """
            SELECT
                pd.id AS discrepancyId,
                o.id AS orderId,
                o.user_id AS userId,
                u.username AS username,
                pd.local_status AS localStatus,
                pd.provider_status AS providerStatus,
                pd.first_detected_at AS firstDetectedAt,
                pd.last_detected_at AS lastDetectedAt,
                pd.detection_count AS detectionCount
            FROM payment_discrepancies pd
            JOIN payments p
              ON p.id = pd.payment_id
            JOIN orders o
              ON o.id = p.order_id
            JOIN users u
              ON u.id = o.user_id
            WHERE pd.status = 'OPEN'
              AND (:orderId IS NULL OR o.id = :orderId)
              AND (:userId IS NULL OR o.user_id = :userId)
              AND (:localStatus IS NULL OR pd.local_status = :localStatus)
              AND (:providerStatus IS NULL OR pd.provider_status = :providerStatus)
            ORDER BY pd.last_detected_at DESC, pd.id DESC
            """, countQuery = """
            SELECT COUNT(*)
            FROM payment_discrepancies pd
            JOIN payments p
              ON p.id = pd.payment_id
            JOIN orders o
              ON o.id = p.order_id
            JOIN users u
              ON u.id = o.user_id
            WHERE pd.status = 'OPEN'
              AND (:orderId IS NULL OR o.id = :orderId)
              AND (:userId IS NULL OR o.user_id = :userId)
              AND (:localStatus IS NULL OR pd.local_status = :localStatus)
              AND (:providerStatus IS NULL OR pd.provider_status = :providerStatus)
            """, nativeQuery = true)
    Page<AdminPaymentDiscrepancyListProjection> searchOpenForAdmin(
            @Param("orderId") Long orderId,
            @Param("userId") Long userId,
            @Param("localStatus") String localStatus,
            @Param("providerStatus") String providerStatus,
            Pageable pageable);

    long countByStatus(PaymentDiscrepancyRecordStatus status);

}
