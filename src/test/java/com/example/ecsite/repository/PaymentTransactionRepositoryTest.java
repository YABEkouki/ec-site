package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentTransactionRepositoryTest {

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savePersistsTransaction() {

        Payment payment = createPayment(
                "payment-transaction-save-user");

        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 10, 0);

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.AUTHORIZE,
                10_000,
                2,
                "payment-transaction-save-key",
                createdAt);

        PaymentTransaction saved = paymentTransactionRepository.save(transaction);

        entityManager.flush();
        entityManager.clear();

        PaymentTransaction loaded = paymentTransactionRepository.findById(saved.getId())
                .orElseThrow();

        assertEquals(payment.getId(), loaded.getPayment().getId());
        assertEquals(
                PaymentTransactionType.AUTHORIZE,
                loaded.getTransactionType());
        assertEquals(
                PaymentTransactionStatus.PENDING,
                loaded.getStatus());
        assertEquals(10_000, loaded.getAmount());
        assertEquals(2, loaded.getOrderContentRevision());
        assertEquals(
                "payment-transaction-save-key",
                loaded.getIdempotencyKey());
        assertEquals(createdAt, loaded.getCreatedAt());
    }

    @Test
    void findByPaymentIdReturnsTransactionsOldestFirst() {

        Payment payment = createPayment(
                "payment-transaction-find-user");

        PaymentTransaction first = paymentTransactionRepository.save(
                new PaymentTransaction(
                        payment,
                        PaymentTransactionType.AUTHORIZE,
                        10_000,
                        0,
                        "payment-transaction-first-key",
                        LocalDateTime.of(
                                2026, 10, 1, 10, 0)));

        PaymentTransaction second = paymentTransactionRepository.save(
                new PaymentTransaction(
                        payment,
                        PaymentTransactionType.CAPTURE,
                        10_000,
                        0,
                        "payment-transaction-second-key",
                        LocalDateTime.of(
                                2026, 10, 1, 11, 0)));

        entityManager.flush();
        entityManager.clear();

        List<PaymentTransaction> transactions = paymentTransactionRepository
                .findByPaymentIdOrderByCreatedAtAscIdAsc(
                        payment.getId());

        assertEquals(2, transactions.size());
        assertEquals(first.getId(), transactions.get(0).getId());
        assertEquals(second.getId(), transactions.get(1).getId());
    }

    @Test
    void findByIdempotencyKeyReturnsTransaction() {

        Payment payment = createPayment(
                "payment-idempotency-find-user");

        PaymentTransaction saved = paymentTransactionRepository.save(
                new PaymentTransaction(
                        payment,
                        PaymentTransactionType.AUTHORIZE,
                        10_000,
                        0,
                        "payment-idempotency-find-key",
                        LocalDateTime.of(
                                2026, 10, 1, 10, 0)));

        entityManager.flush();
        entityManager.clear();

        var result = paymentTransactionRepository.findByIdempotencyKey(
                "payment-idempotency-find-key");

        assertTrue(result.isPresent());
        assertEquals(saved.getId(), result.orElseThrow().getId());
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {

        Payment payment = createPayment(
                "payment-idempotency-duplicate-user");

        paymentTransactionRepository.save(
                new PaymentTransaction(
                        payment,
                        PaymentTransactionType.AUTHORIZE,
                        10_000,
                        0,
                        "duplicate-idempotency-key",
                        LocalDateTime.of(
                                2026, 10, 1, 10, 0)));

        entityManager.flush();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> {
                    paymentTransactionRepository.save(
                            new PaymentTransaction(
                                    payment,
                                    PaymentTransactionType.CAPTURE,
                                    10_000,
                                    0,
                                    "duplicate-idempotency-key",
                                    LocalDateTime.of(
                                            2026, 10, 1, 11, 0)));

                    entityManager.flush();
                });
    }

    private Payment createPayment(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        user = userRepository.save(user);

        Order order = orderRepository.save(
                new Order(
                        user.getId(),
                        10_000,
                        LocalDateTime.of(2026, 10, 1, 9, 0),
                        LocalDateTime.of(2026, 10, 1, 13, 0)));

        return paymentRepository.save(
                new Payment(
                        order,
                        PaymentProvider.MOCK,
                        PaymentMethod.CARD,
                        10_000,
                        LocalDateTime.of(2026, 10, 1, 9, 5)));
    }
}
