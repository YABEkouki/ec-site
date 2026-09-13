package com.example.ecsite.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.OrderAssigneeHistory;

@Service
public class OrderAssigneeHistoryCsvService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    public byte[] createCsv(
            List<OrderAssigneeHistory> histories) {

        StringBuilder csv = new StringBuilder();

        csv.append(
                "変更日時,注文ID,変更前担当者ID,変更前担当者ユーザー名,"
                        + "変更後担当者ID,変更後担当者ユーザー名,"
                        + "変更者ID,変更者ユーザー名\r\n");

        for (OrderAssigneeHistory history : histories) {

            csv.append(
                    history.getChangedAt()
                            .format(DATE_TIME_FORMATTER))
                    .append(',');

            csv.append(
                    history.getOrder().getId())
                    .append(',');

            csv.append(
                    history.getFromAdminAccountId() == null
                            ? ""
                            : history.getFromAdminAccountId())
                    .append(',');

            csv.append(
                    escapeCsv(
                            history.getFromAdminAccountId() == null
                                    ? "未担当"
                                    : history.getFromAdminUsername()))
                    .append(',');

            csv.append(
                    history.getToAdminAccountId() == null
                            ? ""
                            : history.getToAdminAccountId())
                    .append(',');

            csv.append(
                    escapeCsv(
                            history.getToAdminAccountId() == null
                                    ? "未担当"
                                    : history.getToAdminUsername()))
                    .append(',');

            csv.append(
                    history.getChangedByAccountId() == null
                            ? ""
                            : history.getChangedByAccountId())
                    .append(',');

            csv.append(
                    escapeCsv(
                            history.getChangedByUsername()))
                    .append("\r\n");
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        output.writeBytes(
                new byte[] {
                        (byte) 0xEF,
                        (byte) 0xBB,
                        (byte) 0xBF
                });

        output.writeBytes(
                csv.toString()
                        .getBytes(StandardCharsets.UTF_8));

        return output.toByteArray();
    }

    private String escapeCsv(String value) {

        if (value == null) {
            return "";
        }

        boolean requiresQuotes =
                value.contains(",")
                        || value.contains("\"")
                        || value.contains("\n")
                        || value.contains("\r");

        if (!requiresQuotes) {
            return value;
        }

        return "\""
                + value.replace("\"", "\"\"")
                + "\"";
    }
}
