package com.example.ecsite.service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Order;

@Service
public class OrderCsvService {

    private static final byte[] UTF_8_BOM = {
            (byte) 0xEF,
            (byte) 0xBB,
            (byte) 0xBF
    };

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy/MM/dd HH:mm:ss");

    public byte[] createCsv(List<Order> orders) {

        StringBuilder csv = new StringBuilder();

        csv.append(
                "注文番号,ユーザーID,注文日時,注文状態,対応状況,合計金額,"
                        + "受取人,郵便番号,都道府県,市区町村,住所,電話番号,"
                        + "支払日時,発送日時,キャンセル日時")
                .append("\r\n");

        for (Order order : orders) {

            csv.append(order.getId())
                    .append(",")
                    .append(order.getUserId())
                    .append(",")
                    .append(DATE_TIME_FORMATTER.format(order.getOrderedAt()))
                    .append(",")
                    .append(order.getStatus().getDisplayName())
                    .append(",")
                    .append(order.getHandlingStatus().getDisplayName())
                    .append(",")
                    .append(order.getTotalAmount())
                    .append(",")
                    .append(escapeCsv(order.getShippingName()))
                    .append(",")
                    .append(escapeCsv(order.getShippingPostalCode()))
                    .append(",")
                    .append(escapeCsv(order.getShippingPrefecture()))
                    .append(",")
                    .append(escapeCsv(order.getShippingCity()))
                    .append(",")
                    .append(escapeCsv(order.getShippingAddressLine()))
                    .append(",")
                    .append(escapeCsv(order.getShippingPhone()))
                    .append(",")
                    .append(formatDateTime(order.getPaidAt()))
                    .append(",")
                    .append(formatDateTime(order.getShippedAt()))
                    .append(",")
                    .append(formatDateTime(order.getCancelledAt()))
                    .append("\r\n");
        }

        byte[] content = csv.toString()
                .getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[UTF_8_BOM.length + content.length];

        System.arraycopy(
                UTF_8_BOM,
                0,
                result,
                0,
                UTF_8_BOM.length);

        System.arraycopy(
                content,
                0,
                result,
                UTF_8_BOM.length,
                content.length);

        return result;
    }

    private String formatDateTime(
            java.time.LocalDateTime dateTime) {

        if (dateTime == null) {
            return "";
        }

        return DATE_TIME_FORMATTER.format(dateTime);
    }

    private String escapeCsv(String value) {

        if (value == null) {
            return "";
        }

        boolean needsQuote = value.contains(",")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r");

        String escaped = value.replace("\"", "\"\"");

        return needsQuote
                ? "\"" + escaped + "\""
                : escaped;
    }
}