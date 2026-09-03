package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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

        OrderStatusHistory ordered = OrderStatusHistory.create(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        orderStatusHistoryRepository.save(ordered);
        entityManager.flush();

        OrderStatusHistory paid = OrderStatusHistory.create(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                user.getUsername());

        orderStatusHistoryRepository.save(paid);
        entityManager.flush();

        List<OrderStatusHistory> histories = orderStatusHistoryRepository
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

    @Test
    void searchByOrderIdReturnsHistoriesNewestFirst() {

        User user = createUser("order-status-history-search-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderStatusHistory ordered = OrderStatusHistory.create(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        orderStatusHistoryRepository.save(ordered);
        entityManager.flush();

        OrderStatusHistory paid = OrderStatusHistory.create(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "AdminSearchUser");

        orderStatusHistoryRepository.save(paid);
        entityManager.flush();

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(2, result.getTotalElements());
        assertEquals(2, result.getContent().size());

        assertEquals(
                OrderStatus.PAID,
                result.getContent().get(0).getToStatus());

        assertEquals(
                OrderStatus.ORDERED,
                result.getContent().get(1).getToStatus());
    }

    @Test
    void searchByFromStatusReturnsMatchingHistory() {

        User user = createUser("history-from-status-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        saveHistory(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "AdminFromStatus");

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                OrderStatus.ORDERED,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                OrderStatus.PAID,
                result.getContent().get(0).getToStatus());
    }

    @Test
    void searchByToStatusReturnsMatchingHistory() {

        User user = createUser("history-to-status-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        saveHistory(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "AdminToStatus");

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                OrderStatus.PAID,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                OrderStatus.ORDERED,
                result.getContent().get(0).getFromStatus());
    }

    @Test
    void searchByChangedByTypeReturnsMatchingHistory() {

        User user = createUser("history-actor-type-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        saveHistory(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "AdminActorType");

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                null,
                OrderStatusHistoryActorType.ADMIN,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                OrderStatusHistoryActorType.ADMIN,
                result.getContent().get(0).getChangedByType());
    }

    @Test
    void searchByUsernameUsesCaseSensitivePartialMatch() {

        User user = createUser("history-username-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                "AdminSearchUser");

        saveHistory(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "adminsearchuser");

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                null,
                null,
                "Search",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(
                "AdminSearchUser",
                result.getContent().get(0).getChangedByUsername());
    }

    @Test
    void searchWithMultipleConditionsReturnsOnlyMatchingHistory() {

        User user = createUser("history-multiple-condition-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        saveHistory(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "AdminMultiple");

        saveHistory(
                order,
                OrderStatus.PAID,
                OrderStatus.SHIPPED,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "AdminMultiple");

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                "Multiple",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        OrderStatusHistory history = result.getContent().get(0);

        assertEquals(
                OrderStatus.ORDERED,
                history.getFromStatus());

        assertEquals(
                OrderStatus.PAID,
                history.getToStatus());

        assertEquals(
                OrderStatusHistoryActorType.ADMIN,
                history.getChangedByType());

        assertEquals(
                "AdminMultiple",
                history.getChangedByUsername());
    }

    @Test
    void searchIncludesHistoryAtFromBoundary() {

        User user = createUser("history-from-boundary-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderStatusHistory saved = saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        entityManager.refresh(saved);

        LocalDateTime changedAt = saved.getChangedAt();

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                null,
                null,
                null,
                changedAt,
                changedAt.plusSeconds(1),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void searchExcludesHistoryAtToExclusiveBoundary() {

        User user = createUser("history-to-boundary-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderStatusHistory saved = saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        entityManager.refresh(saved);

        LocalDateTime changedAt = saved.getChangedAt();

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                null,
                null,
                null,
                changedAt.minusSeconds(1),
                changedAt,
                PageRequest.of(0, 10));

        assertEquals(0, result.getTotalElements());
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setRole("ROLE_ADMIN");
        user.setEnabled(true);

        return userRepository.save(user);
    }

    private OrderStatusHistory saveHistory(
            Order order,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderStatusHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername) {

        OrderStatusHistory history = OrderStatusHistory.create(
                order,
                fromStatus,
                toStatus,
                changedByType,
                changedByAccountId,
                changedByUsername);

        OrderStatusHistory saved = orderStatusHistoryRepository.save(history);

        entityManager.flush();

        return saved;
    }

    @Test
    void searchReturnsEmptyWhenNoHistoryMatches() {

        User user = createUser("history-no-match-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                user.getUsername());

        Page<OrderStatusHistory> result = orderStatusHistoryRepository.search(
                order.getId(),
                OrderStatus.PAID,
                OrderStatus.SHIPPED,
                OrderStatusHistoryActorType.ADMIN,
                "NotExists",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(0, result.getTotalElements());
        assertEquals(0, result.getContent().size());
    }

    @Test
    void searchSupportsPagination() {

        User user = createUser("history-pagination-user");
        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                null,
                OrderStatus.ORDERED,
                OrderStatusHistoryActorType.USER,
                user.getId(),
                "PaginationUser");

        saveHistory(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "PaginationAdmin");

        saveHistory(
                order,
                OrderStatus.PAID,
                OrderStatus.SHIPPED,
                OrderStatusHistoryActorType.ADMIN,
                user.getId(),
                "PaginationAdmin");

        Page<OrderStatusHistory> firstPage = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 2));

        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(0, firstPage.getNumber());

        Page<OrderStatusHistory> secondPage = orderStatusHistoryRepository.search(
                order.getId(),
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(1, 2));

        assertEquals(3, secondPage.getTotalElements());
        assertEquals(1, secondPage.getContent().size());
        assertEquals(1, secondPage.getNumber());
    }

}
