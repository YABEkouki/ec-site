package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserEnabledHistory;

class UserEnabledHistoryCsvServiceTest {

    private final UserEnabledHistoryCsvService service =
            new UserEnabledHistoryCsvService();

    @Test
    void createCsvOutputsBomHeaderAndHistoryRows() {

        User user = mock(User.class);
        when(user.getId()).thenReturn(10L);
        when(user.getUsername()).thenReturn("customer");

        UserEnabledHistory history =
                mock(UserEnabledHistory.class);

        when(history.getChangedAt())
                .thenReturn(LocalDateTime.of(
                        2026, 9, 25, 14, 30, 45));

        when(history.getUser())
                .thenReturn(user);

        when(history.isFromEnabled())
                .thenReturn(true);

        when(history.isToEnabled())
                .thenReturn(false);

        when(history.getChangedByAccountId())
                .thenReturn(20L);

        when(history.getChangedByUsername())
                .thenReturn("admin");

        byte[] csvBytes =
                service.createCsv(List.of(history));

        assertTrue(csvBytes.length >= 3);
        assertEquals((byte) 0xEF, csvBytes[0]);
        assertEquals((byte) 0xBB, csvBytes[1]);
        assertEquals((byte) 0xBF, csvBytes[2]);

        String csv = new String(
                csvBytes,
                3,
                csvBytes.length - 3,
                StandardCharsets.UTF_8);

        assertEquals(
                """
                        変更日時,顧客ID,ユーザー名,変更前状態,変更後状態,操作管理者ID,操作管理者ユーザー名\r
                        2026/09/25 14:30:45,10,customer,有効,無効,20,admin\r
                        """,
                csv);
    }

    @Test
    void createCsvOutputsEnabledHistory() {

        User user = mock(User.class);
        when(user.getId()).thenReturn(11L);
        when(user.getUsername()).thenReturn("customer2");

        UserEnabledHistory history =
                mock(UserEnabledHistory.class);

        when(history.getChangedAt())
                .thenReturn(LocalDateTime.of(
                        2026, 9, 25, 15, 0));

        when(history.getUser())
                .thenReturn(user);

        when(history.isFromEnabled())
                .thenReturn(false);

        when(history.isToEnabled())
                .thenReturn(true);

        when(history.getChangedByAccountId())
                .thenReturn(21L);

        when(history.getChangedByUsername())
                .thenReturn("admin2");

        byte[] csvBytes =
                service.createCsv(List.of(history));

        String csv = new String(
                csvBytes,
                3,
                csvBytes.length - 3,
                StandardCharsets.UTF_8);

        assertTrue(
                csv.contains(
                        "2026/09/25 15:00:00,11,customer2,無効,有効,21,admin2"));
    }

    @Test
    void createCsvEscapesUsernames() {

        User user = mock(User.class);
        when(user.getId()).thenReturn(12L);
        when(user.getUsername())
                .thenReturn("customer,\"test\"");

        UserEnabledHistory history =
                mock(UserEnabledHistory.class);

        when(history.getChangedAt())
                .thenReturn(LocalDateTime.of(
                        2026, 9, 25, 16, 0));

        when(history.getUser())
                .thenReturn(user);

        when(history.isFromEnabled())
                .thenReturn(true);

        when(history.isToEnabled())
                .thenReturn(false);

        when(history.getChangedByAccountId())
                .thenReturn(22L);

        when(history.getChangedByUsername())
                .thenReturn("admin,\"operator\"");

        byte[] csvBytes =
                service.createCsv(List.of(history));

        String csv = new String(
                csvBytes,
                3,
                csvBytes.length - 3,
                StandardCharsets.UTF_8);

        assertTrue(
                csv.contains(
                        "12,\"customer,\"\"test\"\"\",有効,無効,22,\"admin,\"\"operator\"\"\""));
    }
}
