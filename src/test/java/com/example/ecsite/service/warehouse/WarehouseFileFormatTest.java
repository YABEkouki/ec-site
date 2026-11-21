package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ecsite.service.warehouse.WarehouseFileFormat.Content;
import com.example.ecsite.service.warehouse.WarehouseFileFormat.DetailRow;
import com.example.ecsite.service.warehouse.WarehouseFileFormat.HeaderRow;

/** Feature137-A: the two warehouse files (docs/feature137/warehouse-file-output.md ch.2). */
class WarehouseFileFormatTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    static final BigDecimal TEN = new BigDecimal("10.00"), EIGHT = new BigDecimal("8.00");

    static HeaderRow header(long order, String address, int total, int tax) {
        return new HeaderRow(order, "山田 太郎", "100-0001", "東京都", "千代田区", address, "0312345678", total, tax);
    }

    /** Order 41: two 10% items, one 8% item and the shipping fee (row taxes as allocated by V101). Order 42: one item. */
    static Content sample(String batchId) {
        return new Content(batchId, DAY,
                List.of(header(41, "千代田1-1, ビル\"A\"", 758, 66), header(42, "千代田2-2", 1100, 100)),
                List.of(new DetailRow(41, 1, 501L, "商品A", TEN, 27, 2, 54, 4),
                        new DetailRow(41, 2, 502L, "商品B, 特別", TEN, 54, 1, 54, 4),
                        new DetailRow(41, 3, 503L, "食品C", EIGHT, 100, 1, 100, 7),
                        new DetailRow(41, 4, null, "送料・梱包料", TEN, 550, 1, 550, 51),
                        new DetailRow(42, 1, 504L, "商品D", TEN, 1100, 1, 1100, 100)));
    }

    static String text(byte[] content) {
        assertThat(content).startsWith(0xEF, 0xBB, 0xBF);
        return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
    }

    @Test void rendersTheHeaderFileWithOneRowPerOrder() {
        var file = sample("15").headerFile();
        assertThat(file.filename()).isEqualTo("warehouse-header-20261121-15.csv");
        assertThat(file.rowCount()).isEqualTo(2);
        assertThat(text(file.content())).isEqualTo(
                "バッチID,業務日,注文番号,受取人,郵便番号,都道府県,市区町村,住所,電話番号,税抜き金額,税額,合計金額\r\n"
                + "15,2026/11/21,41,山田 太郎,100-0001,東京都,千代田区,\"千代田1-1, ビル\"\"A\"\"\",0312345678,692,66,758\r\n"
                + "15,2026/11/21,42,山田 太郎,100-0001,東京都,千代田区,千代田2-2,0312345678,1000,100,1100\r\n");
    }

    @Test void rendersTheDetailFileWithTheShippingFeeAsARowAndHalfWidthTaxRates() {
        var file = sample("15").detailFile();
        assertThat(file.filename()).isEqualTo("warehouse-detail-20261121-15.csv");
        assertThat(file.rowCount()).isEqualTo(5);
        assertThat(text(file.content())).isEqualTo(
                "バッチID,注文番号,明細番号,商品ID,商品名,税率,単価,数量,小計,税額\r\n"
                + "15,41,1,501,商品A,10%,27,2,54,4\r\n"
                + "15,41,2,502,\"商品B, 特別\",10%,54,1,54,4\r\n"
                + "15,41,3,503,食品C,8%,100,1,100,7\r\n"
                + "15,41,4,,送料・梱包料,10%,550,1,550,51\r\n"
                + "15,42,1,504,商品D,10%,1100,1,1100,100\r\n");
    }

    @Test void anEmergencyOutputUsesItsEBatchIdInTheRowsAndTheFileNames() {
        var content = sample("E3");
        assertThat(content.headerFile().filename()).isEqualTo("warehouse-header-20261121-E3.csv");
        assertThat(content.detailFile().filename()).isEqualTo("warehouse-detail-20261121-E3.csv");
        assertThat(text(content.headerFile().content()).split("\r\n")[1]).startsWith("E3,2026/11/21,41,");
        assertThat(text(content.detailFile().content()).split("\r\n")[1]).startsWith("E3,41,1,");
    }

    @Test void anEmptyOutputHasOnlyTheHeaderLines() {
        var content = new Content("16", DAY, List.of(), List.of());
        assertThat(text(content.headerFile().content())).isEqualTo(WarehouseFileFormat.HEADER_COLUMNS + "\r\n");
        assertThat(text(content.detailFile().content())).isEqualTo(WarehouseFileFormat.DETAIL_COLUMNS + "\r\n");
        assertThat(content.headerFile().rowCount()).isZero();
        assertThat(content.detailFile().rowCount()).isZero();
    }

    @Test void renderingIsDeterministic() {
        assertThat(sample("15").headerFile().content()).isEqualTo(sample("15").headerFile().content());
        assertThat(sample("15").detailFile().content()).isEqualTo(sample("15").detailFile().content());
    }

    @Test void taxRatesAreWrittenHalfWidthWithoutTrailingZeros() {
        assertThat(WarehouseFileFormat.taxRate(new BigDecimal("10.00"))).isEqualTo("10%");
        assertThat(WarehouseFileFormat.taxRate(new BigDecimal("8"))).isEqualTo("8%");
        assertThat(WarehouseFileFormat.taxRate(new BigDecimal("8.50"))).isEqualTo("8.5%");
        assertThat(WarehouseFileFormat.taxRate(new BigDecimal("0.00"))).isEqualTo("0%");
    }

    @Test void refusesContentWhoseRowsDoNotAddUpOrAreOutOfOrder() {
        var h = List.of(header(41, "a", 600, 54));
        var ok = List.of(new DetailRow(41, 1, 1L, "x", TEN, 50, 1, 50, 4), new DetailRow(41, 2, null, "送料", TEN, 550, 1, 550, 50));
        assertThatCode(() -> new Content("15", DAY, h, ok)).doesNotThrowAnyException();

        // Subtotals, taxes, numbering, unknown or missing orders, ordering, batch id.
        assertInvalid("15", h, List.of(ok.get(0), new DetailRow(41, 2, null, "送料", TEN, 550, 1, 549, 50)));
        assertInvalid("15", h, List.of(ok.get(0), new DetailRow(41, 2, null, "送料", TEN, 550, 1, 550, 49)));
        assertInvalid("15", h, List.of(ok.get(0), new DetailRow(41, 3, null, "送料", TEN, 550, 1, 550, 50)));
        assertInvalid("15", h, List.of(ok.get(1), ok.get(0)));
        assertInvalid("15", h, List.of());
        assertInvalid("15", h, List.of(ok.get(0), ok.get(1), new DetailRow(43, 1, 1L, "x", TEN, 1, 1, 1, 0)));
        assertInvalid("15", List.of(header(42, "a", 1, 0), header(41, "a", 600, 54)), ok);
        assertInvalid("15", List.of(header(41, "a", 600, 54), header(41, "a", 600, 54)), ok);
        assertInvalid("15", List.of(header(41, "a", 600, 601)), ok);
        assertInvalid("15", h, List.of(ok.get(0), new DetailRow(41, 2, null, null, TEN, 550, 1, 550, 50)));
        assertInvalid("15", h, List.of(ok.get(0), new DetailRow(41, 2, null, "送料", TEN, 550, 0, 550, 50)));
        var two = List.of(header(41, "a", 600, 54), header(42, "a", 1, 0));
        var d42 = new DetailRow(42, 1, 1L, "x", TEN, 1, 1, 1, 0);
        assertInvalid("15", two, List.of(ok.get(0), d42, ok.get(1)));
        assertThatCode(() -> new Content("15", DAY, two, List.of(ok.get(0), ok.get(1), d42))).doesNotThrowAnyException();
        for (String bad : List.of("", "0", "E", "e3", "15a", "-1", "E0")) assertInvalid(bad, h, ok);
    }

    static void assertInvalid(String batchId, List<HeaderRow> headers, List<DetailRow> details) {
        assertThatThrownBy(() -> new Content(batchId, DAY, headers, details))
                .as("%s %s %s", batchId, headers, details)
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("倉庫向けファイルの内容が不正です");
    }
}
