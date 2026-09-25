package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserEnabledHistory;
import com.example.ecsite.form.AdminUserEnabledHistorySearchForm;
import com.example.ecsite.repository.UserEnabledHistoryRepository;

@ExtendWith(MockitoExtension.class)
class UserEnabledHistoryServiceTest {

    @Mock
    private UserEnabledHistoryRepository repository;

    private UserEnabledHistoryService service;

    @BeforeEach
    void setUp() {
        service = new UserEnabledHistoryService(repository);
    }

    @Test
    void recordSavesUserEnabledHistory() {

        User user = new User();
        user.setUsername("customer");

        service.record(
                user,
                true,
                false,
                20L,
                "admin");

        verify(repository).save(argThat(history ->
                history.getUser() == user
                        && history.isFromEnabled()
                        && !history.isToEnabled()
                        && history.getChangedByAccountId().equals(20L)
                        && history.getChangedByUsername().equals("admin")));
    }

    @Test
    void searchConvertsConditionsAndDateRange() {

        AdminUserEnabledHistorySearchForm form =
                new AdminUserEnabledHistorySearchForm();

        form.setUserId(10L);
        form.setUsername("  customer  ");
        form.setToEnabled(false);
        form.setChangedByUsername("  AdminUser  ");
        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 3));

        Page<UserEnabledHistory> expected =
                new PageImpl<>(List.of());

        when(repository.search(
                eq(10L),
                eq("customer"),
                eq(false),
                eq("AdminUser"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 4, 0, 0)),
                eq(PageRequest.of(1, 20))))
                .thenReturn(expected);

        Page<UserEnabledHistory> actual =
                service.search(form, 1, 20);

        assertSame(expected, actual);

        verify(repository).search(
                10L,
                "customer",
                false,
                "AdminUser",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 4, 0, 0),
                PageRequest.of(1, 20));
    }

    @Test
    void searchUsesDefaultDateRangeWhenDatesAreNotSpecified() {

        AdminUserEnabledHistorySearchForm form =
                new AdminUserEnabledHistorySearchForm();

        Page<UserEnabledHistory> expected =
                new PageImpl<>(List.of());

        when(repository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<UserEnabledHistory> actual =
                service.search(form, 0, 10);

        assertSame(expected, actual);

        verify(repository).search(
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));
    }

    @Test
    void searchConvertsBlankUsernamesToNull() {

        AdminUserEnabledHistorySearchForm form =
                new AdminUserEnabledHistorySearchForm();

        form.setUsername("   ");
        form.setChangedByUsername("   ");

        Page<UserEnabledHistory> expected =
                new PageImpl<>(List.of());

        when(repository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<UserEnabledHistory> actual =
                service.search(form, 0, 10);

        assertSame(expected, actual);

        verify(repository).search(
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));
    }

    @Test
    void searchAllReturnsAllMatchingHistoriesWithoutPaging() {

        AdminUserEnabledHistorySearchForm form =
                new AdminUserEnabledHistorySearchForm();

        form.setUserId(10L);
        form.setUsername(" customer ");
        form.setToEnabled(true);
        form.setChangedByUsername(" AdminUser ");
        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 3));

        UserEnabledHistory history1 =
                mock(UserEnabledHistory.class);
        UserEnabledHistory history2 =
                mock(UserEnabledHistory.class);

        List<UserEnabledHistory> expected =
                List.of(history1, history2);

        Page<UserEnabledHistory> expectedPage =
                new PageImpl<>(expected);

        when(repository.search(
                eq(10L),
                eq("customer"),
                eq(true),
                eq("AdminUser"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 4, 0, 0)),
                eq(Pageable.unpaged())))
                .thenReturn(expectedPage);

        List<UserEnabledHistory> actual =
                service.searchAll(form);

        assertEquals(expected, actual);

        verify(repository).search(
                10L,
                "customer",
                true,
                "AdminUser",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 4, 0, 0),
                Pageable.unpaged());
    }
}
