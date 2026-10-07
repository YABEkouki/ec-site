package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.User;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentDiscrepancyRepositoryTest {

    @Autowired
    private PaymentDiscrepancyRepository paymentDiscrepancyRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void searchOpenForAdminFiltersByUserIdAndReturnsCustomerInformation() {

        User targetUser = createUser(
                "payment-discrepancy-admin-target");

        User otherUser = createUser(
                "payment-discrepancy-admin-other");

        Payment targetPayment = createPayment(targetUser);
        Payment otherPayment = createPayment(otherUser);

        LocalDateTime detectedAt = LocalDateTime.of(2026, 10, 6, 12, 0);

        PaymentDiscrepancy targetDiscrepancy = paymentDiscrepancyRepository.save(
                new PaymentDiscrepancy(
                        targetPayment,
                        PaymentStatus.PENDING,
                        PaymentFlowStatus.REQUIRES_CAPTURE,
                        detectedAt));

        paymentDiscrepancyRepository.save(
                new PaymentDiscrepancy(
                        otherPayment,
                        PaymentStatus.PENDING,
                        PaymentFlowStatus.REQUIRES_CAPTURE,
                        detectedAt.plusMinutes(1)));

        entityManager.flush();
        entityManager.clear();

        Page<AdminPaymentDiscrepancyListProjection> result = paymentDiscrepancyRepository.searchOpenForAdmin(
                null,
                targetUser.getId(),
                null,
                null,
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        AdminPaymentDiscrepancyListProjection item = result.getContent().get(0);

        assertEquals(
                targetDiscrepancy.getId(),
                item.getDiscrepancyId());

        assertEquals(
                targetPayment.getOrder().getId(),
                item.getOrderId());

        assertEquals(
                targetUser.getId(),
                item.getUserId());

        assertEquals(
                "payment-discrepancy-admin-target",
                item.getUsername());

        assertEquals(
                PaymentStatus.PENDING.name(),
                item.getLocalStatus());

        assertEquals(
                PaymentFlowStatus.REQUIRES_CAPTURE.name(),
                item.getProviderStatus());

        assertEquals(
                detectedAt,
                item.getFirstDetectedAt());

        assertEquals(
                detectedAt,
                item.getLastDetectedAt());

        assertEquals(
                1,
                item.getDetectionCount());
    }

    @Test
    void searchOpenForAdminDoesNotReturnResolvedDiscrepancy() {

        User user = createUser(
                "payment-discrepancy-admin-resolved");

        Payment payment = createPayment(user);

        LocalDateTime detectedAt = LocalDateTime.of(2026, 10, 6, 12, 0);

        PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(
                payment,
                PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE,
                detectedAt);

        discrepancy.resolve(
                detectedAt.plusMinutes(10));

        paymentDiscrepancyRepository.save(discrepancy);

        entityManager.flush();
        entityManager.clear();

        Page<AdminPaymentDiscrepancyListProjection> result = paymentDiscrepancyRepository.searchOpenForAdmin(
                payment.getOrder().getId(),
                null,
                null,
                null,
                PageRequest.of(0, 10));

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void searchOpenForAdminFiltersByAllConditions() {

        User targetUser = createUser(
                "payment-discrepancy-admin-all-target");

        User otherUser = createUser(
                "payment-discrepancy-admin-all-other");

        Payment targetPayment = createPayment(targetUser);
        Payment otherPayment = createPayment(otherUser);

        LocalDateTime detectedAt = LocalDateTime.of(2026, 10, 6, 13, 0);

        PaymentDiscrepancy targetDiscrepancy = paymentDiscrepancyRepository.save(
                new PaymentDiscrepancy(
                        targetPayment,
                        PaymentStatus.PENDING,
                        PaymentFlowStatus.REQUIRES_CAPTURE,
                        detectedAt));

        paymentDiscrepancyRepository.save(
                new PaymentDiscrepancy(
                        otherPayment,
                        PaymentStatus.AUTHORIZED,
                        PaymentFlowStatus.SUCCEEDED,
                        detectedAt.plusMinutes(1)));

        entityManager.flush();
        entityManager.clear();

        Page<AdminPaymentDiscrepancyListProjection> result = paymentDiscrepancyRepository.searchOpenForAdmin(
                targetPayment.getOrder().getId(),
                targetUser.getId(),
                PaymentStatus.PENDING.name(),
                PaymentFlowStatus.REQUIRES_CAPTURE.name(),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        assertEquals(
                targetDiscrepancy.getId(),
                result.getContent().get(0).getDiscrepancyId());
    }

    @Test
    void searchOpenForAdminReturnsLatestDetectedFirst() {

        User firstUser = createUser(
                "payment-discrepancy-admin-sort-first");

        User secondUser = createUser(
                "payment-discrepancy-admin-sort-second");

        Payment firstPayment = createPayment(firstUser);
        Payment secondPayment = createPayment(secondUser);

        PaymentDiscrepancy first = paymentDiscrepancyRepository.save(
                new PaymentDiscrepancy(
                        firstPayment,
                        PaymentStatus.PENDING,
                        PaymentFlowStatus.REQUIRES_CAPTURE,
                        LocalDateTime.of(2026, 10, 6, 13, 0)));

        PaymentDiscrepancy second = paymentDiscrepancyRepository.save(
                new PaymentDiscrepancy(
                        secondPayment,
                        PaymentStatus.PENDING,
                        PaymentFlowStatus.REQUIRES_CAPTURE,
                        LocalDateTime.of(2026, 10, 6, 14, 0)));

        entityManager.flush();
        entityManager.clear();

        Page<AdminPaymentDiscrepancyListProjection> result = paymentDiscrepancyRepository.searchOpenForAdmin(
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10));

        assertEquals(2, result.getTotalElements());

        assertEquals(
                second.getId(),
                result.getContent().get(0).getDiscrepancyId());

        assertEquals(
                first.getId(),
                result.getContent().get(1).getDiscrepancyId());
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        return userRepository.saveAndFlush(user);
    }

    private Payment createPayment(User user) {

        Order order = orderRepository.saveAndFlush(
                new Order(
                        user.getId(),
                        10_000,
                        LocalDateTime.of(2026, 10, 6, 10, 0),
                        LocalDateTime.of(2026, 10, 6, 14, 0)));

        return paymentRepository.saveAndFlush(
                new Payment(
                        order,
                        PaymentProvider.PAYJP,
                        PaymentMethod.CARD,
                        10_000,
                        LocalDateTime.of(2026, 10, 6, 10, 1)));
    }
}
