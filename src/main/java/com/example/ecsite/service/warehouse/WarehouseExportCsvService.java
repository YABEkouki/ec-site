package com.example.ecsite.service.warehouse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.security.AdminUserDetails;

/**
 * Feature131 C-2: the warehouse CSV of a sealed batch. One file, one row per order item; order columns repeat on
 * every row. UTF-8 with BOM and CRLF like the existing order CSV. The rows come only from the immutable export
 * lines, so every download of a batch is byte-identical; each download is recorded with its SHA-256.
 */
@Service
public class WarehouseExportCsvService {

    public static final String HEADER = "バッチID,業務日,注文番号,明細番号,受取人,郵便番号,都道府県,市区町村,住所,電話番号,商品ID,商品名,数量";

    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public record Download(String filename, byte[] content, int lineCount, int rowCount) {}

    public static class NotDownloadableException extends RuntimeException {
        public NotDownloadableException(String message) { super(message); }
    }

    /** One CSV row. The batch ID is text so that Feature136 emergency CSVs can use 'E' + record id. */
    public record Row(String batchId, LocalDate businessDate, long orderId, int itemNo, String name, String postalCode,
            String prefecture, String city, String addressLine, String phone, long productId, String productName, int quantity) {}

    private final JdbcTemplate jdbc;

    public WarehouseExportCsvService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Renders and records one administrator download. Only a SEALED batch is downloadable (also enforced by V87). */
    @Transactional
    public Download download(long batchId) {
        AdminUserDetails actor = requireAdmin();
        var batch = jdbc.query("select business_date,state,line_count from warehouse_export_batches where id=? for share",
                (r, n) -> new Object[] {r.getObject("business_date", LocalDate.class), r.getString("state"), r.getObject("line_count")},
                batchId);
        if (batch.isEmpty()) throw new NotDownloadableException("倉庫出力バッチが見つかりません。");
        if (!"SEALED".equals(batch.getFirst()[1])) {
            throw new NotDownloadableException("出力処理が完了していないバッチはダウンロードできません。");
        }
        LocalDate businessDate = (LocalDate) batch.getFirst()[0];
        int lineCount = (Integer) batch.getFirst()[2];
        var rows = rows(batchId);
        byte[] content = render(rows);
        jdbc.update("""
            insert into warehouse_export_downloads(batch_id,admin_account_id,admin_username,line_count,row_count,content_sha256)
            values (?,?,?,?,?,?)
            """, batchId, actor.getId(), actor.getUsername(), lineCount, rows.size(), sha256(content));
        String filename = "warehouse-export-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + batchId + ".csv";
        return new Download(filename, content, lineCount, rows.size());
    }

    private List<Row> rows(long batchId) {
        return jdbc.query("""
            select l.batch_id,b.business_date,l.order_id,e.n::int as item_no,l.shipping_name,l.shipping_postal_code,
              l.shipping_prefecture,l.shipping_city,l.shipping_address_line,l.shipping_phone,
              (e.i->>'productId')::bigint as product_id,e.i->>'productName' as product_name,(e.i->>'quantity')::int as quantity
            from warehouse_export_lines l
            join warehouse_export_batches b on b.id=l.batch_id
            cross join lateral jsonb_array_elements(l.items) with ordinality as e(i,n)
            where l.batch_id=?
            order by l.order_id, e.n
            """, (r, n) -> new Row(String.valueOf(r.getLong("batch_id")), r.getObject("business_date", LocalDate.class), r.getLong("order_id"),
                r.getInt("item_no"), r.getString("shipping_name"), r.getString("shipping_postal_code"),
                r.getString("shipping_prefecture"), r.getString("shipping_city"), r.getString("shipping_address_line"),
                r.getString("shipping_phone"), r.getLong("product_id"), r.getString("product_name"), r.getInt("quantity")),
            batchId);
    }

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
