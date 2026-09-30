package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.OrderContentChangeCharge;
import com.example.ecsite.entity.OrderContentChangeHistory;
import com.example.ecsite.entity.OrderContentChangeItem;
import com.example.ecsite.repository.OrderContentChangeHistoryRepository;

@ExtendWith(MockitoExtension.class)
class OrderContentChangeHistoryServiceTest {

    @Mock
    private OrderContentChangeHistoryRepository repository;

    private OrderContentChangeHistoryService service;

    @BeforeEach
    void setUp() {
        service = new OrderContentChangeHistoryService(repository);
    }

    @Test
    void findByOrderIdReturnsHistoriesAndInitializesDetails() {

        Long orderId = 1L;

        OrderContentChangeHistory first =
                mock(OrderContentChangeHistory.class);

        OrderContentChangeHistory second =
                mock(OrderContentChangeHistory.class);

        List<OrderContentChangeItem> firstItems = List.of();
        List<OrderContentChangeCharge> firstCharges = List.of();
        List<OrderContentChangeItem> secondItems = List.of();
        List<OrderContentChangeCharge> secondCharges = List.of();

        when(first.getItems()).thenReturn(firstItems);
        when(first.getCharges()).thenReturn(firstCharges);
        when(second.getItems()).thenReturn(secondItems);
        when(second.getCharges()).thenReturn(secondCharges);

        List<OrderContentChangeHistory> expected =
                List.of(first, second);

        when(repository
                .findByOrderIdOrderByChangedAtDescIdDesc(orderId))
                .thenReturn(expected);

        List<OrderContentChangeHistory> actual =
                service.findByOrderId(orderId);

        assertSame(expected, actual);

        verify(repository)
                .findByOrderIdOrderByChangedAtDescIdDesc(orderId);

        verify(first).getItems();
        verify(first).getCharges();
        verify(second).getItems();
        verify(second).getCharges();
    }
}
