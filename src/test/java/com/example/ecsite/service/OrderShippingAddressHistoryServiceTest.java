package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderShippingAddressHistory;
import com.example.ecsite.entity.OrderShippingAddressHistoryActorType;
import com.example.ecsite.repository.OrderShippingAddressHistoryRepository;

@ExtendWith(MockitoExtension.class)
class OrderShippingAddressHistoryServiceTest {

    @Mock
    private OrderShippingAddressHistoryRepository repository;

    private OrderShippingAddressHistoryService service;

    @BeforeEach
    void setUp() {
        service = new OrderShippingAddressHistoryService(repository);
    }

    @Test
    void recordSavesShippingAddressHistory() {

        Order order = new Order(
                10L,
                1000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        service.record(
                order,
                OrderShippingAddressHistoryActorType.USER,
                10L,
                "testuser",
                "山田 太郎",
                "100-0001",
                "東京都",
                "千代田区",
                "千代田1-1",
                "090-1111-2222",
                "佐藤 花子",
                "150-0001",
                "東京都",
                "渋谷区",
                "神宮前1-2-3",
                "080-1234-5678");

        ArgumentCaptor<OrderShippingAddressHistory> captor =
                ArgumentCaptor.forClass(
                        OrderShippingAddressHistory.class);

        verify(repository).save(captor.capture());

        OrderShippingAddressHistory history = captor.getValue();

        assertSame(
                order,
                history.getOrder());

        assertEquals(
                OrderShippingAddressHistoryActorType.USER,
                history.getChangedByType());

        assertEquals(
                10L,
                history.getChangedByAccountId());

        assertEquals(
                "testuser",
                history.getChangedByUsername());

        assertEquals(
                "山田 太郎",
                history.getOldShippingName());

        assertEquals(
                "100-0001",
                history.getOldShippingPostalCode());

        assertEquals(
                "東京都",
                history.getOldShippingPrefecture());

        assertEquals(
                "千代田区",
                history.getOldShippingCity());

        assertEquals(
                "千代田1-1",
                history.getOldShippingAddressLine());

        assertEquals(
                "090-1111-2222",
                history.getOldShippingPhone());

        assertEquals(
                "佐藤 花子",
                history.getNewShippingName());

        assertEquals(
                "150-0001",
                history.getNewShippingPostalCode());

        assertEquals(
                "東京都",
                history.getNewShippingPrefecture());

        assertEquals(
                "渋谷区",
                history.getNewShippingCity());

        assertEquals(
                "神宮前1-2-3",
                history.getNewShippingAddressLine());

        assertEquals(
                "080-1234-5678",
                history.getNewShippingPhone());
    }

    @Test
    void findByOrderIdReturnsHistoriesFromRepository() {

        Long orderId = 1L;

        OrderShippingAddressHistory first =
                mock(OrderShippingAddressHistory.class);

        OrderShippingAddressHistory second =
                mock(OrderShippingAddressHistory.class);

        List<OrderShippingAddressHistory> expected =
                List.of(first, second);

        when(repository
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId))
                .thenReturn(expected);

        List<OrderShippingAddressHistory> actual =
                service.findByOrderId(orderId);

        assertSame(
                expected,
                actual);

        verify(repository)
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }
}
