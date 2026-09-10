package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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

    @Test
    void searchByOrderIdReturnsHistoriesNewestFirst() {

        User user = createUser(
                "handling-history-search-order-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                "HandlingAdmin1");

        saveHistory(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                user.getId(),
                "HandlingAdmin2");

        Page<OrderHandlingStatusHistory> result = repository.search(
                order.getId(),
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(2, result.getTotalElements());

        assertEquals(
                OrderHandlingStatus.IN_PROGRESS,
                result.getContent().get(0).getToStatus());

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                result.getContent().get(1).getToStatus());
    }

    @Test
    void searchByFromStatusReturnsMatchingHistory() {

        User user = createUser(
                "handling-history-from-status-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                "HandlingFromAdmin");

        saveHistory(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                user.getId(),
                "HandlingFromAdmin");

        Page<OrderHandlingStatusHistory> result = repository.search(
                order.getId(),
                OrderHandlingStatus.NEEDS_ACTION,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        assertEquals(
                OrderHandlingStatus.IN_PROGRESS,
                result.getContent().get(0).getToStatus());
    }

    @Test
    void searchByToStatusReturnsMatchingHistory() {

        User user = createUser(
                "handling-history-to-status-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                "HandlingToAdmin");

        saveHistory(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                user.getId(),
                "HandlingToAdmin");

        Page<OrderHandlingStatusHistory> result = repository.search(
                order.getId(),
                null,
                OrderHandlingStatus.NEEDS_ACTION,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        assertEquals(
                OrderHandlingStatus.NONE,
                result.getContent().get(0).getFromStatus());
    }

    @Test
    void searchByUsernameUsesCaseSensitivePartialMatch() {

        User user = createUser(
                "handling-history-username-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                "AdminSearchUser");

        saveHistory(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                user.getId(),
                "adminsearchuser");

        Page<OrderHandlingStatusHistory> result = repository.search(
                order.getId(),
                null,
                null,
                "Search",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        assertEquals(
                "AdminSearchUser",
                result.getContent()
                        .get(0)
                        .getChangedByUsername());
    }

    @Test
    void searchWithMultipleConditionsReturnsOnlyMatchingHistory() {

        User user = createUser(
                "handling-history-multiple-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                "AdminMultiple");

        saveHistory(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                user.getId(),
                "AdminMultiple");

        saveHistory(
                order,
                OrderHandlingStatus.IN_PROGRESS,
                OrderHandlingStatus.RESOLVED,
                user.getId(),
                "OtherAdmin");

        Page<OrderHandlingStatusHistory> result = repository.search(
                order.getId(),
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                "Multiple",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        OrderHandlingStatusHistory history = result.getContent().get(0);

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                history.getFromStatus());

        assertEquals(
                OrderHandlingStatus.IN_PROGRESS,
                history.getToStatus());

        assertEquals(
                "AdminMultiple",
                history.getChangedByUsername());
    }

    @Test
    void searchIncludesHistoryAtFromBoundary() {

        User user = createUser(
                "handling-history-from-boundary-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderHandlingStatusHistory saved = saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                user.getUsername());

        entityManager.refresh(saved);

        LocalDateTime changedAt = saved.getChangedAt();

        Page<OrderHandlingStatusHistory> result = repository.search(
                order.getId(),
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

        User user = createUser(
                "handling-history-to-boundary-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderHandlingStatusHistory saved = saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                user.getUsername());

        entityManager.refresh(saved);

        LocalDateTime changedAt = saved.getChangedAt();

        Page<OrderHandlingStatusHistory> result = repository.search(
                order.getId(),
                null,
                null,
                null,
                changedAt.minusSeconds(1),
                changedAt,
                PageRequest.of(0, 10));

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void searchSupportsPagination() {

        User user = createUser(
                "handling-history-pagination-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        saveHistory(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                user.getId(),
                "PaginationAdmin");

        saveHistory(
                order,
                OrderHandlingStatus.NEEDS_ACTION,
                OrderHandlingStatus.IN_PROGRESS,
                user.getId(),
                "PaginationAdmin");

        saveHistory(
                order,
                OrderHandlingStatus.IN_PROGRESS,
                OrderHandlingStatus.RESOLVED,
                user.getId(),
                "PaginationAdmin");

        Page<OrderHandlingStatusHistory> firstPage = repository.search(
                order.getId(),
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

        Page<OrderHandlingStatusHistory> secondPage = repository.search(
                order.getId(),
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

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        return userRepository.save(user);
    }

    private OrderHandlingStatusHistory saveHistory(
            Order order,
            OrderHandlingStatus fromStatus,
            OrderHandlingStatus toStatus,
            Long changedByAccountId,
            String changedByUsername) {

        OrderHandlingStatusHistory history = OrderHandlingStatusHistory.create(
                order,
                fromStatus,
                toStatus,
                changedByAccountId,
                changedByUsername);

        OrderHandlingStatusHistory saved = repository.save(history);

        entityManager.flush();

        return saved;
    }

}
