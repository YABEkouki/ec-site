package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.ecsite.config.WarehouseFileProperties;

/** Feature137-B: placing the two warehouse files into the folder collected by FTP (docs ch.4.2). */
class WarehouseFileWriterTest {

    @TempDir Path dir;
    Path out, work;
    final WarehouseFileWriter writer = new WarehouseFileWriter();
    final WarehouseFileFormat.Content content = WarehouseFileFormatTest.sample("15");

    @BeforeEach void folders() throws IOException {
        out = Files.createDirectory(dir.resolve("out"));
        work = Files.createDirectory(dir.resolve("work"));
    }

    @Test void writesBothFilesAndLeavesNoTemporaryFile() throws IOException {
        var written = writer.write(content, out, work);
        byte[] header = Files.readAllBytes(out.resolve("warehouse-header-20261121-15.csv"));
        byte[] detail = Files.readAllBytes(out.resolve("warehouse-detail-20261121-15.csv"));
        assertThat(header).isEqualTo(content.headerFile().content());
        assertThat(detail).isEqualTo(content.detailFile().content());
        assertThat(written.headerSha256()).isEqualTo(WarehouseExportCsvService.sha256(header));
        assertThat(written.detailSha256()).isEqualTo(WarehouseExportCsvService.sha256(detail));
        try (var files = Files.list(out)) { assertThat(files).hasSize(2); }
        try (var files = Files.list(work)) { assertThat(files).isEmpty(); }
    }

    @Test void identicalExistingFilesCountAsWritten() throws IOException {
        writer.write(content, out, work);
        var modified = Files.getLastModifiedTime(out.resolve("warehouse-header-20261121-15.csv"));
        var again = writer.write(content, out, work);
        assertThat(again.headerSha256()).isEqualTo(WarehouseExportCsvService.sha256(content.headerFile().content()));
        assertThat(Files.getLastModifiedTime(out.resolve("warehouse-header-20261121-15.csv"))).isEqualTo(modified);
    }

    @Test void aDifferentExistingFileIsNeverOverwritten() throws IOException {
        Files.writeString(out.resolve("warehouse-header-20261121-15.csv"), "other");
        assertThatThrownBy(() -> writer.write(content, out, work))
                .isInstanceOf(WarehouseFileWriter.WriteException.class)
                .hasMessageContaining("上書きしません").hasMessageContaining("warehouse-header-20261121-15.csv");
        assertThat(Files.readString(out.resolve("warehouse-header-20261121-15.csv"))).isEqualTo("other");
        // The detail file goes first, so it is already in place; a later attempt finds it identical.
        assertThat(Files.readAllBytes(out.resolve("warehouse-detail-20261121-15.csv"))).isEqualTo(content.detailFile().content());
        try (var files = Files.list(work)) { assertThat(files).isEmpty(); }
    }

    @Test void refusesMissingOrIdenticalFolders() {
        assertThatThrownBy(() -> writer.write(content, dir.resolve("missing"), work))
                .isInstanceOf(WarehouseFileWriter.WriteException.class).hasMessageContaining("出力フォルダーがありません");
        assertThatThrownBy(() -> writer.write(content, out, dir.resolve("missing")))
                .isInstanceOf(WarehouseFileWriter.WriteException.class).hasMessageContaining("作業フォルダーがありません");
        assertThatThrownBy(() -> writer.write(content, out, out))
                .isInstanceOf(WarehouseFileWriter.WriteException.class).hasMessageContaining("別の場所");
        assertThatThrownBy(() -> writer.write(content, null, work)).isInstanceOf(WarehouseFileWriter.WriteException.class);
    }

    @Test void anEmptyOutputWritesHeaderOnlyFiles() throws IOException {
        writer.write(new WarehouseFileFormat.Content("16", WarehouseFileFormatTest.DAY, java.util.List.of(), java.util.List.of()), out, work);
        assertThat(Files.readAllBytes(out.resolve("warehouse-header-20261121-16.csv")))
                .isEqualTo(("﻿" + WarehouseFileFormat.HEADER_COLUMNS + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(out.resolve("warehouse-detail-20261121-16.csv")))
                .isEqualTo(("﻿" + WarehouseFileFormat.DETAIL_COLUMNS + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void enablingRequiresBothFoldersAndSaneLimits() {
        assertThatCode(() -> properties(false, "", "", 12)).doesNotThrowAnyException();
        assertThatThrownBy(() -> properties(true, "", "/w", 12)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WAREHOUSE_FILE_OUTPUT_DIR");
        assertThatThrownBy(() -> properties(true, "/o", " ", 12)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(false, "", "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(properties(true, " /o ", "/w", 12).outputDir()).isEqualTo("/o");
    }

    static WarehouseFileProperties properties(boolean enabled, String output, String work, int maxAttempts) {
        return new WarehouseFileProperties(enabled, output, work, Duration.ofMinutes(1), Duration.ofMinutes(5), maxAttempts,
                Duration.ofMinutes(5), 20);
    }

    @Test void onlyOwnMessagesAreShownAsFailureReasons() {
        assertThat(WarehouseFileOutputService.reason(new WarehouseFileWriter.WriteException("出力フォルダーがありません：/x", null)))
                .isEqualTo("出力フォルダーがありません：/x");
        assertThat(WarehouseFileOutputService.reason(new org.springframework.dao.DataIntegrityViolationException("secret order data")))
                .isEqualTo("出力処理のエラー（DataIntegrityViolationException）");
    }
}
