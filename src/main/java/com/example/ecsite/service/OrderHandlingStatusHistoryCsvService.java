package com.example.ecsite.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.OrderHandlingStatusHistory;

@Service
public class OrderHandlingStatusHistoryCsvService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    public byte[] createCsv(
            List<OrderHandlingStatusHistory> histories) {

        StringBuilder csv = new StringBuilder();

        csv.append(
                "変更日時,注文ID,変更前対応状況,変更後対応状況,管理者ID,管理者ユーザー名")
                .append("\r\n");

        for (OrderHandlingStatusHistory history : histories) {

            csv.append(
                    history.getChangedAt()
                            .format(DATE_TIME_FORMATTER))
                    .append(",");

            csv.append(history.getOrder().getId())
                    .append(",");

            csv.append(
                    history.getFromStatus() != null
                            ? escapeCsv(
                                    history.getFromStatus()
                                            .getDisplayName())
                            : "")
                    .append(",");

            csv.append(
                    history.getToStatus() != null
                            ? escapeCsv(
                                    history.getToStatus()
                                            .getDisplayName())
                            : "")
                    .append(",");

            csv.append(
                    history.getChangedByAccountId() != null
                            ? history.getChangedByAccountId()
                            : "")
                    .append(",");

            csv.append(
                    escapeCsv(
                            history.getChangedByUsername()))
                    .append("\r\n");
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        output.write(0xEF);
        output.write(0xBB);
        output.write(0xBF);

        output.writeBytes(
                csv.toString()
                        .getBytes(StandardCharsets.UTF_8));

        return output.toByteArray();
    }

    private String escapeCsv(String value) {

        if (value == null) {
            return "";
        }

        if (value.contains(",")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r")) {

            return "\""
                    + value.replace("\"", "\"\"")
                    + "\"";
        }

        return value;
    }
}
