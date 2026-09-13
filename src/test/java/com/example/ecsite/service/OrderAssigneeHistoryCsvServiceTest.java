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
import com.example.ecsite.entity.OrderAssigneeHistory;

class OrderAssigneeHistoryCsvServiceTest {

    private final OrderAssigneeHistoryCsvService csvService = new OrderAssigneeHistoryCsvService();

    @Test
    void createCsvOutputsBomHeaderAndHistoryRows() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(10L);

        OrderAssigneeHistory history = mock(OrderAssigneeHistory.class);

        when(history.getChangedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                9,
                                13,
                                14,
                                30,
                                45));

        when(history.getOrder())
                .thenReturn(order);

        when(history.getFromAdminAccountId())
                .thenReturn(20L);

        when(history.getFromAdminUsername())
                .thenReturn("admin1");

        when(history.getToAdminAccountId())
                .thenReturn(21L);

        when(history.getToAdminUsername())
                .thenReturn("admin2");

        when(history.getChangedByAccountId())
                .thenReturn(30L);

        when(history.getChangedByUsername())
                .thenReturn("operator");

        byte[] csvBytes = csvService.createCsv(
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

        String csv = new String(
                csvBytes,
                3,
                csvBytes.length - 3,
                StandardCharsets.UTF_8);

        assertEquals(
                """
                        変更日時,注文ID,変更前担当者ID,変更前担当者ユーザー名,変更後担当者ID,変更後担当者ユーザー名,変更者ID,変更者ユーザー名\r
                        2026/09/13 14:30:45,10,20,admin1,21,admin2,30,operator\r
                        """,
                csv);
    }

    @Test
    void createCsvOutputsUnassignedAndEscapesUsername() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(11L);

        OrderAssigneeHistory history = mock(OrderAssigneeHistory.class);

        when(history.getChangedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                9,
                                13,
                                15,
                                0));

        when(history.getOrder())
                .thenReturn(order);

        when(history.getFromAdminAccountId())
                .thenReturn(null);

        when(history.getFromAdminUsername())
                .thenReturn(null);

        when(history.getToAdminAccountId())
                .thenReturn(21L);

        when(history.getToAdminUsername())
                .thenReturn("admin,2");

        when(history.getChangedByAccountId())
                .thenReturn(30L);

        when(history.getChangedByUsername())
                .thenReturn("operator,\"test\"");

        byte[] csvBytes = csvService.createCsv(
                List.of(history));

        String csv = new String(
                csvBytes,
                3,
                csvBytes.length - 3,
                StandardCharsets.UTF_8);

        assertTrue(
                csv.contains(
                        "2026/09/13 15:00:00,11,,未担当,21,\"admin,2\",30,\"operator,\"\"test\"\"\""));
    }

}
