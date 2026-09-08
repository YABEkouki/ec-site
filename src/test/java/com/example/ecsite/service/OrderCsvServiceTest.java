package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.Order;

class OrderCsvServiceTest {

    private final OrderCsvService orderCsvService = new OrderCsvService();

    @Test
    void createCsvOutputsHeaderAndOrderDataWithUtf8Bom() {

        Order order = new Order(10L, 2500);
        order.setOrderedAt(
                LocalDateTime.of(
                        2026,
                        8,
                        31,
                        14,
                        32,
                        15));

        order.setShippingAddress(
                "山田 太郎",
                "123-4567",
                "東京都",
                "千代田区",
                "1-2-3",
                "090-1234-5678");

        byte[] csvBytes = orderCsvService.createCsv(
                List.of(order));

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

        assertTrue(csv.startsWith(
                "注文番号,ユーザーID,注文日時,注文状態,対応状況,合計金額,"
                        + "受取人,郵便番号,都道府県,市区町村,住所,電話番号,"
                        + "支払日時,発送日時,キャンセル日時"));

        assertTrue(csv.contains("10"));
        assertTrue(csv.contains("2026/08/31 14:32:15"));
        assertTrue(csv.contains("注文受付"));
        assertTrue(csv.contains("通常"));
        assertTrue(csv.contains("2500"));
        assertTrue(csv.contains("山田 太郎"));
        assertTrue(csv.contains("東京都"));
    }

    @Test
    void createCsvEscapesCommaQuoteAndLineBreak() {

        Order order = new Order(10L, 2500);
        order.setOrderedAt(
                LocalDateTime.of(
                        2026,
                        8,
                        31,
                        14,
                        32,
                        15));

        order.setShippingAddress(
                "山田 \"太郎\"",
                "123-4567",
                "東京都",
                "千代田区",
                "1-2-3,丸の内\nビル",
                "090-1234-5678");

        byte[] csvBytes = orderCsvService.createCsv(
                List.of(order));

        String csv = new String(
                csvBytes,
                3,
                csvBytes.length - 3,
                StandardCharsets.UTF_8);

        assertTrue(csv.contains(
                "\"山田 \"\"太郎\"\"\""));

        assertTrue(csv.contains(
                "\"1-2-3,丸の内\nビル\""));
    }
}
