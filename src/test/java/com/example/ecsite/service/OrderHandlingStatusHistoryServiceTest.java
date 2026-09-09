package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
import com.example.ecsite.form.AdminOrderHandlingStatusHistorySearchForm;
import com.example.ecsite.repository.OrderHandlingStatusHistoryRepository;

@ExtendWith(MockitoExtension.class)
class OrderHandlingStatusHistoryServiceTest {

    @Mock
    private OrderHandlingStatusHistoryRepository repository;

    private OrderHandlingStatusHistoryService service;

    @BeforeEach
    void setUp() {
        service = new OrderHandlingStatusHistoryService(repository);
    }

    @Test
    void recordSavesHandlingStatusHistory() {

        Order order = new Order(10L, 1000);

        service.record(
                order,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                20L,
                "admin");

        ArgumentCaptor<OrderHandlingStatusHistory> captor = ArgumentCaptor.forClass(
                OrderHandlingStatusHistory.class);

        verify(repository).save(captor.capture());

        OrderHandlingStatusHistory history = captor.getValue();

        assertSame(order, history.getOrder());
        assertEquals(
                OrderHandlingStatus.NONE,
                history.getFromStatus());
        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                history.getToStatus());
        assertEquals(
                20L,
                history.getChangedByAccountId());
        assertEquals(
                "admin",
                history.getChangedByUsername());
    }

    @Test
    void findByOrderIdReturnsHistoriesFromRepository() {

        Long orderId = 1L;

        List<OrderHandlingStatusHistory> expected = List.of();

        when(repository
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId))
                .thenReturn(expected);

        List<OrderHandlingStatusHistory> actual = service.findByOrderId(orderId);

        assertSame(expected, actual);

        verify(repository)
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }

    @Test
    void searchConvertsDateRangeAndTrimsUsername() {

        AdminOrderHandlingStatusHistorySearchForm form = new AdminOrderHandlingStatusHistorySearchForm();

        form.setOrderId(10L);
        form.setFromStatus(OrderHandlingStatus.NONE);
        form.setToStatus(OrderHandlingStatus.NEEDS_ACTION);
        form.setChangedByUsername("  AdminUser  ");
        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 3));

        Page<OrderHandlingStatusHistory> expected = new PageImpl<>(List.of());

        when(repository.search(
                eq(10L),
                eq(OrderHandlingStatus.NONE),
                eq(OrderHandlingStatus.NEEDS_ACTION),
                eq("AdminUser"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 4, 0, 0)),
                eq(PageRequest.of(1, 20))))
                .thenReturn(expected);

        Page<OrderHandlingStatusHistory> actual = service.search(form, 1, 20);

        assertSame(expected, actual);

        verify(repository).search(
                10L,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                "AdminUser",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 4, 0, 0),
                PageRequest.of(1, 20));
    }

    @Test
    void searchUsesDefaultDateRangeWhenDatesAreNotSpecified() {

        AdminOrderHandlingStatusHistorySearchForm form = new AdminOrderHandlingStatusHistorySearchForm();

        Page<OrderHandlingStatusHistory> expected = new PageImpl<>(List.of());

        when(repository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<OrderHandlingStatusHistory> actual = service.search(form, 0, 10);

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
    void searchConvertsBlankUsernameToNull() {

        AdminOrderHandlingStatusHistorySearchForm form = new AdminOrderHandlingStatusHistorySearchForm();

        form.setChangedByUsername("   ");

        Page<OrderHandlingStatusHistory> expected = new PageImpl<>(List.of());

        when(repository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<OrderHandlingStatusHistory> actual = service.search(form, 0, 10);

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
    void searchAllUsesUnpagedAndSameSearchConditions() {

        AdminOrderHandlingStatusHistorySearchForm form = new AdminOrderHandlingStatusHistorySearchForm();

        form.setOrderId(10L);
        form.setFromStatus(OrderHandlingStatus.NONE);
        form.setToStatus(OrderHandlingStatus.NEEDS_ACTION);
        form.setChangedByUsername("  AdminUser  ");
        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 3));

        OrderHandlingStatusHistory history = org.mockito.Mockito.mock(OrderHandlingStatusHistory.class);

        List<OrderHandlingStatusHistory> expected = List.of(history);

        when(repository.search(
                eq(10L),
                eq(OrderHandlingStatus.NONE),
                eq(OrderHandlingStatus.NEEDS_ACTION),
                eq("AdminUser"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 4, 0, 0)),
                eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(expected));

        List<OrderHandlingStatusHistory> actual = service.searchAll(form);

        assertEquals(expected, actual);

        verify(repository).search(
                10L,
                OrderHandlingStatus.NONE,
                OrderHandlingStatus.NEEDS_ACTION,
                "AdminUser",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 4, 0, 0),
                Pageable.unpaged());
    }

}
