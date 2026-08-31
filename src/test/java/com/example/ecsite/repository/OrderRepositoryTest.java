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
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

        @Autowired
        private OrderRepository orderRepository;

        @Autowired
        private EntityManager entityManager;

        @Autowired
        private UserRepository userRepository;

        private static final LocalDateTime SEARCH_FROM = LocalDateTime.of(1970, 1, 1, 0, 0);

        private static final LocalDateTime SEARCH_TO = LocalDateTime.of(9999, 12, 31, 0, 0);

        @Test
        void searchFiltersByUserId() {

                User firstUser = createUser("order-search-user-1");
                User secondUser = createUser("order-search-user-2");

                createOrder(
                                firstUser.getId(),
                                LocalDateTime.of(2026, 8, 10, 10, 0));

                Order target = createOrder(
                                secondUser.getId(),
                                LocalDateTime.of(2026, 8, 11, 10, 0));

                Page<Order> result = orderRepository.search(
                                null,
                                secondUser.getId(),
                                SEARCH_FROM,
                                SEARCH_TO,
                                null,
                                PageRequest.of(0, 20));

                assertEquals(1, result.getTotalElements());
                assertEquals(target.getId(), result.getContent().get(0).getId());
        }

        @Test
        void searchFiltersByOrderId() {

                User user = createUser("order-search-user-3");

                Order target = createOrder(
                                user.getId(),
                                LocalDateTime.of(2026, 8, 11, 10, 0));

                Page<Order> result = orderRepository.search(
                                target.getId(),
                                null,
                                SEARCH_FROM,
                                SEARCH_TO,
                                null,
                                PageRequest.of(0, 20));

                assertEquals(1, result.getTotalElements());
                assertEquals(target.getId(), result.getContent().get(0).getId());
        }

        @Test
        void searchFiltersByOrderedAtRange() {

                User user = createUser("order-search-range-user");

                createOrder(
                                user.getId(),
                                LocalDateTime.of(2026, 8, 9, 23, 59));

                Order firstInRange = createOrder(
                                user.getId(),
                                LocalDateTime.of(2026, 8, 10, 0, 0));

                Order lastInRange = createOrder(
                                user.getId(),
                                LocalDateTime.of(2026, 8, 20, 23, 59));

                createOrder(
                                user.getId(),
                                LocalDateTime.of(2026, 8, 21, 0, 0));

                Page<Order> result = orderRepository.search(
                                null,
                                user.getId(),
                                LocalDateTime.of(2026, 8, 10, 0, 0),
                                LocalDateTime.of(2026, 8, 21, 0, 0),
                                null,
                                PageRequest.of(0, 20));

                assertEquals(2, result.getTotalElements());
                assertEquals(lastInRange.getId(), result.getContent().get(0).getId());
                assertEquals(firstInRange.getId(), result.getContent().get(1).getId());
        }

        @Test
        void searchFiltersByStatus() {

                User user = createUser("order-search-user-5");

                Order paidOrder = createOrder(
                                user.getId(),
                                LocalDateTime.of(2026, 8, 11, 10, 0));

                paidOrder.markAsPaid();
                entityManager.flush();

                Page<Order> result = orderRepository.search(
                                null,
                                null,
                                SEARCH_FROM,
                                SEARCH_TO,
                                OrderStatus.PAID,
                                PageRequest.of(0, 20));

                assertEquals(1, result.getTotalElements());
                assertEquals(paidOrder.getId(), result.getContent().get(0).getId());
        }

        @Test
        void searchCombinesConditionsWithAnd() {

                User targetUser = createUser("order-search-target");
                User otherUser = createUser("order-search-other");

                Order target = createOrder(
                                targetUser.getId(),
                                LocalDateTime.of(2026, 8, 15, 11, 0));

                Order paidOtherUser = createOrder(
                                otherUser.getId(),
                                LocalDateTime.of(2026, 8, 15, 12, 0));

                target.markAsPaid();
                paidOtherUser.markAsPaid();
                entityManager.flush();

                Page<Order> result = orderRepository.search(
                                null,
                                targetUser.getId(),
                                LocalDateTime.of(2026, 8, 15, 0, 0),
                                LocalDateTime.of(2026, 8, 16, 0, 0),
                                OrderStatus.PAID,
                                PageRequest.of(0, 20));

                assertEquals(1, result.getTotalElements());
                assertEquals(target.getId(), result.getContent().get(0).getId());
        }

        private Order createOrder(
                        Long userId,
                        LocalDateTime orderedAt) {

                Order order = new Order(userId, 1000);
                order.setOrderedAt(orderedAt);

                Order saved = orderRepository.save(order);
                entityManager.flush();

                return saved;
        }

        private User createUser(String username) {

                User user = new User();
                user.setUsername(username);
                user.setPassword("password");
                user.setRole("ROLE_USER");
                user.setEnabled(true);

                User saved = userRepository.save(user);
                entityManager.flush();

                return saved;
        }

}