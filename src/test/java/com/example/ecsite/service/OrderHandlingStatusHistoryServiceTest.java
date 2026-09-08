package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
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

        ArgumentCaptor<OrderHandlingStatusHistory> captor =
                ArgumentCaptor.forClass(
                        OrderHandlingStatusHistory.class);

        verify(repository).save(captor.capture());

        OrderHandlingStatusHistory history =
                captor.getValue();

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

        List<OrderHandlingStatusHistory> expected =
                List.of();

        when(repository
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId))
                .thenReturn(expected);

        List<OrderHandlingStatusHistory> actual =
                service.findByOrderId(orderId);

        assertSame(expected, actual);

        verify(repository)
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }
}
