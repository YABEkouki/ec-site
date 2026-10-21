package com.example.ecsite.service.payment.safety.lifecycle;

import java.sql.*;
import java.util.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.payment.safety.*;
import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.payload.ObservationLedgerSnapshot.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;
import com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;

/** Observer-only immutable storage. Never owns a connection or authorizes finance. */
final class JdbcObservationAssessmentStore {
    static final class Problem extends IllegalArgumentException {
        final Failure failure;
        Problem(Failure failure){super("Observation stage failed");this.failure=failure;}
    }
    record Candidate(long id,UUID requestId,AssessmentRecordDigestV1.Record record,PaymentSafetyPayloadCodecV1.Payloads payloads) {
        @Override public String toString(){return "AssessmentCandidate[redacted]";}
    }
    private final PaymentSafetyPayloadCodecV1 codec=new PaymentSafetyPayloadCodecV1();
    AssessmentRecordDigestV1.Record observe(Connection c,ObservationRunRequest request,long order,int attempt)throws SQLException {
        var m=request.manifest();var d=PaymentSafetyReadContext.Limits.defaults();
        var limits=new PaymentSafetyReadContext.Limits(d.payments(),d.transactions(),d.statusHistory(),d.captureRequests(),d.inputBytes(),d.fetchSize(),m.policy().statementTimeoutSeconds(),m.policy().lockTimeoutMillis());
        PaymentSafetyReadFacts facts;
        try {facts=new JdbcPaymentSafetySnapshotReader().readLocked(c,order,new PaymentSafetyReadContext(limits,m.verificationProfiles(),m.timeOrigins())).orElse(null);}
        catch(SQLException failure){throw new Problem(new Failure(Stage.ACQUISITION,Code.READ_FAILED));}
        catch(RuntimeException failure){throw new Problem(new Failure(Stage.ACQUISITION,Code.MAPPING_FAILED));}
        if(facts==null)throw new Problem(null);
        return classify(request,attempt,facts);
    }
    private AssessmentRecordDigestV1.Record classify(ObservationRunRequest request,int attempt,PaymentSafetyReadFacts facts) {
        var m=request.manifest();PaymentSafetySnapshotAssembly assembly;
        try{assembly=new PaymentSafetySnapshotAssembler().assemble(facts,m.captureProfiles());}
        catch(RuntimeException failure){throw new Problem(new Failure(Stage.ASSEMBLY,Code.UNREPRESENTABLE_INPUT));}
        PaymentSafetyClassification result;
        try{result=new PaymentSafetyClassifier().classify(assembly.snapshot());}
        catch(RuntimeException failure){throw new Problem(new Failure(Stage.CLASSIFICATION,Code.CLASSIFICATION_FAILED));}
        var evidence=assembly.evidence();
        int captures=evidence.captures().size(),requests=facts.captureRequests().size();String summary="VERIFIED";
        if(captures==0 && requests==0)summary="NOT_APPLICABLE";
        else if(!evidence.globalFindings().isEmpty() || evidence.captures().stream().anyMatch(x->x.verification()==PaymentSafetySnapshot.CaptureVerification.MISMATCH))summary="MISMATCH";
        else if(evidence.captures().stream().anyMatch(x->x.verification()==PaymentSafetySnapshot.CaptureVerification.MISSING))summary="MISSING";
        else if(evidence.captures().stream().anyMatch(x->x.verification()==PaymentSafetySnapshot.CaptureVerification.UNVERIFIED))summary="UNVERIFIED";
        var o=facts.order();var observed=new AssessmentRecordDigestV1.ObservedValues(o.status(),o.totalAmount(),o.contentRevision(),o.activePaymentId(),captures,requests,summary,"NOT_EVALUATED");
        return new AssessmentRecordDigestV1.Record(request.requestId(),o.id(),attempt,"OBSERVED",null,m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),ObservationRunDigestsV1.manifestDigest(m),facts,evidence,result,observed);
    }
    Candidate prepare(Connection c,UUID uuid,AssessmentRecordDigestV1.Record record)throws SQLException {
        Objects.requireNonNull(uuid);long id;
        try(var q=c.createStatement();var r=q.executeQuery("select nextval(pg_get_serial_sequence('payment_safety_assessments','id'))")){if(!r.next())throw corrupt();id=r.getLong(1);}
        try{return new Candidate(id,uuid,record,codec.encodeAssessment(record));}
        catch(IllegalArgumentException failure){throw new Problem(new Failure(Stage.PERSISTENCE,Code.CODEC_FAILED));}
    }
    List<VerifiedAssessment> references(Connection c,ObservationRunRequest request)throws SQLException {
        var refs=new ArrayList<VerifiedAssessment>();long bytes=0;
        try(var q=c.prepareStatement("select * from payment_safety_assessments where run_id=? order by id limit 1001")) {
            q.setObject(1,request.requestId());q.setFetchSize(1);
            try(var r=q.executeQuery()){while(r.next()) {
                if(refs.size()==1000)throw corrupt();var row=read(r,request);bytes+=size(row.payloads());if(bytes>96L*1024*1024)throw corrupt();
                refs.add(new VerifiedAssessment(row.record().runId(),row.record().orderId(),row.record().attemptNo(),row.id(),row.payloads().recordDigest(),Disposition.valueOf(row.record().classification().disposition().name())));
            }}
        }return List.copyOf(refs);
    }
    ObservationRunReceipt.Status replay(Connection c,ObservationRunRequest request,Candidate expected)throws SQLException {
        Candidate actual=null;
        try(var q=c.prepareStatement("select * from payment_safety_assessments where id=? or request_id=? or (run_id=? and order_id=? and attempt_no=? and phase='OBSERVED')")) {
            q.setLong(1,expected.id());q.setObject(2,expected.requestId());q.setObject(3,request.requestId());q.setLong(4,expected.record().orderId());q.setInt(5,expected.record().attemptNo());
            try(var r=q.executeQuery()){while(r.next()) {if(!request.requestId().equals(r.getObject("run_id",UUID.class)))return CONTENT_CONFLICT;if(actual!=null)return CONTENT_CONFLICT;actual=read(r,request);}}
        }
        return actual==null?NOT_APPLIED:same(actual,expected)?REPLAYED:CONTENT_CONFLICT;
    }
    static boolean same(Candidate a,Candidate b){return a.id()==b.id() && a.requestId().equals(b.requestId()) && a.record().equals(b.record()) && a.payloads().recordDigest().equals(b.payloads().recordDigest());}
    private Candidate read(ResultSet r,ObservationRunRequest request)throws SQLException {
        if(!"OBSERVED".equals(r.getString("phase")) || r.getObject("pre_assessment_id")!=null || r.getInt("payload_format_version")!=1)throw corrupt();
        String input=r.getString("input_payload"),classification=r.getString("classification_details"),evidence=r.getString("capture_evidence_details");
        var payloads=new PaymentSafetyPayloadCodecV1.Payloads(input,classification,evidence,PaymentSafetyInputFingerprint.sha256(codec.decodeInput(input)),r.getString("input_fingerprint"),r.getString("record_digest"));
        var observed=new AssessmentRecordDigestV1.ObservedValues(r.getString("observed_order_status"),r.getInt("observed_order_total_amount"),r.getInt("observed_order_content_revision"),r.getObject("observed_active_payment_id",Long.class),r.getInt("capture_transaction_count"),r.getInt("capture_request_count"),r.getString("capture_evidence_summary"),r.getString("financial_resumption"));
        var header=new AssessmentRecordDigestV1.Header(r.getObject("run_id",UUID.class),r.getLong("order_id"),r.getInt("attempt_no"),"OBSERVED",null,r.getString("classification_contract_version"),r.getString("classifier_impl_version"),r.getShort("fingerprint_format_version"),r.getString("contract_manifest_digest"),observed);
        var record=codec.decodeAssessment(header,payloads);
        if(!record.equals(classify(request,record.attemptNo(),record.facts())) || !record.classification().disposition().name().equals(r.getString("disposition")) || !Objects.equals(record.classification().activePaymentId(),r.getObject("selected_payment_id",Long.class))
            || !record.facts().timeOrigins().equals(request.manifest().timeOrigins()) || !record.facts().verificationProfiles().equals(request.manifest().verificationProfiles()))throw corrupt();
        return new Candidate(r.getLong("id"),r.getObject("request_id",UUID.class),record,payloads);
    }
    private long size(PaymentSafetyPayloadCodecV1.Payloads p){return (long)p.input().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+p.classification().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+p.evidence().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    void insert(Connection c,Candidate candidate)throws SQLException {
        var r=candidate.record();var p=candidate.payloads();var o=r.observed();
        try(var q=c.prepareStatement("insert into payment_safety_assessments(id,request_id,record_digest,order_id,run_id,attempt_no,phase,input_fingerprint,fingerprint_format_version,classification_contract_version,classifier_impl_version,contract_manifest_digest,disposition,selected_payment_id,financial_resumption,observed_order_status,observed_order_total_amount,observed_order_content_revision,observed_active_payment_id,capture_transaction_count,capture_request_count,capture_evidence_summary,payload_format_version,input_payload,classification_details,capture_evidence_details) values (?,?,?,?,?,?,'OBSERVED',?,?,?,?,?,?,?,'NOT_EVALUATED',?,?,?,?,?,?,?,1,?::jsonb,?::jsonb,?::jsonb)")) {
            q.setLong(1,candidate.id());q.setObject(2,candidate.requestId());q.setString(3,p.recordDigest());q.setLong(4,r.orderId());q.setObject(5,r.runId());q.setInt(6,r.attemptNo());q.setString(7,p.finalFingerprint());q.setShort(8,r.fingerprintFormatVersion());q.setString(9,r.classificationContractVersion());q.setString(10,r.classifierImplVersion());q.setString(11,r.contractManifestDigest());q.setString(12,r.classification().disposition().name());q.setObject(13,r.classification().activePaymentId());q.setString(14,o.orderStatus());q.setInt(15,o.orderTotalAmount());q.setInt(16,o.orderContentRevision());q.setObject(17,o.activePaymentId());q.setInt(18,o.captureTransactionCount());q.setInt(19,o.captureRequestCount());q.setString(20,o.captureEvidenceSummary());q.setString(21,p.input());q.setString(22,p.classification());q.setString(23,p.evidence());
            if(q.executeUpdate()!=1)throw corrupt();
        }
    }
    private static JdbcObservationRunRepository.IntegrityFailure corrupt(){return new JdbcObservationRunRepository.IntegrityFailure();}
}
