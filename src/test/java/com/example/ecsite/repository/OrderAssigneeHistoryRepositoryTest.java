package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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

        OrderAssigneeHistory assigned = OrderAssigneeHistory.create(
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

        OrderAssigneeHistory changed = OrderAssigneeHistory.create(
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

        List<OrderAssigneeHistory> histories = repository.findByOrderIdOrderByChangedAtAscIdAsc(
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

        OrderAssigneeHistory history = OrderAssigneeHistory.create(
                order,
                10L,
                "admin01",
                null,
                null,
                20L,
                "operator",
                changeEventId);

        OrderAssigneeHistory saved = repository.save(history);

        entityManager.flush();
        entityManager.clear();

        OrderAssigneeHistory reloaded = repository.findById(saved.getId())
                .orElseThrow();

        assertEquals(10L, reloaded.getFromAdminAccountId());
        assertEquals("admin01", reloaded.getFromAdminUsername());
        assertNull(reloaded.getToAdminAccountId());
        assertNull(reloaded.getToAdminUsername());
        assertEquals(changeEventId, reloaded.getChangeEventId());
        assertNotNull(reloaded.getChangedAt());
    }

    @Test
    void searchFiltersByOrderId() {

        User user = createUser(
                "order-assignee-history-search-user");

        Order firstOrder = orderRepository.save(
                new Order(user.getId(), 1000));

        Order secondOrder = orderRepository.save(
                new Order(user.getId(), 2000));

        repository.save(
                OrderAssigneeHistory.create(
                        firstOrder,
                        null,
                        null,
                        10L,
                        "admin01",
                        20L,
                        "operator",
                        UUID.randomUUID()));

        repository.save(
                OrderAssigneeHistory.create(
                        secondOrder,
                        10L,
                        "admin01",
                        11L,
                        "admin02",
                        20L,
                        "operator",
                        UUID.randomUUID()));

        entityManager.flush();
        entityManager.clear();

        Page<OrderAssigneeHistory> result = repository.search(
                secondOrder.getId(),
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                secondOrder.getId(),
                result.getContent().get(0).getOrder().getId());
    }

    @Test
    void searchFiltersByFromUnassigned() {

        User user = createUser(
                "order-assignee-history-from-unassigned-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        null,
                        null,
                        10L,
                        "admin01",
                        20L,
                        "operator",
                        UUID.randomUUID()));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        11L,
                        "admin02",
                        20L,
                        "operator",
                        UUID.randomUUID()));

        entityManager.flush();
        entityManager.clear();

        Page<OrderAssigneeHistory> result = repository.search(
                null,
                true,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertNull(result.getContent().get(0).getFromAdminAccountId());
    }

    @Test
    void searchFiltersByToUnassigned() {

        User user = createUser(
                "order-assignee-history-to-unassigned-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        null,
                        null,
                        20L,
                        "operator",
                        UUID.randomUUID()));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        11L,
                        "admin02",
                        20L,
                        "operator",
                        UUID.randomUUID()));

        entityManager.flush();
        entityManager.clear();

        Page<OrderAssigneeHistory> result = repository.search(
                null,
                null,
                null,
                true,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertNull(result.getContent().get(0).getToAdminAccountId());
    }

    @Test
    void searchFiltersByToAdminAccountId() {

        User user = createUser(
                "order-assignee-history-to-admin-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        20L,
                        "admin02",
                        30L,
                        "operator",
                        UUID.randomUUID()));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        21L,
                        "admin03",
                        30L,
                        "operator",
                        UUID.randomUUID()));

        entityManager.flush();
        entityManager.clear();

        Page<OrderAssigneeHistory> result = repository.search(
                null,
                null,
                null,
                false,
                21L,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                21L,
                result.getContent().get(0).getToAdminAccountId());
    }

    @Test
    void searchFiltersByChangedByUsername() {

        User user = createUser(
                "order-assignee-history-operator-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        null,
                        null,
                        10L,
                        "admin01",
                        20L,
                        "operator-alpha",
                        UUID.randomUUID()));

        repository.save(
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        11L,
                        "admin02",
                        21L,
                        "manager-beta",
                        UUID.randomUUID()));

        entityManager.flush();
        entityManager.clear();

        Page<OrderAssigneeHistory> result = repository.search(
                null,
                null,
                null,
                null,
                null,
                "operator",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                "operator-alpha",
                result.getContent().get(0).getChangedByUsername());
    }

    @Test
    void searchFiltersByChangedAtRange() {

        User user = createUser(
                "order-assignee-history-date-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderAssigneeHistory before = repository.save(
                OrderAssigneeHistory.create(
                        order,
                        null,
                        null,
                        10L,
                        "admin01",
                        20L,
                        "operator",
                        UUID.randomUUID()));

        entityManager.flush();

        updateChangedAt(
                before,
                LocalDateTime.of(2026, 9, 9, 23, 59));

        OrderAssigneeHistory inRange = repository.save(
                OrderAssigneeHistory.create(
                        order,
                        10L,
                        "admin01",
                        11L,
                        "admin02",
                        20L,
                        "operator",
                        UUID.randomUUID()));

        entityManager.flush();

        updateChangedAt(
                inRange,
                LocalDateTime.of(2026, 9, 10, 12, 0));

        OrderAssigneeHistory after = repository.save(
                OrderAssigneeHistory.create(
                        order,
                        11L,
                        "admin02",
                        null,
                        null,
                        20L,
                        "operator",
                        UUID.randomUUID()));

        entityManager.flush();

        updateChangedAt(
                after,
                LocalDateTime.of(2026, 9, 11, 0, 0));

        Page<OrderAssigneeHistory> result = repository.search(
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 9, 10, 0, 0),
                LocalDateTime.of(2026, 9, 11, 0, 0),
                PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                inRange.getId(),
                result.getContent().get(0).getId());
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");

        return userRepository.save(user);
    }

    private void updateChangedAt(
            OrderAssigneeHistory history,
            LocalDateTime changedAt) {

        entityManager.createNativeQuery("""
                UPDATE order_assignee_histories
                SET changed_at = :changedAt
                WHERE id = :id
                """)
                .setParameter("changedAt", changedAt)
                .setParameter("id", history.getId())
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();
    }

}
