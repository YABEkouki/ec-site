package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;

class OrderStatusHistoryCsvServiceTest {

    private final OrderStatusHistoryCsvService orderStatusHistoryCsvService =
            new OrderStatusHistoryCsvService();

    @Test
    void createCsvOutputsBomHeaderAndHistoryRows() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(10L);

        OrderStatusHistory history = mock(OrderStatusHistory.class);

        when(history.getChangedAt())
                .thenReturn(LocalDateTime.of(2026, 9, 3, 14, 30, 45));
        when(history.getOrder())
                .thenReturn(order);
        when(history.getFromStatus())
                .thenReturn(OrderStatus.ORDERED);
        when(history.getToStatus())
                .thenReturn(OrderStatus.PAID);
        when(history.getChangedByType())
                .thenReturn(OrderStatusHistoryActorType.ADMIN);
        when(history.getChangedByAccountId())
                .thenReturn(20L);
        when(history.getChangedByUsername())
                .thenReturn("admin");

        byte[] csvBytes =
                orderStatusHistoryCsvService.createCsv(
                        List.of(history));

        assertTrue(csvBytes.length >= 3);
        assertEquals((byte) 0xEF, csvBytes[0]);
        assertEquals((byte) 0xBB, csvBytes[1]);
        assertEquals((byte) 0xBF, csvBytes[2]);

        String csv =
                new String(
                        csvBytes,
                        3,
                        csvBytes.length - 3,
                        StandardCharsets.UTF_8);

        assertEquals(
                """
                変更日時,注文ID,変更前ステータス,変更後ステータス,変更者種別,変更者ID,変更者ユーザー名\r
                2026/09/03 14:30:45,10,注文受付,支払済み,管理者,20,admin\r
                """,
                csv);
    }

    @Test
    void createCsvOutputsEmptyFieldsForNullValuesAndEscapesUsername() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(11L);

        OrderStatusHistory history = mock(OrderStatusHistory.class);

        when(history.getChangedAt())
                .thenReturn(LocalDateTime.of(2026, 9, 3, 15, 0));
        when(history.getOrder())
                .thenReturn(order);
        when(history.getFromStatus())
                .thenReturn(null);
        when(history.getToStatus())
                .thenReturn(OrderStatus.ORDERED);
        when(history.getChangedByType())
                .thenReturn(OrderStatusHistoryActorType.SYSTEM);
        when(history.getChangedByAccountId())
                .thenReturn(null);
        when(history.getChangedByUsername())
                .thenReturn("system,\"batch\"");

        byte[] csvBytes =
                orderStatusHistoryCsvService.createCsv(
                        List.of(history));

        String csv =
                new String(
                        csvBytes,
                        3,
                        csvBytes.length - 3,
                        StandardCharsets.UTF_8);

        assertTrue(
                csv.contains(
                        "2026/09/03 15:00:00,11,,注文受付,システム,,\"system,\"\"batch\"\"\""));
    }
}
