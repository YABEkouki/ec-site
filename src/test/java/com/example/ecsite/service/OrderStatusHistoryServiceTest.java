package com.example.ecsite.service;

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

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.form.AdminOrderStatusHistorySearchForm;
import com.example.ecsite.repository.OrderStatusHistoryRepository;

@ExtendWith(MockitoExtension.class)
class OrderStatusHistoryServiceTest {

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    private OrderStatusHistoryService orderStatusHistoryService;

    @BeforeEach
    void setUp() {
        orderStatusHistoryService = new OrderStatusHistoryService(
                orderStatusHistoryRepository);
    }

    @Test
    void recordSavesOrderStatusHistory() {

        Order order = new Order(10L, 1000);

        orderStatusHistoryService.record(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                20L,
                "admin");

        verify(orderStatusHistoryRepository)
                .save(
                        org.mockito.ArgumentMatchers.any(
                                OrderStatusHistory.class));
    }

    @Test
    void recordSavesTrimmedInternalNote() {

        Order order = new Order(10L, 1000);

        orderStatusHistoryService.record(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                20L,
                "admin",
                "  入金を確認したため  ");

        verify(orderStatusHistoryRepository)
                .save(org.mockito.ArgumentMatchers.argThat(
                        history -> "入金を確認したため"
                                .equals(history.getInternalNote())));
    }

    @Test
    void findByOrderIdReturnsHistoriesInRepositoryOrder() {

        Long orderId = 1L;

        OrderStatusHistory first = mock(OrderStatusHistory.class);
        OrderStatusHistory second = mock(OrderStatusHistory.class);

        List<OrderStatusHistory> expected = List.of(first, second);

        when(orderStatusHistoryRepository
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId))
                .thenReturn(expected);

        List<OrderStatusHistory> actual = orderStatusHistoryService.findByOrderId(orderId);

        org.junit.jupiter.api.Assertions
                .assertSame(expected, actual);

        verify(orderStatusHistoryRepository)
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }

    @Test
    void searchConvertsDateRangeAndTrimsUsername() {

        AdminOrderStatusHistorySearchForm form = new AdminOrderStatusHistorySearchForm();

        form.setOrderId(10L);
        form.setFromStatus(OrderStatus.ORDERED);
        form.setToStatus(OrderStatus.PAID);
        form.setChangedByType(OrderStatusHistoryActorType.ADMIN);
        form.setChangedByUsername("  AdminUser  ");
        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 3));

        Page<OrderStatusHistory> expected = new PageImpl<>(List.of());

        when(orderStatusHistoryRepository.search(
                eq(10L),
                eq(OrderStatus.ORDERED),
                eq(OrderStatus.PAID),
                eq(OrderStatusHistoryActorType.ADMIN),
                eq("AdminUser"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 4, 0, 0)),
                eq(PageRequest.of(1, 20))))
                .thenReturn(expected);

        Page<OrderStatusHistory> actual = orderStatusHistoryService.search(
                form,
                1,
                20);

        org.junit.jupiter.api.Assertions
                .assertSame(expected, actual);

        verify(orderStatusHistoryRepository).search(
                10L,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                "AdminUser",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 4, 0, 0),
                PageRequest.of(1, 20));
    }

    @Test
    void searchUsesDefaultDateRangeWhenDatesAreNotSpecified() {

        AdminOrderStatusHistorySearchForm form = new AdminOrderStatusHistorySearchForm();

        Page<OrderStatusHistory> expected = new PageImpl<>(List.of());

        when(orderStatusHistoryRepository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<OrderStatusHistory> actual = orderStatusHistoryService.search(
                form,
                0,
                10);

        org.junit.jupiter.api.Assertions
                .assertSame(expected, actual);

        verify(orderStatusHistoryRepository).search(
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

        AdminOrderStatusHistorySearchForm form = new AdminOrderStatusHistorySearchForm();

        form.setChangedByUsername("   ");

        Page<OrderStatusHistory> expected = new PageImpl<>(List.of());

        when(orderStatusHistoryRepository.search(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)),
                eq(LocalDateTime.of(2100, 1, 1, 0, 0)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(expected);

        Page<OrderStatusHistory> actual = orderStatusHistoryService.search(
                form,
                0,
                10);

        org.junit.jupiter.api.Assertions
                .assertSame(expected, actual);

        verify(orderStatusHistoryRepository).search(
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
    void searchAllReturnsAllMatchingHistoriesWithoutPaging() {

        AdminOrderStatusHistorySearchForm form = new AdminOrderStatusHistorySearchForm();

        form.setOrderId(10L);
        form.setFromStatus(OrderStatus.ORDERED);
        form.setToStatus(OrderStatus.PAID);
        form.setChangedByType(OrderStatusHistoryActorType.ADMIN);
        form.setChangedByUsername("  AdminUser  ");
        form.setFrom(LocalDate.of(2026, 9, 1));
        form.setTo(LocalDate.of(2026, 9, 3));

        OrderStatusHistory history1 = mock(OrderStatusHistory.class);
        OrderStatusHistory history2 = mock(OrderStatusHistory.class);

        List<OrderStatusHistory> expected = List.of(history1, history2);

        Page<OrderStatusHistory> expectedPage = new PageImpl<>(expected);

        when(orderStatusHistoryRepository.search(
                eq(10L),
                eq(OrderStatus.ORDERED),
                eq(OrderStatus.PAID),
                eq(OrderStatusHistoryActorType.ADMIN),
                eq("AdminUser"),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 4, 0, 0)),
                eq(Pageable.unpaged())))
                .thenReturn(expectedPage);

        List<OrderStatusHistory> actual = orderStatusHistoryService.searchAll(form);

        org.junit.jupiter.api.Assertions
                .assertEquals(expected, actual);

        verify(orderStatusHistoryRepository).search(
                10L,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                "AdminUser",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 4, 0, 0),
                Pageable.unpaged());
    }

    @Test
    void recordConvertsBlankInternalNoteToNull() {

        Order order = new Order(10L, 1000);

        orderStatusHistoryService.record(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                20L,
                "admin",
                "   ");

        verify(orderStatusHistoryRepository)
                .save(org.mockito.ArgumentMatchers.argThat(
                        history -> history.getInternalNote() == null));
    }

    @Test
    void recordWithoutInternalNoteSavesNull() {

        Order order = new Order(10L, 1000);

        orderStatusHistoryService.record(
                order,
                OrderStatus.ORDERED,
                OrderStatus.PAID,
                OrderStatusHistoryActorType.ADMIN,
                20L,
                "admin");

        verify(orderStatusHistoryRepository)
                .save(org.mockito.ArgumentMatchers.argThat(
                        history -> history.getInternalNote() == null));
    }

}
