package com.example.ecsite.service.warehouse;

import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.config.WarehouseFileProperties;

/**
 * Feature137-B: one writing pass. Each output is claimed, read and recorded in its own short transaction; the files
 * are written between them with no transaction, connection or lock held (external I/O outside the database
 * transaction). A failed attempt is retried after the retry delay and becomes FAILED after the last attempt;
 * an administrator can then re-output it. If recording the outcome fails, the claim expires and the next pass
 * writes again: the same file names and bytes, so an existing identical file counts as written (docs ch.4.2, D).
 */
@Service
public class WarehouseFileOutputService {
    private static final Logger log = LoggerFactory.getLogger(WarehouseFileOutputService.class);

    private final UUID owner = UUID.randomUUID();
    private final WarehouseFileOutputStore store;
    private final WarehouseFileSource source;
    private final WarehouseFileWriter writer;
    private final WarehouseFileProperties properties;

    public WarehouseFileOutputService(WarehouseFileOutputStore store, WarehouseFileSource source, WarehouseFileWriter writer,
            WarehouseFileProperties properties) {
        this.store = store; this.source = source; this.writer = writer; this.properties = properties;
    }

    /** Returns how many outputs this pass attempted. Safe to repeat. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int runPass() {
        int handled = 0;
        while (handled < properties.batchSize()) {
            var claim = store.claim(owner, properties.claimLease());
            if (claim.isEmpty()) break;
            handled++;
            attempt(claim.get());
        }
        return handled;
    }

    private void attempt(WarehouseFileOutputStore.Claimed claim) {
        try {
            var content = claim.kind() == WarehouseFileOutputStore.Kind.REGULAR
                    ? source.forBatch(claim.batchId()) : source.forEmergency(claim.emergencyRecordId());
            var header = content.headerFile();
            var detail = content.detailFile();
            if (!header.filename().equals(claim.headerFilename()) || !detail.filename().equals(claim.detailFilename())
                    || header.rowCount() != claim.headerRows() || detail.rowCount() != claim.detailRows()) {
                throw new IllegalStateException("出力内容が出力記録（ファイル名・行数）と一致しません。");
            }
            var written = writer.write(content, Path.of(properties.outputDir()), Path.of(properties.workDir()));
            if (store.markWritten(claim.id(), owner, written.headerSha256(), written.detailSha256())) {
                log.info("Warehouse files written. outputId={}, header={}, detail={}, headerRows={}, detailRows={}",
                        claim.id(), claim.headerFilename(), claim.detailFilename(), claim.headerRows(), claim.detailRows());
            } else {
                log.warn("Warehouse files written but the claim was lost; another pass records them. outputId={}", claim.id());
            }
        } catch (RuntimeException failure) {
            String reason = reason(failure);
            boolean recorded = store.markFailed(claim.id(), owner, reason, properties.maxAttempts(), properties.retryDelay());
            log.warn("Warehouse file output attempt failed. outputId={}, attempt={}, recorded={}, reason={}",
                    claim.id(), claim.attempts() + 1, recorded, reason);
        }
    }

    /** Our own messages are shown as is; any other error only by its type (SQL text may contain order data). */
    static String reason(RuntimeException failure) {
        if (failure instanceof WarehouseFileWriter.WriteException || failure instanceof WarehouseFileSource.NotWritableException
                || (failure instanceof IllegalStateException && failure.getMessage() != null)) {
            return failure.getMessage();
        }
        return "出力処理のエラー（" + failure.getClass().getSimpleName() + "）";
    }
}
