package com.example.ecsite.service.warehouse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Feature137: the two warehouse files of one output (a 14:05 batch or one emergency-shipped order), as designed in
 * docs/feature137/warehouse-file-output.md ch.2. The header file has one row per order; the detail file has one row
 * per order item, then one per positive charge (shipping fee), numbered from 1 within the order.
 * UTF-8 with BOM, CRLF, a header line, dates yyyy/MM/dd, quotes only when needed (as the Feature131 CSV).
 * Rendering is pure: the same content always gives the same bytes.
 */
public final class WarehouseFileFormat {

    public static final String HEADER_COLUMNS = "バッチID,業務日,注文番号,受取人,郵便番号,都道府県,市区町村,住所,電話番号,税抜き金額,税額,合計金額";
    public static final String DETAIL_COLUMNS = "バッチID,注文番号,明細番号,商品ID,商品名,税率,単価,数量,小計,税額";

    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private WarehouseFileFormat() {}

    /** One order. {@code totalAmount} includes tax; the amount without tax is {@code totalAmount - taxAmount}. */
    public record HeaderRow(long orderId, String name, String postalCode, String prefecture, String city,
            String addressLine, String phone, int totalAmount, int taxAmount) {}

    /** One detail row. {@code productId} is null for a charge row (shipping fee). */
    public record DetailRow(long orderId, int lineNo, Long productId, String productName, BigDecimal taxRate,
            int unitPrice, int quantity, int subtotal, int taxAmount) {}

    /** One rendered file. */
    public record File(String filename, byte[] content, int rowCount) {}

    /**
     * Everything one output writes. {@code batchId} is the batch id for a 14:05 batch and 'E' + the emergency record
     * id for an emergency-shipped order; {@code businessDate} is the batch business date or the emergency date.
     */
    public record Content(String batchId, LocalDate businessDate, List<HeaderRow> headers, List<DetailRow> details) {
        public Content {
            Objects.requireNonNull(batchId, "batchId");
            Objects.requireNonNull(businessDate, "businessDate");
            headers = List.copyOf(headers);
            details = List.copyOf(details);
            validate(batchId, headers, details);
        }

        public File headerFile() {
            return new File(headerFilename(businessDate, batchId), renderHeader(this), headers.size());
        }

        public File detailFile() {
            return new File(detailFilename(businessDate, batchId), renderDetail(this), details.size());
        }
    }

    public static String headerFilename(LocalDate businessDate, String batchId) {
        return "warehouse-header-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + batchId + ".csv";
    }

    public static String detailFilename(LocalDate businessDate, String batchId) {
        return "warehouse-detail-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + batchId + ".csv";
    }

    /** 10.00 → "10%", 8 → "8%" (half-width). */
    public static String taxRate(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * The application half of the V101 invariant: orders ascending and unique, every order has detail rows numbered
     * 1..n in order, and per order the detail subtotals add up to the total and the detail taxes to the tax.
     */
    static void validate(String batchId, List<HeaderRow> headers, List<DetailRow> details) {
        if (!batchId.matches("E?[1-9][0-9]{0,17}")) throw invalid("バッチIDが不正です。");
        var totals = new HashMap<Long, long[]>();
        long previous = 0;
        for (var h : headers) {
            if (h.orderId() <= previous) throw invalid("注文の並びが不正です。");
            previous = h.orderId();
            if (h.taxAmount() < 0 || h.taxAmount() > h.totalAmount()) throw invalid("注文 " + h.orderId() + " の税額が不正です。");
            totals.put(h.orderId(), new long[] {0, 0, 0});
        }
        var finished = new HashSet<Long>();
        Long current = null;
        for (var d : details) {
            long[] sum = totals.get(d.orderId());
            if (sum == null) throw invalid("ヘッダにない注文 " + d.orderId() + " の明細があります。");
            if (!Objects.equals(current, d.orderId())) {
                if (current != null) finished.add(current);
                if (finished.contains(d.orderId()) || (current != null && d.orderId() < current)) {
                    throw invalid("明細の並びが不正です。");
                }
                current = d.orderId();
            }
            if (d.lineNo() != sum[2] + 1) throw invalid("注文 " + d.orderId() + " の明細番号が連番ではありません。");
            if (d.productName() == null || d.taxRate() == null || d.taxRate().signum() < 0
                    || d.quantity() <= 0 || d.subtotal() < 0 || d.taxAmount() < 0) {
                throw invalid("注文 " + d.orderId() + " の明細 " + d.lineNo() + " が不正です。");
            }
            sum[0] += d.subtotal();
            sum[1] += d.taxAmount();
            sum[2] = d.lineNo();
        }
        for (var h : headers) {
            long[] sum = totals.get(h.orderId());
            if (sum[2] == 0 || sum[0] != h.totalAmount() || sum[1] != h.taxAmount()) {
                throw invalid("注文 " + h.orderId() + " の明細の合計が注文金額・税額と一致しません。");
            }
        }
    }

    static byte[] renderHeader(Content c) {
        var csv = new StringBuilder(HEADER_COLUMNS).append("\r\n");
        String day = DATE.format(c.businessDate());
        for (var h : c.headers()) {
            csv.append(c.batchId()).append(',').append(day).append(',').append(h.orderId()).append(',')
                    .append(escape(h.name())).append(',').append(escape(h.postalCode())).append(',')
                    .append(escape(h.prefecture())).append(',').append(escape(h.city())).append(',')
                    .append(escape(h.addressLine())).append(',').append(escape(h.phone())).append(',')
                    .append(h.totalAmount() - h.taxAmount()).append(',').append(h.taxAmount()).append(',')
                    .append(h.totalAmount()).append("\r\n");
        }
        return withBom(csv);
    }

    static byte[] renderDetail(Content c) {
        var csv = new StringBuilder(DETAIL_COLUMNS).append("\r\n");
        for (var d : c.details()) {
            csv.append(c.batchId()).append(',').append(d.orderId()).append(',').append(d.lineNo()).append(',')
                    .append(d.productId() == null ? "" : d.productId().toString()).append(',')
                    .append(escape(d.productName())).append(',').append(taxRate(d.taxRate())).append(',')
                    .append(d.unitPrice()).append(',').append(d.quantity()).append(',').append(d.subtotal()).append(',')
                    .append(d.taxAmount()).append("\r\n");
        }
        return withBom(csv);
    }

    private static String escape(String value) {
        return WarehouseExportCsvService.escape(value);
    }

    private static byte[] withBom(StringBuilder csv) {
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF_8_BOM.length + body.length];
        System.arraycopy(UTF_8_BOM, 0, result, 0, UTF_8_BOM.length);
        System.arraycopy(body, 0, result, UTF_8_BOM.length, body.length);
        return result;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("倉庫向けファイルの内容が不正です：" + message);
    }
}
