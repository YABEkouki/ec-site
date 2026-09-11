package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderAssigneeHistory;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderAssigneeHistoryRepositoryTest {

    @Autowired
    private OrderAssigneeHistoryRepository repository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByOrderIdReturnsHistoriesOldestFirst() {

        User user = createUser(
                "order-assignee-history-test-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        UUID firstEventId = UUID.randomUUID();

        OrderAssigneeHistory assigned =
                OrderAssigneeHistory.create(
                        order,
                        null,
                        null,
                        10L,
                        "admin01",
                        20L,
                        "operator",
                        firstEventId);

        repository.save(assigned);
        entityManager.flush();

        UUID secondEventId = UUID.randomUUID();

        OrderAssigneeHistory changed =
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        11L,
                        "admin02",
                        20L,
                        "operator",
                        secondEventId);

        repository.save(changed);
        entityManager.flush();

        entityManager.clear();

        List<OrderAssigneeHistory> histories =
                repository.findByOrderIdOrderByChangedAtAscIdAsc(
                        order.getId());

        assertEquals(2, histories.size());

        OrderAssigneeHistory first = histories.get(0);

        assertNull(first.getFromAdminAccountId());
        assertNull(first.getFromAdminUsername());
        assertEquals(10L, first.getToAdminAccountId());
        assertEquals("admin01", first.getToAdminUsername());
        assertEquals(20L, first.getChangedByAccountId());
        assertEquals("operator", first.getChangedByUsername());
        assertEquals(firstEventId, first.getChangeEventId());
        assertNotNull(first.getChangedAt());

        OrderAssigneeHistory second = histories.get(1);

        assertEquals(10L, second.getFromAdminAccountId());
        assertEquals("admin01", second.getFromAdminUsername());
        assertEquals(11L, second.getToAdminAccountId());
        assertEquals("admin02", second.getToAdminUsername());
        assertEquals(secondEventId, second.getChangeEventId());
        assertNotNull(second.getChangedAt());
    }

    @Test
    void saveAllowsUnassignedAfterChange() {

        User user = createUser(
                "order-assignee-history-clear-test-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        UUID changeEventId = UUID.randomUUID();

        OrderAssigneeHistory history =
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        null,
                        null,
                        20L,
                        "operator",
                        changeEventId);

        OrderAssigneeHistory saved =
                repository.save(history);

        entityManager.flush();
        entityManager.clear();

        OrderAssigneeHistory reloaded =
                repository.findById(saved.getId())
                        .orElseThrow();

        assertEquals(10L, reloaded.getFromAdminAccountId());
        assertEquals("admin01", reloaded.getFromAdminUsername());
        assertNull(reloaded.getToAdminAccountId());
        assertNull(reloaded.getToAdminUsername());
        assertEquals(changeEventId, reloaded.getChangeEventId());
        assertNotNull(reloaded.getChangedAt());
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");

        return userRepository.save(user);
    }
}
