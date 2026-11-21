package com.example.ecsite.service.warehouse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.example.ecsite.security.AdminUserDetails;

/**
 * Feature131 C-2: the warehouse CSV format (one file, one row per order item). Feature137-B replaced the download
 * of a batch by the two warehouse files ({@link WarehouseFileFormat}); the format remains for the Feature136
 * emergency CSV until Feature137-C, and the download audit (V87) remains as history.
 */
@Service
public class WarehouseExportCsvService {

    public static final String HEADER = "バッチID,業務日,注文番号,明細番号,受取人,郵便番号,都道府県,市区町村,住所,電話番号,商品ID,商品名,数量";

    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public static class NotDownloadableException extends RuntimeException {
        public NotDownloadableException(String message) { super(message); }
    }

    /** One CSV row. The batch ID is text so that Feature136 emergency CSVs can use 'E' + record id. */
    public record Row(String batchId, LocalDate businessDate, long orderId, int itemNo, String name, String postalCode,
            String prefecture, String city, String addressLine, String phone, long productId, String productName, int quantity) {}

    public static byte[] render(List<Row> rows) {
        var csv = new StringBuilder(HEADER).append("\r\n");
        for (var r : rows) {
            csv.append(escape(r.batchId())).append(',').append(DATE.format(r.businessDate())).append(',').append(r.orderId())
                    .append(',').append(r.itemNo()).append(',').append(escape(r.name())).append(',')
                    .append(escape(r.postalCode())).append(',').append(escape(r.prefecture())).append(',')
                    .append(escape(r.city())).append(',').append(escape(r.addressLine())).append(',')
                    .append(escape(r.phone())).append(',').append(r.productId()).append(',')
                    .append(escape(r.productName())).append(',').append(r.quantity()).append("\r\n");
        }
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF_8_BOM.length + body.length];
        System.arraycopy(UTF_8_BOM, 0, result, 0, UTF_8_BOM.length);
        System.arraycopy(body, 0, result, UTF_8_BOM.length, body.length);
        return result;
    }

    static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static AdminUserDetails requireAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                || !(authentication.getPrincipal() instanceof AdminUserDetails actor)
                || actor.getId() == null || actor.getId() <= 0
                || actor.getUsername() == null || actor.getUsername().isBlank() || actor.getUsername().length() > 100) {
            throw new AccessDeniedException("有効な管理者認証が必要です。");
        }
        return actor;
    }
}
