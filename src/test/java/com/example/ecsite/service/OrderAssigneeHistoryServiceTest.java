package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderAssigneeHistory;
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

        ArgumentCaptor<OrderAssigneeHistory> captor =
                ArgumentCaptor.forClass(OrderAssigneeHistory.class);

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
}
