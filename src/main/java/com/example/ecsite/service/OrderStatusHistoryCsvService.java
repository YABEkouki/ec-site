package com.example.ecsite.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.OrderStatusHistory;

@Service
public class OrderStatusHistoryCsvService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    public byte[] createCsv(
            List<OrderStatusHistory> histories) {

        StringBuilder csv = new StringBuilder();

        csv.append(
                "変更日時,注文ID,変更前ステータス,変更後ステータス,変更者種別,変更者ID,変更者ユーザー名,変更理由・備考")
                .append("\r\n");

        for (OrderStatusHistory history : histories) {

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
                    escapeCsv(
                            history.getToStatus()
                                    .getDisplayName()))
                    .append(",");

            csv.append(
                    escapeCsv(
                            history.getChangedByType()
                                    .getDisplayName()))
                    .append(",");

            csv.append(
                    history.getChangedByAccountId() != null
                            ? history.getChangedByAccountId()
                            : "")
                    .append(",");

            csv.append(
                    escapeCsv(
                            history.getChangedByUsername()))
                    .append(",");

            csv.append(
                    escapeCsv(
                            history.getInternalNote()))
                    .append("\r\n");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();

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
