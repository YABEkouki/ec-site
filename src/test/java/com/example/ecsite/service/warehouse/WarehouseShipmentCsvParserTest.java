package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Feature132-B: plain JUnit, no Spring or database. */
class WarehouseShipmentCsvParserTest {
    static final String H = WarehouseShipmentCsvParser.HEADER;

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void readsRowsWithBomCrlfQuotesAndBlankLines() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = utf8(H + "\r\n12,1043,2026-11-17,ヤマト運輸,1234-5678\r\n\r\n 12 ,\"1044\",2026-11-17,\"佐川, \"\"急便\"\"\",ABC-1\n");
        byte[] content = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, content, 0, 3); System.arraycopy(body, 0, content, 3, body.length);

        var rows = WarehouseShipmentCsvParser.parse(content);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).lineNo()).isEqualTo(2);
        assertThat(rows.get(0).fields()).containsExactly("12", "1043", "2026-11-17", "ヤマト運輸", "1234-5678");
        assertThat(rows.get(1).lineNo()).isEqualTo(4);
        assertThat(rows.get(1).fields()).containsExactly("12", "1044", "2026-11-17", "佐川, \"急便\"", "ABC-1");
    }

    @Test void aMalformedRowIsReturnedWithoutFields() {
        var rows = WarehouseShipmentCsvParser.parse(utf8(H + "\n1,2,3\n1,2,2026-11-17,\"broken,X\n1,2,2026-11-17,a,b,c\n1,2,2026-11-17,a\"x\",b\n"));
        assertThat(rows).extracting(WarehouseShipmentCsvParser.Row::fields).containsOnlyNulls();
        assertThat(rows).extracting(WarehouseShipmentCsvParser.Row::lineText).first().isEqualTo("1,2,3");
    }

    @Test void refusesFilesThatCannotBeRead() {
        refused(new byte[0], "空");
        refused(utf8("注文番号,バッチID\n1,2\n"), "見出し");
        refused(utf8(H + "\n\n"), "データ行がありません");
        refused(new byte[] {(byte) 0xff, (byte) 0xfe, 0x41}, "UTF-8");
        refused(new byte[WarehouseShipmentCsvParser.MAX_BYTES + 1], "1MB");
        var many = new StringBuilder(H).append('\n');
        for (int i = 0; i <= WarehouseShipmentCsvParser.MAX_ROWS; i++) many.append("1,").append(i + 1).append(",2026-11-17,a,b\n");
        refused(utf8(many.toString()), "多すぎます");
    }

    @Test void longLinesAreTruncatedForDisplay() {
        String carrier = "x".repeat(2000);
        var row = WarehouseShipmentCsvParser.parse(utf8(H + "\n1,2,2026-11-17," + carrier + ",b\n")).getFirst();
        assertThat(row.lineText()).hasSize(WarehouseShipmentCsvParser.MAX_LINE_TEXT);
        assertThat(row.fields()).hasSize(5);
    }

    @Test void fileNamesAreReducedToASafeBaseName() {
        assertThat(WarehouseShipmentImportService.fileName("C:\\tmp\\shipped.csv")).isEqualTo("shipped.csv");
        assertThat(WarehouseShipmentImportService.fileName("../a/b\u0007c.csv")).isEqualTo("bc.csv");
        assertThat(WarehouseShipmentImportService.fileName(null)).isEqualTo("shipped.csv");
        assertThat(WarehouseShipmentImportService.fileName("x".repeat(300))).hasSize(255);
        assertThat(List.of(WarehouseShipmentCsvParser.HEADER.split(","))).hasSize(WarehouseShipmentCsvParser.COLUMNS);
    }

    static void refused(byte[] content, String message) {
        assertThatThrownBy(() -> WarehouseShipmentCsvParser.parse(content))
                .isInstanceOf(WarehouseShipmentCsvParser.InvalidFileException.class).hasMessageContaining(message);
    }
}
