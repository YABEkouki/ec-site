package com.example.ecsite.service.warehouse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Feature137-B: puts the two files of one output into the folder collected by FTP (docs ch.4.2). No database work.
 * Each file is written and flushed in the work folder, then moved atomically into the output folder, the detail
 * file first and the header file last, so a header in the output folder means its detail is there too (no
 * completion file, decision 6). A file that already exists with the same bytes counts as written (a retry after a
 * lost outcome); a file with other bytes is never overwritten and the attempt fails.
 */
@Component
public class WarehouseFileWriter {

    public record Written(byte[] headerSha256, byte[] detailSha256) {}

    /** The reason is shown to administrators (no secrets: only folder and file names). */
    public static class WriteException extends RuntimeException {
        public WriteException(String message, Throwable cause) { super(message, cause); }
    }

    public Written write(WarehouseFileFormat.Content content, Path outputDir, Path workDir) {
        requireFolders(outputDir, workDir);
        var header = content.headerFile();
        var detail = content.detailFile();
        place(detail, outputDir, workDir);
        place(header, outputDir, workDir);
        syncDirectory(outputDir);
        return new Written(WarehouseExportCsvService.sha256(header.content()), WarehouseExportCsvService.sha256(detail.content()));
    }

    static void requireFolders(Path outputDir, Path workDir) {
        if (outputDir == null || workDir == null) throw new WriteException("出力フォルダーと作業フォルダーの設定が必要です。", null);
        if (!Files.isDirectory(outputDir)) throw new WriteException("出力フォルダーがありません：" + outputDir, null);
        if (!Files.isDirectory(workDir)) throw new WriteException("作業フォルダーがありません：" + workDir, null);
        try {
            if (Files.isSameFile(outputDir, workDir)) {
                throw new WriteException("作業フォルダーは出力フォルダーと別の場所にしてください。", null);
            }
            if (!Files.getFileStore(outputDir).equals(Files.getFileStore(workDir))) {
                throw new WriteException("作業フォルダーは出力フォルダーと同じファイルシステムにしてください。", null);
            }
        } catch (IOException e) {
            throw new WriteException("フォルダーを確認できません：" + e.getClass().getSimpleName(), e);
        }
    }

    private static void place(WarehouseFileFormat.File file, Path outputDir, Path workDir) {
        Path target = outputDir.resolve(file.filename());
        if (!target.getParent().equals(outputDir) || !target.getFileName().toString().equals(file.filename())) {
            throw new WriteException("ファイル名が不正です：" + file.filename(), null);
        }
        if (sameFileExists(target, file.content())) return;
        Path temp = workDir.resolve(file.filename() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(file.content());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Checked again right before the move: a file appearing meanwhile is never replaced by a different one.
            if (sameFileExists(target, file.content())) return;
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new WriteException("作業フォルダーから出力フォルダーへ一括で移動できません（同じファイルシステムにしてください）。", e);
        } catch (IOException e) {
            throw new WriteException("ファイルを書き出せません：" + file.filename() + "（" + e.getClass().getSimpleName() + "）", e);
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* a leftover temporary file is harmless */ }
        }
    }

    /** True when the target holds exactly these bytes; refuses a different existing file. */
    private static boolean sameFileExists(Path target, byte[] content) {
        if (!Files.exists(target)) return false;
        try {
            if (Arrays.equals(Files.readAllBytes(target), content)) return true;
        } catch (IOException e) {
            throw new WriteException("既存のファイルを確認できません：" + target.getFileName() + "（" + e.getClass().getSimpleName() + "）", e);
        }
        throw new WriteException("同じ名前で内容の違うファイルが出力フォルダーにあります（上書きしません）：" + target.getFileName(), null);
    }

    private static void syncDirectory(Path dir) {
        // Best effort: makes the renames durable on Linux; some file systems do not allow opening a directory.
        try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The files themselves were flushed before the move.
        }
    }
}
