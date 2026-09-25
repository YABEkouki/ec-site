package com.example.ecsite.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.UserEnabledHistory;

@Service
public class UserEnabledHistoryCsvService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    public byte[] createCsv(
            List<UserEnabledHistory> histories) {

        StringBuilder csv = new StringBuilder();

        csv.append(
                "変更日時,顧客ID,ユーザー名,変更前状態,変更後状態,操作管理者ID,操作管理者ユーザー名")
                .append("\r\n");

        for (UserEnabledHistory history : histories) {

            csv.append(
                    history.getChangedAt()
                            .format(DATE_TIME_FORMATTER))
                    .append(",");

            csv.append(history.getUser().getId())
                    .append(",");

            csv.append(
                    escapeCsv(
                            history.getUser().getUsername()))
                    .append(",");

            csv.append(
                    history.isFromEnabled()
                            ? "有効"
                            : "無効")
                    .append(",");

            csv.append(
                    history.isToEnabled()
                            ? "有効"
                            : "無効")
                    .append(",");

            csv.append(history.getChangedByAccountId())
                    .append(",");

            csv.append(
                    escapeCsv(
                            history.getChangedByUsername()))
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
