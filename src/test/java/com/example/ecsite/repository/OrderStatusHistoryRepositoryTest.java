package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderStatusHistoryRepositoryTest {

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByOrderIdReturnsHistoriesOldestFirst() {

        User user = createUser("order-status-history-test-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderStatusHistory ordered =
                OrderStatusHistory.create(
                        order,
                        null,
                        OrderStatus.ORDERED,
                        OrderStatusHistoryActorType.USER,
                        user.getId(),
                        user.getUsername());

        orderStatusHistoryRepository.save(ordered);
        entityManager.flush();

        OrderStatusHistory paid =
                OrderStatusHistory.create(
                        order,
                        OrderStatus.ORDERED,
                        OrderStatus.PAID,
                        OrderStatusHistoryActorType.ADMIN,
                        user.getId(),
                        user.getUsername());

        orderStatusHistoryRepository.save(paid);
        entityManager.flush();

        List<OrderStatusHistory> histories =
                orderStatusHistoryRepository
                        .findByOrderIdOrderByChangedAtAscIdAsc(
                                order.getId());

        assertEquals(2, histories.size());

        assertNull(histories.get(0).getFromStatus());
        assertEquals(
                OrderStatus.ORDERED,
                histories.get(0).getToStatus());
        assertEquals(
                OrderStatusHistoryActorType.USER,
                histories.get(0).getChangedByType());

        assertEquals(
                OrderStatus.ORDERED,
                histories.get(1).getFromStatus());
        assertEquals(
                OrderStatus.PAID,
                histories.get(1).getToStatus());
        assertEquals(
                OrderStatusHistoryActorType.ADMIN,
                histories.get(1).getChangedByType());

        assertEquals(
                user.getId(),
                histories.get(1).getChangedByAccountId());
        assertEquals(
                user.getUsername(),
                histories.get(1).getChangedByUsername());
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setRole("ROLE_ADMIN");
        user.setEnabled(true);

        return userRepository.save(user);
    }
}
