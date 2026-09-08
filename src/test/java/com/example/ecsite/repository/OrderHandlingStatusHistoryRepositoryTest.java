package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderHandlingStatusHistoryRepositoryTest {

    @Autowired
    private OrderHandlingStatusHistoryRepository repository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByOrderIdReturnsHistoriesOldestFirst() {

        User user = createUser(
                "order-handling-status-history-test-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderHandlingStatusHistory needsAction = OrderHandlingStatusHistory.create(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                user.getUsername());

        repository.save(needsAction);
        entityManager.flush();

        OrderHandlingStatusHistory inProgress = OrderHandlingStatusHistory.create(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                user.getId(),
                user.getUsername());

        repository.save(inProgress);
        entityManager.flush();

        List<OrderHandlingStatusHistory> histories = repository
                .findByOrderIdOrderByChangedAtAscIdAsc(
                        order.getId());

        assertEquals(2, histories.size());

        assertEquals(
                OrderHandlingStatus.NONE,
                histories.get(0).getFromStatus());

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                histories.get(0).getToStatus());

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                histories.get(1).getFromStatus());

        assertEquals(
                OrderHandlingStatus.IN_PROGRESS,
                histories.get(1).getToStatus());

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
