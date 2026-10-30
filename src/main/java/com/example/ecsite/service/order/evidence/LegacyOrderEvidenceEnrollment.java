package com.example.ecsite.service.order.evidence;

import static com.example.ecsite.service.order.evidence.OrderContentSnapshotFactory.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.payjp.OrderAuthorizationObservation;
import com.example.ecsite.service.order.evidence.JdbcOrderPaymentEvidenceReader.Read;

/** Explicit internal enrollment only. No bean, controller, scheduler or financial POST connection.
 * Every exception after write begins may be an ambiguous commit; caller must re-read, never
 * infer failure/success or retry finance. A repeat call returns the existing verified epoch. */
public final class LegacyOrderEvidenceEnrollment {
    public enum Status { ENROLLED, ALREADY_ENROLLED, NOT_FOUND, BLOCKED, REVIEW_REQUIRED, CONFLICT, OBSERVATION_UNAVAILABLE, COMMIT_UNKNOWN }
    public record Result(Status status,UUID epochId) {}
    private final JdbcTemplate jdbc;
    private final JdbcOrderPaymentEvidenceReader reader;
    private final TransactionTemplate readTx,writeTx;
    private final PaymentGateway gateway;
    private final CaptureConnectionIdentity connection;
    private final OrderContentSnapshotCodec codec=new OrderContentSnapshotCodec();
    public LegacyOrderEvidenceEnrollment(DataSource source,PaymentGateway gateway,CaptureConnectionIdentity connection) {
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);reader=new JdbcOrderPaymentEvidenceReader(jdbc);this.gateway=Objects.requireNonNull(gateway);this.connection=Objects.requireNonNull(connection);
        var manager=new DataSourceTransactionManager(source);
        readTx=new TransactionTemplate(manager);readTx.setReadOnly(true);readTx.setTimeout(5);readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writeTx=new TransactionTemplate(manager);writeTx.setTimeout(5);writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Result enroll(long orderId) {
        if(orderId<=0||TransactionSynchronizationManager.isActualTransactionActive())return result(Status.BLOCKED);
        final Read before;
        try {before=readTx.execute(s->reader.read(orderId));}catch(RuntimeException e){return result(Status.REVIEW_REQUIRED);}
        var existing=existing(before);if(existing!=null)return existing;
        try {if(!readTx.execute(s->eligible(before)))return result(Status.BLOCKED);}catch(RuntimeException e){return result(Status.REVIEW_REQUIRED);}
        final OrderAuthorizationObservation.Expected expected;
        final OrderAuthorizationObservation observation;
        try {expected=expected(before);observation=gateway.observeOrderAuthorization(expected);if(observation==null)return result(Status.OBSERVATION_UNAVAILABLE);}
        catch(RuntimeException e){return result(Status.OBSERVATION_UNAVAILABLE);}
        try {
            return writeTx.execute(s->{
                var now=reader.readLocked(orderId);var repeated=existing(now);if(repeated!=null)return repeated;
                if(!Objects.equals(before.localFingerprint(),now.localFingerprint()))return result(Status.CONFLICT);
                if(!eligible(now))return result(Status.BLOCKED);
                try {observation.consume(expected(now));}catch(RuntimeException invalidObservation){return result(Status.OBSERVATION_UNAVAILABLE);}
                var f=now.facts();var a=f.authorizations().stream().filter(x->x.id()==now.decision().authorizationId()).findFirst().orElseThrow();
                var receiptCodec=new OrderCheckpointReceiptCodec();
                var receipt=new OrderCheckpointReceiptCodec.Receipt(1,orderId,a.paymentId(),a.id(),f.current().contentRevision(),codec.fingerprint(f.current()),
                    now.localAssessment(),OrderCheckpointReceiptCodec.hash(now.localAssessment()),observation.payload(),OrderCheckpointReceiptCodec.hash(observation.payload()));
                final byte[] payload;
                try {payload=receiptCodec.encode(receipt);}catch(IllegalArgumentException oversizedReceipt){return result(Status.REVIEW_REQUIRED);}
                byte[] snapshot=codec.encode(f.current());UUID id=UUID.randomUUID();
                var epoch=new OrderPaymentEvidence.Epoch(1,id,orderId,a.paymentId(),a.id(),a.amount(),a.revision(),OrderPaymentEvidence.Origin.LEGACY_CURRENT_CHECKPOINT,
                    f.current(),reauthorizationForUnenrolled(now,a),receiptCodec.checkpoint(receipt),null);
                String digest=OrderEvidenceDigests.epoch(epoch);
                jdbc.update("""
                    insert into order_payment_evidence_epochs(id,order_id,payment_id,authorization_transaction_id,origin_kind,
                      start_content_revision,start_total_amount,authorized_amount,authorization_revision,provider_flow_id,
                      start_snapshot,start_fingerprint,observation_payload,observation_fingerprint,observation_started_at,observation_finished_at,evidence_version,evidence_digest)
                    values (?,?,?,?,'LEGACY_CURRENT_CHECKPOINT',?,?,?,?,?,?,?,?,?,?,?,1,?)
                    """,id,orderId,a.paymentId(),a.id(),f.current().contentRevision(),f.current().totalAmount(),a.amount(),a.revision(),a.flowId(),
                    snapshot,hex(codec.fingerprint(f.current())),payload,hex(OrderEvidenceDigests.sha256(payload)),OffsetDateTime.ofInstant(observation.startedAt(),ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(observation.finishedAt(),ZoneOffset.UTC),hex(digest));
                if(jdbc.update("update orders set current_evidence_epoch_id=? where id=? and current_evidence_epoch_id is null",id,orderId)!=1)throw new IllegalStateException("Enrollment conflict");
                var saved=reader.readLocked(orderId);
                if(saved.state()!=JdbcOrderPaymentEvidenceReader.State.READ||!saved.decision().locallyVerified())throw new IllegalStateException("Saved evidence did not verify");
                observation.requireConsumed(expected);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void beforeCommit(boolean readOnly){observation.requireConsumed(expected);if(!eligibleState(now))throw new IllegalStateException("Admission expired");}
                });
                return new Result(Status.ENROLLED,id);
            });
        } catch(org.springframework.dao.ConcurrencyFailureException e){return result(Status.CONFLICT);}
        catch(RuntimeException e){return result(Status.COMMIT_UNKNOWN);}
    }
    private Result existing(Read r) {
        if(r==null||r.state()==JdbcOrderPaymentEvidenceReader.State.INVALID)return result(Status.REVIEW_REQUIRED);
        if(r.state()==JdbcOrderPaymentEvidenceReader.State.NOT_FOUND)return result(Status.NOT_FOUND);
        if(r.state()!=JdbcOrderPaymentEvidenceReader.State.READ)return result(Status.CONFLICT);
        if(r.facts().evidenceRequired())return r.decision().locallyVerified()?new Result(Status.ALREADY_ENROLLED,r.facts().epoch().id()):result(Status.REVIEW_REQUIRED);
        return null;
    }
    private boolean eligible(Read r) {
        return r.decision().disposition()==OrderPaymentEvidenceDecision.Disposition.LEGACY_UNENROLLED
            &&r.facts().current().totalAmount()<=r.decision().authorizedAmount()&&eligibleState(r);
    }
    private boolean eligibleState(Read r) {
        var o=r.order();
        LocalDateTime dbNow=jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",LocalDateTime.class);
        return "ORDERED".equals(t(o,"status"))&&nullableText(o,"shipping_preparation_started_at")==null
            &&LocalDateTime.parse(t(o,"change_deadline_at")).isAfter(dbNow);
    }
    private OrderAuthorizationObservation.Expected expected(Read r) {
        var a=r.facts().authorizations().stream().filter(x->x.id()==r.decision().authorizationId()).findFirst().orElseThrow();
        return new OrderAuthorizationObservation.Expected(r.facts().current().orderId(),a.paymentId(),a.id(),a.flowId(),a.amount(),a.revision(),r.localFingerprint(),connection,a.operationId());
    }
    private OrderPaymentEvidence.Reauthorization reauthorizationForUnenrolled(Read r,OrderPaymentEvidence.Authorization a) {
        // A changed-payment generation needs the original completed-operation/cancellation facts;
        // the reader resolves them, never infers them from amounts or revisions.
        return reader.reauthorizationFor(r.facts().current().orderId(),a);
    }
    private static byte[] hex(String s){return HexFormat.of().parseHex(s);}
    private static Result result(Status s){return new Result(s,null);}
}
