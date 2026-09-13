package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderAssigneeHistory;
import com.example.ecsite.form.AdminOrderAssigneeHistoryFilter;
import com.example.ecsite.form.AdminOrderAssigneeHistorySearchForm;
import com.example.ecsite.repository.OrderAssigneeHistoryRepository;

@ExtendWith(MockitoExtension.class)
class OrderAssigneeHistoryServiceTest {

    @Mock
    private OrderAssigneeHistoryRepository repository;

    private OrderAssigneeHistoryService service;

    @BeforeEach
    void setUp() {
        service = new OrderAssigneeHistoryService(repository);
    }

    @Test
    void recordSavesAssigneeHistory() {

        Order order = new Order(10L, 1000);
        UUID changeEventId = UUID.randomUUID();

        service.record(
                order,
                11L,
                "admin-before",
                12L,
                "admin-after",
                20L,
                "operator",
                changeEventId);

        ArgumentCaptor<OrderAssigneeHistory> captor = ArgumentCaptor.forClass(OrderAssigneeHistory.class);

        verify(repository).save(captor.capture());

        OrderAssigneeHistory history = captor.getValue();

        assertSame(order, history.getOrder());
        assertEquals(11L, history.getFromAdminAccountId());
        assertEquals("admin-before", history.getFromAdminUsername());
        assertEquals(12L, history.getToAdminAccountId());
        assertEquals("admin-after", history.getToAdminUsername());
        assertEquals(20L, history.getChangedByAccountId());
        assertEquals("operator", history.getChangedByUsername());
        assertEquals(changeEventId, history.getChangeEventId());
    }

    @Test
    void findByOrderIdReturnsHistoriesFromRepository() {

        Long orderId = 1L;
        List<OrderAssigneeHistory> expected = List.of();

        when(repository.findByOrderIdOrderByChangedAtAscIdAsc(orderId))
                .thenReturn(expected);

        List<OrderAssigneeHistory> actual = service.findByOrderId(orderId);

        assertSame(expected, actual);

        verify(repository)
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }

    @Test
    void searchConvertsConditionsForSpecificAdmins() {

        AdminOrderAssigneeHistorySearchForm form = new AdminOrderAssigneeHistorySearchForm();

        form.setOrderId(10L);

        form.setFromAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.SPECIFIC);
        form.setFromAdminAccountId(11L);

        form.setToAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.SPECIFIC);
        form.setToAdminAccountId(12L);

        form.setChangedByUsername("  AdminUser  ");

        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 3));

        Page<OrderAssigneeHistory> expected = new PageImpl<>(List.of());

        when(repository.search(
                eq(10L),
                eq(false),
                eq(11L),
                eq(false),
                eq(12L),
                eq("AdminUser"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 4, 0, 0)),
                eq(PageRequest.of(1, 20))))
                .thenReturn(expected);

        Page<OrderAssigneeHistory> actual = service.search(form, 1, 20);

        assertSame(expected, actual);

        verify(repository).search(
                10L,
                false,
                11L,
                false,
                12L,
                "AdminUser",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 4, 0, 0),
                PageRequest.of(1, 20));
    }

    @Test
    void searchConvertsUnassignedFilters() {

        AdminOrderAssigneeHistorySearchForm form = new AdminOrderAssigneeHistorySearchForm();

        form.setFromAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.UNASSIGNED);

        form.setToAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.UNASSIGNED);

        Page<OrderAssigneeHistory> expected = new PageImpl<>(List.of());

        when(repository.search(
                eq(null),
                eq(true),
                eq(null),
                eq(true),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<OrderAssigneeHistory> actual = service.search(form, 0, 10);

        assertSame(expected, actual);

        verify(repository).search(
                null,
                true,
                null,
                true,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));
    }

    @Test
    void searchUsesNoAssigneeConditionForAllFilters() {

        AdminOrderAssigneeHistorySearchForm form = new AdminOrderAssigneeHistorySearchForm();

        form.setFromAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.ALL);
        form.setFromAdminAccountId(11L);

        form.setToAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.ALL);
        form.setToAdminAccountId(12L);

        Page<OrderAssigneeHistory> expected = new PageImpl<>(List.of());

        when(repository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<OrderAssigneeHistory> actual = service.search(form, 0, 10);

        assertSame(expected, actual);

        verify(repository).search(
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));
    }

    @Test
    void searchConvertsBlankUsernameToNull() {

        AdminOrderAssigneeHistorySearchForm form = new AdminOrderAssigneeHistorySearchForm();

        form.setChangedByUsername("   ");

        Page<OrderAssigneeHistory> expected = new PageImpl<>(List.of());

        when(repository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<OrderAssigneeHistory> actual = service.search(form, 0, 10);

        assertSame(expected, actual);

        verify(repository).search(
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0),
                PageRequest.of(0, 10));
    }

    @Test
    void searchAllReturnsAllHistoriesMatchingSearchConditions() {

        AdminOrderAssigneeHistorySearchForm form = new AdminOrderAssigneeHistorySearchForm();

        form.setOrderId(10L);

        form.setFromAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.SPECIFIC);
        form.setFromAdminAccountId(20L);

        form.setToAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.UNASSIGNED);

        form.setChangedByUsername("admin");

        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 13));

        OrderAssigneeHistory history = org.mockito.Mockito.mock(
                OrderAssigneeHistory.class);

        Page<OrderAssigneeHistory> expected = new PageImpl<>(List.of(history));

        when(repository.search(
                eq(10L),
                eq(false),
                eq(20L),
                eq(true),
                eq(null),
                eq("admin"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 14, 0, 0)),
                eq(Pageable.unpaged())))
                .thenReturn(expected);

        List<OrderAssigneeHistory> actual = service.searchAll(form);

        assertEquals(
                List.of(history),
                actual);

        verify(repository).search(
                10L,
                false,
                20L,
                true,
                null,
                "admin",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 14, 0, 0),
                Pageable.unpaged());
    }

}
