package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserEnabledHistory;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserEnabledHistoryRepositoryTest {

    @Autowired
    private UserEnabledHistoryRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void searchReturnsHistoriesNewestFirst() {

        User user = createUser("enabled-history-order-user");

        saveHistory(user, true, false, 100L, "admin1");
        saveHistory(user, false, true, 100L, "admin1");

        Page<UserEnabledHistory> result = search(
                null, null, null, null, 0, 10);

        assertEquals(2, result.getTotalElements());
        assertEquals(true, result.getContent().get(0).isToEnabled());
        assertEquals(false, result.getContent().get(1).isToEnabled());
    }

    @Test
    void searchByUserIdReturnsMatchingHistory() {

        User user1 = createUser("enabled-history-id-user1");
        User user2 = createUser("enabled-history-id-user2");

        saveHistory(user1, true, false, 100L, "admin");
        saveHistory(user2, true, false, 100L, "admin");

        Page<UserEnabledHistory> result = repository.search(
                user1.getId(),
                null,
                null,
                null,
                defaultFrom(),
                defaultTo(),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(user1.getId(), result.getContent().get(0).getUser().getId());
    }

    @Test
    void searchByUsernameUsesPartialMatch() {

        User user1 = createUser("customer-alpha");
        User user2 = createUser("customer-beta");

        saveHistory(user1, true, false, 100L, "admin");
        saveHistory(user2, true, false, 100L, "admin");

        Page<UserEnabledHistory> result = repository.search(
                null,
                "alpha",
                null,
                null,
                defaultFrom(),
                defaultTo(),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals("customer-alpha",
                result.getContent().get(0).getUser().getUsername());
    }

    @Test
    void searchByToEnabledReturnsMatchingHistory() {

        User user = createUser("enabled-history-state-user");

        saveHistory(user, true, false, 100L, "admin");
        saveHistory(user, false, true, 100L, "admin");

        Page<UserEnabledHistory> enabled = repository.search(
                user.getId(),
                null,
                true,
                null,
                defaultFrom(),
                defaultTo(),
                PageRequest.of(0, 10));

        assertEquals(1, enabled.getTotalElements());
        assertEquals(true, enabled.getContent().get(0).isToEnabled());

        Page<UserEnabledHistory> disabled = repository.search(
                user.getId(),
                null,
                false,
                null,
                defaultFrom(),
                defaultTo(),
                PageRequest.of(0, 10));

        assertEquals(1, disabled.getTotalElements());
        assertEquals(false, disabled.getContent().get(0).isToEnabled());
    }

    @Test
    void searchByChangedByUsernameUsesPartialMatch() {

        User user = createUser("enabled-history-admin-user");

        saveHistory(user, true, false, 100L, "AdminAlpha");
        saveHistory(user, false, true, 101L, "AdminBeta");

        Page<UserEnabledHistory> result = repository.search(
                user.getId(),
                null,
                null,
                "Alpha",
                defaultFrom(),
                defaultTo(),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals("AdminAlpha",
                result.getContent().get(0).getChangedByUsername());
    }

    @Test
    void searchWithMultipleConditionsReturnsOnlyMatchingHistory() {

        User user = createUser("enabled-history-multiple");

        saveHistory(user, true, false, 100L, "AdminTarget");
        saveHistory(user, false, true, 101L, "AdminOther");

        Page<UserEnabledHistory> result = repository.search(
                user.getId(),
                "history-multiple",
                false,
                "Target",
                defaultFrom(),
                defaultTo(),
                PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());

        UserEnabledHistory history = result.getContent().get(0);

        assertEquals(user.getId(), history.getUser().getId());
        assertEquals(false, history.isToEnabled());
        assertEquals("AdminTarget", history.getChangedByUsername());
    }

    @Test
    void searchIncludesHistoryAtFromBoundary() {

        User user = createUser("enabled-history-from-boundary");

        UserEnabledHistory history =
                saveHistory(user, true, false, 100L, "admin");

        entityManager.refresh(history);

        LocalDateTime changedAt = history.getChangedAt();

        Page<UserEnabledHistory> result = repository.search(
                user.getId(),
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

        User user = createUser("enabled-history-to-boundary");

        UserEnabledHistory history =
                saveHistory(user, true, false, 100L, "admin");

        entityManager.refresh(history);

        LocalDateTime changedAt = history.getChangedAt();

        Page<UserEnabledHistory> result = repository.search(
                user.getId(),
                null,
                null,
                null,
                changedAt.minusSeconds(1),
                changedAt,
                PageRequest.of(0, 10));

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void searchReturnsEmptyWhenNoHistoryMatches() {

        User user = createUser("enabled-history-no-match");

        saveHistory(user, true, false, 100L, "admin");

        Page<UserEnabledHistory> result = repository.search(
                user.getId(),
                "not-exists",
                true,
                "not-exists",
                defaultFrom(),
                defaultTo(),
                PageRequest.of(0, 10));

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void searchSupportsPagination() {

        User user = createUser("enabled-history-pagination");

        saveHistory(user, true, false, 100L, "admin");
        saveHistory(user, false, true, 100L, "admin");
        saveHistory(user, true, false, 100L, "admin");

        Page<UserEnabledHistory> firstPage = search(
                user.getId(), null, null, null, 0, 2);

        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(0, firstPage.getNumber());

        Page<UserEnabledHistory> secondPage = search(
                user.getId(), null, null, null, 1, 2);

        assertEquals(3, secondPage.getTotalElements());
        assertEquals(1, secondPage.getContent().size());
        assertEquals(1, secondPage.getNumber());
    }

    private Page<UserEnabledHistory> search(
            Long userId,
            String username,
            Boolean toEnabled,
            String changedByUsername,
            int page,
            int size) {

        return repository.search(
                userId,
                username,
                toEnabled,
                changedByUsername,
                defaultFrom(),
                defaultTo(),
                PageRequest.of(page, size));
    }

    private LocalDateTime defaultFrom() {
        return LocalDateTime.of(2000, 1, 1, 0, 0);
    }

    private LocalDateTime defaultTo() {
        return LocalDateTime.of(2100, 1, 1, 0, 0);
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        return userRepository.save(user);
    }

    private UserEnabledHistory saveHistory(
            User user,
            boolean fromEnabled,
            boolean toEnabled,
            Long changedByAccountId,
            String changedByUsername) {

        UserEnabledHistory history = UserEnabledHistory.create(
                user,
                fromEnabled,
                toEnabled,
                changedByAccountId,
                changedByUsername);

        UserEnabledHistory saved = repository.save(history);

        entityManager.flush();

        return saved;
    }
}
