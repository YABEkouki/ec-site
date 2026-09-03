package com.example.ecsite.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
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
}
