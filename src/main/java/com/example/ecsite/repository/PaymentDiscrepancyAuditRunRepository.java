package com.example.ecsite.repository;

import java.util.Optional;
import java.util.List;
import com.example.ecsite.repository.projection.PaymentDiscrepancyAuditRunningSummaryProjection;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.example.ecsite.entity.PaymentDiscrepancyAuditExecutionType;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRun;
import jakarta.persistence.LockModeType;

public interface PaymentDiscrepancyAuditRunRepository extends JpaRepository<PaymentDiscrepancyAuditRun, Long> {
    Optional<PaymentDiscrepancyAuditRun> findFirstByExecutionTypeOrderByStartedAtDescIdDesc(
        PaymentDiscrepancyAuditExecutionType executionType);

    Optional<PaymentDiscrepancyAuditRun> findFirstByExecutionTypeAndStatusOrderByFinishedAtDescIdDesc(
        PaymentDiscrepancyAuditExecutionType executionType,
        PaymentDiscrepancyAuditRunStatus status);

    @Query("""
        select r from PaymentDiscrepancyAuditRun r
        where r.executionType = :executionType
          and (:status is null or r.status = :status)
          and (cast(:from as timestamp) is null or r.startedAt >= :from)
          and (cast(:toExclusive as timestamp) is null or r.startedAt < :toExclusive)
        order by r.startedAt desc, r.id desc
        """)
    Page<PaymentDiscrepancyAuditRun> searchForAdmin(
        @Param("executionType") PaymentDiscrepancyAuditExecutionType executionType,
        @Param("status") PaymentDiscrepancyAuditRunStatus status,
        @Param("from") Instant from,
        @Param("toExclusive") Instant toExclusive,
        Pageable pageable);

    @Query("""
        select count(r) as count, min(r.startedAt) as oldestStartedAt
        from PaymentDiscrepancyAuditRun r
        where r.executionType = :executionType and r.status = 'RUNNING'
          and r.startedAt <= :cutoff
        """)
    PaymentDiscrepancyAuditRunningSummaryProjection summarizeLongRunning(
        @Param("executionType") PaymentDiscrepancyAuditExecutionType executionType,
        @Param("cutoff") Instant cutoff);

    @Query("""
        select r from PaymentDiscrepancyAuditRun r
        where r.executionType = :executionType and r.status <> 'RUNNING'
        order by r.finishedAt desc, r.id desc
        """)
    List<PaymentDiscrepancyAuditRun> findRecentFinishedForMonitoring(
        @Param("executionType") PaymentDiscrepancyAuditExecutionType executionType, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentDiscrepancyAuditRun r where r.id = :id")
    Optional<PaymentDiscrepancyAuditRun> findForFinish(@Param("id") Long id);
}
