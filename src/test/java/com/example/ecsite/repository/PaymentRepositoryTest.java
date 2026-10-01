package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savePersistsPayment() {

        Order order = createOrder("payment-save-user");

        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 10, 0);

        Payment payment = new Payment(
                order,
                PaymentProvider.MOCK,
                PaymentMethod.CARD,
                10_000,
                createdAt);

        Payment saved = paymentRepository.save(payment);

        entityManager.flush();
        entityManager.clear();

        Payment loaded = paymentRepository.findById(saved.getId())
                .orElseThrow();

        assertEquals(order.getId(), loaded.getOrder().getId());
        assertEquals(PaymentProvider.MOCK, loaded.getProvider());
        assertEquals(PaymentMethod.CARD, loaded.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, loaded.getStatus());
        assertEquals(10_000, loaded.getAmount());
        assertEquals(createdAt, loaded.getCreatedAt());
        assertEquals(createdAt, loaded.getUpdatedAt());
    }

    @Test
    void findByOrderIdReturnsPaymentsOldestFirst() {

        Order order = createOrder("payment-order-find-user");

        Payment first = paymentRepository.save(
                new Payment(
                        order,
                        PaymentProvider.MOCK,
                        PaymentMethod.CARD,
                        10_000,
                        LocalDateTime.of(2026, 10, 1, 10, 0)));

        Payment second = paymentRepository.save(
                new Payment(
                        order,
                        PaymentProvider.MOCK,
                        PaymentMethod.CARD,
                        12_000,
                        LocalDateTime.of(2026, 10, 1, 11, 0)));

        entityManager.flush();
        entityManager.clear();

        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(
                order.getId());

        assertEquals(2, payments.size());
        assertEquals(first.getId(), payments.get(0).getId());
        assertEquals(second.getId(), payments.get(1).getId());
    }

    @Test
    void findByOrderIdDoesNotReturnPaymentsForOtherOrders() {

        Order targetOrder = createOrder("payment-target-order-user");
        Order otherOrder = createOrder("payment-other-order-user");

        Payment target = paymentRepository.save(
                new Payment(
                        targetOrder,
                        PaymentProvider.MOCK,
                        PaymentMethod.CARD,
                        10_000,
                        LocalDateTime.of(2026, 10, 1, 10, 0)));

        paymentRepository.save(
                new Payment(
                        otherOrder,
                        PaymentProvider.MOCK,
                        PaymentMethod.CARD,
                        20_000,
                        LocalDateTime.of(2026, 10, 1, 11, 0)));

        entityManager.flush();
        entityManager.clear();

        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(
                targetOrder.getId());

        assertEquals(1, payments.size());
        assertEquals(target.getId(), payments.get(0).getId());
    }

    private Order createOrder(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        user = userRepository.save(user);

        return orderRepository.save(
                new Order(
                        user.getId(),
                        10_000,
                        LocalDateTime.of(2026, 10, 1, 9, 0),
                        LocalDateTime.of(2026, 10, 1, 13, 0)));
    }
}
