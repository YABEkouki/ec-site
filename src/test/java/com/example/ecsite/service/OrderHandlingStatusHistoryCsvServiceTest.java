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
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;

class OrderHandlingStatusHistoryCsvServiceTest {

    private final OrderHandlingStatusHistoryCsvService csvService =
            new OrderHandlingStatusHistoryCsvService();

    @Test
    void createCsvOutputsBomHeaderAndHistoryRows() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(10L);

        OrderHandlingStatusHistory history =
                mock(OrderHandlingStatusHistory.class);

        when(history.getChangedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                9,
                                9,
                                14,
                                30,
                                45));

        when(history.getOrder())
                .thenReturn(order);

        when(history.getFromStatus())
                .thenReturn(OrderHandlingStatus.NONE);

        when(history.getToStatus())
                .thenReturn(OrderHandlingStatus.NEEDS_ACTION);

        when(history.getChangedByAccountId())
                .thenReturn(20L);

        when(history.getChangedByUsername())
                .thenReturn("admin");

        byte[] csvBytes =
                csvService.createCsv(
                        List.of(history));

        assertTrue(csvBytes.length >= 3);

        assertEquals(
                (byte) 0xEF,
                csvBytes[0]);

        assertEquals(
                (byte) 0xBB,
                csvBytes[1]);

        assertEquals(
                (byte) 0xBF,
                csvBytes[2]);

        String csv =
                new String(
                        csvBytes,
                        3,
                        csvBytes.length - 3,
                        StandardCharsets.UTF_8);

        assertEquals(
                """
                        変更日時,注文ID,変更前対応状況,変更後対応状況,管理者ID,管理者ユーザー名\r
                        2026/09/09 14:30:45,10,通常,要対応,20,admin\r
                        """,
                csv);
    }

    @Test
    void createCsvOutputsEmptyFieldsForNullValuesAndEscapesUsername() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(11L);

        OrderHandlingStatusHistory history =
                mock(OrderHandlingStatusHistory.class);

        when(history.getChangedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                9,
                                9,
                                15,
                                0));

        when(history.getOrder())
                .thenReturn(order);

        when(history.getFromStatus())
                .thenReturn(null);

        when(history.getToStatus())
                .thenReturn(OrderHandlingStatus.IN_PROGRESS);

        when(history.getChangedByAccountId())
                .thenReturn(null);

        when(history.getChangedByUsername())
                .thenReturn("admin,\"test\"");

        byte[] csvBytes =
                csvService.createCsv(
                        List.of(history));

        String csv =
                new String(
                        csvBytes,
                        3,
                        csvBytes.length - 3,
                        StandardCharsets.UTF_8);

        assertTrue(
                csv.contains(
                        "2026/09/09 15:00:00,11,,対応中,,\"admin,\"\"test\"\"\""));
    }
}
