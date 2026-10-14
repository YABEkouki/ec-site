package com.example.ecsite.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;

public interface PaymentTransactionRepository
        extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByPaymentIdOrderByCreatedAtAscIdAsc(Long paymentId);

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTransaction> findByPaymentIdAndTransactionTypeAndStatus(
            Long paymentId,
            PaymentTransactionType transactionType,
            PaymentTransactionStatus status);

    List<PaymentTransaction> findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
            PaymentTransactionStatus status,
            LocalDateTime createdAt,
            Pageable pageable);

    // Exact String.isBlank semantics, including controls and Unicode whitespace.
    String PROVIDER_ID_BLANK_CHARACTERS = java.util.stream.IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
            .filter(Character::isWhitespace)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();

    String RECONCILIATION_FILTER = """
            from PaymentTransaction t join t.payment p
            where t.status = com.example.ecsite.entity.PaymentTransactionStatus.PENDING
              and t.createdAt < :cutoff
              and p.provider = com.example.ecsite.entity.PaymentProvider.PAYJP
              and p.paymentMethod = com.example.ecsite.entity.PaymentMethod.CARD
              and ((:missing = true and (p.providerPaymentId is null or function('translate', p.providerPaymentId, :blankCharacters, '') = ''))
                or (:missing = false and p.providerPaymentId is not null and function('translate', p.providerPaymentId, :blankCharacters, '') <> ''))
            """;

    @Transactional(readOnly = true)
    @Query("select max(t.id) " + RECONCILIATION_FILTER)
    Long reconciliationUpperId(@Param("cutoff") LocalDateTime cutoff, @Param("missing") boolean missing, @Param("blankCharacters") String blankCharacters);

    /** Scalar IDs only; the read transaction ends before any provider request. */
    @Transactional(readOnly = true)
    @Query("select t.id " + RECONCILIATION_FILTER + " and t.id > :afterId and t.id <= :upperId order by t.id asc")
    List<Long> reconciliationIds(@Param("cutoff") LocalDateTime cutoff, @Param("missing") boolean missing,
            @Param("afterId") long afterId, @Param("upperId") long upperId,
            @Param("blankCharacters") String blankCharacters, Pageable pageable);

}
