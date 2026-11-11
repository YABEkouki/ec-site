package com.example.ecsite.service.order.evidence;

import java.util.*;
import javax.sql.DataSource;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;

/** Internal receipt coordinator only; deliberately NOT a bean, endpoint, scheduler or order writer.
 * Independent RC transactions, no gateway dependency. Production remains fenced off by V71.
 * A failed commit response never implies rollback; recover() reads durable evidence by the same key. */
public final class OrderEditAdmissionService {
    public enum Outcome { ACCEPTED, REJECTED_LATE, UNPROVEN, BLOCKED, CONFLICT, NOT_FOUND, RUNTIME_UNAVAILABLE, COMMIT_UNKNOWN }
    public record Command(long customerId,UUID submissionKey,Request request,Candidate confirmed) {
        public Command {if(customerId<=0||submissionKey==null||request==null||confirmed==null)throw new IllegalArgumentException("Incomplete admission command");}
        @Override public String toString(){return "AdmissionCommand[redacted]";}
    }
    /** DB states are observed facts, not aliases for the coordinator's outcome. */
    public record Result(Outcome outcome,UUID admissionId,String databaseAdmissionState,String databaseExecutionState,String reason) {}
    private record Row(UUID id,long order,long customer,UUID key,UUID generation,String xid,byte[] request,byte[] candidate,String admission,String execution,Binding binding,Proof proof,Financial financial) {}
    private record Financial(String audit,byte[] canonical,byte[] digest,String binding,byte[] bindingCanonical,byte[] bindingDigest) {}
    private record Binding(int revision,UUID epoch,Long head,byte[] fingerprint,long payment,long authorization,
            int authorizedAmount,int authorizationRevision,int before,int after,int requestVersion,int candidateVersion,
            String route,byte[] requestDigest,byte[] candidateDigest,byte[] beforeSnapshot,byte[] epochDigest,byte[] headDigest,String flow,java.time.Instant deadline) {}
    private record Proof(UUID generation,String originalXid,String verificationXid,String decision,java.time.Instant commit,
            java.time.Instant deadline,byte[] requestDigest,byte[] candidateDigest,String before,String after,int version) {}
    private static final class Denied extends RuntimeException {
        final Outcome outcome;final String reason;
        Denied(Outcome outcome,String reason){this.outcome=outcome;this.reason=reason;}
    }
    private static final class UntrustedReceipt extends RuntimeException {
        final Row row;UntrustedReceipt(Row row){this.row=row;}
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTx,readTx;
    private final JdbcOrderPaymentEvidenceReader evidence;
    private final OrderEditAdmissionGuard gate;
    private final OrderEditAdmissionPayloadCodec payloads=new OrderEditAdmissionPayloadCodec();
    private final OrderEditCandidateCalculator calculator=new OrderEditCandidateCalculator();

    public OrderEditAdmissionService(DataSource source){this(source,new DataSourceTransactionManager(source));}
    /** The supplied manager must bind this same DataSource; also permits isolated commit-response fault tests. */
    public OrderEditAdmissionService(DataSource source,PlatformTransactionManager manager) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));jdbc.setQueryTimeout(5);
        evidence=new JdbcOrderPaymentEvidenceReader(jdbc);gate=new OrderEditAdmissionGuard(jdbc);
        writeTx=template(manager,false);readTx=template(new DataSourceTransactionManager(source),true);
    }
    private static TransactionTemplate template(PlatformTransactionManager manager,boolean readOnly) {
        var t=new TransactionTemplate(manager);t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);t.setReadOnly(readOnly);t.setTimeout(10);return t;
    }
    private static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Admission coordinator must run outside a caller transaction");
    }
    public Result submit(Command command) {
        outsideTransaction();Objects.requireNonNull(command);
        final byte[] request,candidate;final Request normalized;
        try {normalized=payloads.normalize(command.request());request=payloads.request(command.customerId(),normalized);candidate=payloads.candidate(command.confirmed());}
        catch(IllegalArgumentException invalid){return response(Outcome.BLOCKED,null,"INVALID_PAYLOAD");}
        Row row;
        try {
            // Read-only replay is permitted while STOPPED: it never creates or changes an admission.
            row=lookup(command.customerId(),normalized.orderId(),command.submissionKey());
            if(row!=null){same(row,request,candidate);return resume(row,false);}
            row=writeTx.execute(s->{
                UUID generation=runtime(); // runtime -> Order -> operation -> Payment
                lockOrder(normalized.orderId(),command.customerId());
                var repeated=find(command.customerId(),normalized.orderId(),command.submissionKey());
                if(repeated!=null){same(repeated,request,candidate);return repeated;}
                try {gate.requireClear(normalized.orderId());}
                catch(com.example.ecsite.exception.InvalidOrderStatusException conflict){throw new Denied(Outcome.CONFLICT,"OTHER_UNRESOLVED_ADMISSION");}
                var read=evidence.readLocked(normalized.orderId());
                if(read.state()!=JdbcOrderPaymentEvidenceReader.State.READ||!read.decision().locallyVerified())throw new Denied(Outcome.BLOCKED,"INVALID_SAVED_EVIDENCE");
                var now=jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class);
                var context=OrderEditCandidateSources.context(read,command.customerId(),now);
                var result=calculator.calculateForAdmission(context,normalized,catalog(normalized));
                if(result.route()!=OrderChangeRoutePolicy.Route.RETAIN_AUTH&&result.route()!=OrderChangeRoutePolicy.Route.REAUTHORIZE)throw new Denied(Outcome.BLOCKED,"NON_NOOP_EDIT_REQUIRED");
                if(result.route()==OrderChangeRoutePolicy.Route.REAUTHORIZE&&!Boolean.TRUE.equals(jdbc.queryForObject(
                    "select to_regprocedure('f125_reauth_initialize(uuid,uuid,bigint,bigint,uuid,bytea)') is not null",Boolean.class)))
                    throw new Denied(Outcome.BLOCKED,"REAUTHORIZATION_MIGRATION_REQUIRED");
                byte[] fresh=payloads.candidate(result.candidate());
                if(!Arrays.equals(fresh,candidate))throw new Denied(Outcome.CONFLICT,"CONFIRMATION_CHANGED");
                payloads.validateSaved(payloads.decodeRequest(request),payloads.decodeCandidate(fresh),read.facts().current());
                var p=payloads.decodeCandidate(fresh);UUID id=UUID.randomUUID();
                jdbc.update("""
                    insert into order_edit_admissions(id,order_id,customer_id,actor_username,submission_key,
                      request_version,request_payload,request_digest,candidate_version,candidate_payload,candidate_digest,
                      base_content_revision,base_evidence_epoch_id,base_evidence_history_id,base_fingerprint,
                      base_payment_id,base_authorization_id,authorized_amount,authorization_revision,before_amount,after_amount,
                      deadline_at,db_generation_id,route)
                    select ?,o.id,o.user_id,'DB supplies actor',?,1,?,sha256(?),1,?,sha256(?),?,?,?,?,?,?,?,?,?, ?,
                      o.change_deadline_at at time zone 'Asia/Tokyo',?,? from orders o where o.id=?
                    """,id,command.submissionKey(),request,request,fresh,fresh,p.revision(),p.epochId(),p.headHistoryId(),
                    HexFormat.of().parseHex(p.baseFingerprint()),p.paymentId(),p.authorizationId(),p.authorizedAmount(),p.authorizationRevision(),
                    p.beforeAmount(),p.preview().totalAmount(),generation,result.route().name(),p.orderId());
                return Objects.requireNonNull(find(command.customerId(),normalized.orderId(),command.submissionKey()));
            });
        } catch(Denied denied){return response(denied.outcome,null,denied.reason);}
        catch(UntrustedReceipt invalid){return response(Outcome.UNPROVEN,invalid.row,"SAVED_RECEIPT_INVALID");}
        catch(RuntimeException failure){
            row=safeLookup(command.customerId(),normalized.orderId(),command.submissionKey());
            return response(sqlState(failure,"55000")?Outcome.RUNTIME_UNAVAILABLE:Outcome.COMMIT_UNKNOWN,row,
                sqlState(failure,"55000")?"RUNTIME_UNVERIFIED":"TX_A_OUTCOME_UNCONFIRMED");
        }
        return resume(row,false);
    }
    /** Explicit recovery never inserts a receipt, resets the deadline or releases its gate. */
    public Result recover(long customerId,long orderId,UUID key) {
        outsideTransaction();if(customerId<=0||orderId<=0||key==null)throw new IllegalArgumentException("Invalid recovery identity");
        try {var row=lookup(customerId,orderId,key);return row==null?response(Outcome.NOT_FOUND,null,"NO_VISIBLE_RECEIPT"):resume(row,true);}
        catch(UntrustedReceipt invalid){return response(Outcome.UNPROVEN,invalid.row,"SAVED_RECEIPT_INVALID");}
        catch(RuntimeException unavailable){return response(Outcome.COMMIT_UNKNOWN,null,"LOOKUP_UNAVAILABLE");}
    }
    private Result resume(Row original,boolean retryUnproven) {
        if(original==null)return response(Outcome.COMMIT_UNKNOWN,null,"NO_VISIBLE_RECEIPT");
        if("ACCEPTED".equals(original.admission()))return response(Outcome.ACCEPTED,original,"DURABLE_DB_DECISION");
        if("REJECTED_LATE".equals(original.admission()))return response(Outcome.REJECTED_LATE,original,"DURABLE_DB_DECISION");
        if((!retryUnproven&&"UNPROVEN".equals(original.admission()))||!Set.of("NOT_READY","REVIEW_REQUIRED").contains(original.execution()))
            return response(Outcome.UNPROVEN,original,"SAVED_UNRESOLVED_STATE");
        try {
            var decided=writeTx.execute(s->{
                UUID generation=runtime();if(!generation.equals(original.generation()))throw new Denied(Outcome.RUNTIME_UNAVAILABLE,"GENERATION_MISMATCH");
                var fresh=find(original.customer(),original.order(),original.key());
                if(fresh==null||!fresh.id().equals(original.id())||!fresh.xid().equals(original.xid()))throw new Denied(Outcome.UNPROVEN,"RECEIPT_IDENTITY_CHANGED");
                jdbc.queryForObject("select f125_admission_verify(?)",String.class,original.id());
                return Objects.requireNonNull(find(original.customer(),original.order(),original.key()));
            });
            return response(switch(decided.admission()) {case "ACCEPTED"->Outcome.ACCEPTED;case "REJECTED_LATE"->Outcome.REJECTED_LATE;default->Outcome.UNPROVEN;},decided,"DURABLE_DB_DECISION");
        } catch(Denied denied){return response(denied.outcome,original,denied.reason);}
        catch(UntrustedReceipt invalid){return response(Outcome.UNPROVEN,original,"SAVED_RECEIPT_INVALID");}
        catch(RuntimeException failure) {
            if(sqlState(failure,"55000"))return response(Outcome.RUNTIME_UNAVAILABLE,original,"RUNTIME_UNVERIFIED");
            if(sqlState(failure,"23514")) {
                try {
                    var held=writeTx.execute(s->{
                        if(!runtime().equals(original.generation()))throw new Denied(Outcome.RUNTIME_UNAVAILABLE,"GENERATION_MISMATCH");
                        jdbc.queryForObject("select f125_admission_lock(?)",Object.class,original.id());
                        var current=find(original.customer(),original.order(),original.key());
                        if(current!=null&&Set.of("RECORDED_UNVERIFIED","UNPROVEN").contains(current.admission()))
                            jdbc.queryForObject("select f125_admission_hold(?,'COMMIT_EVIDENCE_UNPROVEN')",Object.class,original.id());
                        return find(original.customer(),original.order(),original.key());
                    });
                    return response(Outcome.UNPROVEN,held,"COMMIT_EVIDENCE_UNPROVEN");
                } catch(RuntimeException cannotHold){ /* no forced update or guessed gate release */ }
            }
            return response(Outcome.COMMIT_UNKNOWN,safeLookup(original.customer(),original.order(),original.key()),"TX_B_OUTCOME_UNCONFIRMED");
        }
    }
    private UUID runtime(){return jdbc.queryForObject("select f125_admission_require_runtime()",UUID.class);}
    private void lockOrder(long order,long customer) {
        if(jdbc.queryForList("select id from orders where id=? and user_id=? for update",Long.class,order,customer).size()!=1)
            throw new Denied(Outcome.BLOCKED,"ORDER_OWNERSHIP");
    }
    private List<Offer> catalog(Request request) {
        var ids=request.lines().stream().map(Line::productId).distinct().sorted().toList();
        return jdbc.query("""
            select p.id,p.name,c.id,c.name,p.price,t.id,t.code,t.name,t.tax_rate,(p.active and c.active and t.active)
            from products p join categories c on c.id=p.category_id join tax_categories t on t.id=p.tax_category_id
            where p.id in (
            """+String.join(",",Collections.nCopies(ids.size(),"?"))+") order by p.id",
            (r,n)->new Offer(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getInt(5),r.getLong(6),r.getString(7),r.getString(8),
                OrderContentSnapshotCodec.taxRateBasisPoints(r.getBigDecimal(9)),r.getBoolean(10)),ids.toArray());
    }
    private Row lookup(long customer,long order,UUID key){return readTx.execute(s->find(customer,order,key));}
    private Row safeLookup(long customer,long order,UUID key){try{return lookup(customer,order,key);}catch(RuntimeException unavailable){return null;}}
    private Row find(long customer,long order,UUID key) {
        var rows=jdbc.query("""
            select a.*,a.original_xid::text original_xid_text,
              to_jsonb(a)->>'financial_snapshot' financial_audit,
              decode(substr(to_jsonb(a)->>'financial_canonical',3),'hex') financial_bytes,
              decode(substr(to_jsonb(a)->>'financial_digest',3),'hex') financial_hash,
              to_jsonb(p)->>'financial_binding' financial_binding_audit,
              decode(substr(to_jsonb(p)->>'financial_binding_canonical',3),'hex') financial_binding_bytes,
              decode(substr(to_jsonb(p)->>'financial_binding_digest',3),'hex') financial_binding_hash,
              e.start_snapshot epoch_snapshot,e.evidence_digest epoch_digest,e.provider_flow_id epoch_flow,
              h.after_snapshot head_snapshot,h.evidence_digest head_digest,
              p.admission_id proof_id,p.db_generation_id proof_generation,p.original_xid::text proof_xid,
              p.verification_xid::text proof_verification_xid,p.decision proof_decision,p.original_commit_at proof_commit,
              p.deadline_at proof_deadline,p.request_digest proof_request_digest,p.candidate_digest proof_candidate_digest,
              p.status_before proof_before,p.status_after proof_after,p.guard_version proof_version
            from order_edit_admissions a left join order_payment_evidence_epochs e on e.id=a.base_evidence_epoch_id and e.order_id=a.order_id
            left join order_content_change_histories h on h.id=a.base_evidence_history_id and h.evidence_epoch_id=a.base_evidence_epoch_id and h.order_id=a.order_id
            left join order_edit_admission_evidences p on p.admission_id=a.id
            where a.customer_id=? and a.order_id=? and a.submission_key=?
            """,(r,n)->{
                Long head=r.getObject("base_evidence_history_id",Long.class);
                var binding=new Binding(r.getInt("base_content_revision"),r.getObject("base_evidence_epoch_id",UUID.class),head,r.getBytes("base_fingerprint"),
                    r.getLong("base_payment_id"),r.getLong("base_authorization_id"),r.getInt("authorized_amount"),r.getInt("authorization_revision"),r.getInt("before_amount"),r.getInt("after_amount"),
                    r.getInt("request_version"),r.getInt("candidate_version"),r.getString("route"),r.getBytes("request_digest"),r.getBytes("candidate_digest"),
                    r.getBytes(head==null?"epoch_snapshot":"head_snapshot"),r.getBytes("epoch_digest"),r.getBytes(head==null?"epoch_digest":"head_digest"),r.getString("epoch_flow"),
                    r.getObject("deadline_at",java.time.OffsetDateTime.class).toInstant());
                Proof proof=r.getObject("proof_id")==null?null:new Proof(r.getObject("proof_generation",UUID.class),r.getString("proof_xid"),r.getString("proof_verification_xid"),r.getString("proof_decision"),
                    r.getObject("proof_commit",java.time.OffsetDateTime.class).toInstant(),r.getObject("proof_deadline",java.time.OffsetDateTime.class).toInstant(),
                    r.getBytes("proof_request_digest"),r.getBytes("proof_candidate_digest"),r.getString("proof_before"),r.getString("proof_after"),r.getInt("proof_version"));
                return new Row(r.getObject("id",UUID.class),r.getLong("order_id"),r.getLong("customer_id"),r.getObject("submission_key",UUID.class),r.getObject("db_generation_id",UUID.class),
                    r.getString("original_xid_text"),r.getBytes("request_payload"),r.getBytes("candidate_payload"),r.getString("admission_state"),r.getString("execution_state"),binding,proof,new Financial(r.getString("financial_audit"),r.getBytes("financial_bytes"),r.getBytes("financial_hash"),
                    r.getString("financial_binding_audit"),r.getBytes("financial_binding_bytes"),r.getBytes("financial_binding_hash")));
            },customer,order,key);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        try {
        var request=payloads.decodeRequest(row.request());var candidate=payloads.decodeCandidate(row.candidate());var b=row.binding();
        if(request.customerId()!=row.customer()||request.orderId()!=row.order()||candidate.customerId()!=row.customer()
            ||candidate.orderId()!=row.order()||request.revision()!=candidate.revision()||b.revision()!=candidate.revision()
            ||!b.epoch().equals(candidate.epochId())||!Objects.equals(b.head(),candidate.headHistoryId())
            ||!Arrays.equals(b.fingerprint(),HexFormat.of().parseHex(candidate.baseFingerprint()))
            ||b.payment()!=candidate.paymentId()||b.authorization()!=candidate.authorizationId()||b.authorizedAmount()!=candidate.authorizedAmount()
            ||b.authorizationRevision()!=candidate.authorizationRevision()||b.before()!=candidate.beforeAmount()||b.after()!=candidate.preview().totalAmount()
            ||b.requestVersion()!=1||b.candidateVersion()!=1||!(candidate.preview().totalAmount()>candidate.authorizedAmount()?"REAUTHORIZE":"RETAIN_AUTH").equals(b.route())
            ||!OrderEvidenceDigests.sha256(row.request()).equals(HexFormat.of().formatHex(b.requestDigest()))
            ||!OrderEvidenceDigests.sha256(row.candidate()).equals(HexFormat.of().formatHex(b.candidateDigest()))
            ||!Arrays.equals(b.epochDigest(),HexFormat.of().parseHex(candidate.epochDigest()))||!Arrays.equals(b.headDigest(),HexFormat.of().parseHex(candidate.headDigest()))
            ||!Objects.equals(b.flow(),candidate.providerFlowId()))throw new IllegalArgumentException("Receipt payload/base binding mismatch");
        payloads.validateSaved(request,candidate,new OrderContentSnapshotCodec().decode(b.beforeSnapshot()));
        boolean decided=Set.of("ACCEPTED","REJECTED_LATE").contains(row.admission());var proof=row.proof();
        if(decided!=(proof!=null))throw new IllegalArgumentException("Receipt decision proof missing or unexpected");
        if(proof!=null&&(!proof.generation().equals(row.generation())||!proof.originalXid().equals(row.xid())||proof.originalXid().equals(proof.verificationXid())
            ||!proof.decision().equals(row.admission())||!proof.deadline().equals(b.deadline())||!Arrays.equals(proof.requestDigest(),b.requestDigest())
            ||!Arrays.equals(proof.candidateDigest(),b.candidateDigest())||!"committed".equals(proof.before())||!"committed".equals(proof.after())||proof.version()!=1
            ||proof.commit().isBefore(proof.deadline())!=proof.decision().equals("ACCEPTED")))throw new IllegalArgumentException("Receipt decision proof binding mismatch");
        validateFinancial(row);
        return row;
        } catch(IllegalArgumentException invalid){throw new UntrustedReceipt(row);}
    }
    /** Optional only for genuine pre-V72 receipts: never creates or repairs their missing basis. */
    private static void validateFinancial(Row row) {
        var f=row.financial();
        if(f.audit()==null){
            if(f.canonical()!=null||f.digest()!=null||f.binding()!=null||f.bindingCanonical()!=null||f.bindingDigest()!=null)throw new IllegalArgumentException("Partial financial evidence");
            return;
        }
        var codec=new OrderAdmissionFinancialSnapshotCodec();var snap=codec.decode(f.audit());var b=row.binding();
        if(!Arrays.equals(codec.canonical(snap),f.canonical())||!Arrays.equals(codec.digest(snap),f.digest())
            ||!snap.admissionId().equals(row.id())||snap.orderId()!=row.order()||snap.customerId()!=row.customer()
            ||!snap.dbGeneration().equals(row.generation())||!snap.originalXid().equals(row.xid())
            ||snap.revision()!=b.revision()||!snap.epoch().equals(b.epoch())||!Objects.equals(snap.head(),b.head())
            ||snap.payment()!=b.payment()||snap.authorization()!=b.authorization()||snap.authorizedAmount()!=b.authorizedAmount()
            ||snap.authorizationRevision()!=b.authorizationRevision()||snap.orderAmount()!=b.before()
            ||!snap.requestDigest().equals(HexFormat.of().formatHex(b.requestDigest()))
            ||!snap.candidateDigest().equals(HexFormat.of().formatHex(b.candidateDigest()))
            ||!snap.fingerprint().equals(HexFormat.of().formatHex(b.fingerprint()))
            ||!snap.epochDigest().equals(HexFormat.of().formatHex(b.epochDigest()))||!snap.headDigest().equals(HexFormat.of().formatHex(b.headDigest()))
            ||snap.deadline()!=micros(b.deadline()))throw new IllegalArgumentException("Financial receipt mismatch");
        if(row.proof()==null){if(f.binding()!=null||f.bindingCanonical()!=null||f.bindingDigest()!=null)throw new IllegalArgumentException("Premature financial binding");return;}
        if(f.binding()==null||!Arrays.equals(codec.binding(f.binding()),f.bindingCanonical())
            ||!Arrays.equals(codec.bindingDigest(f.binding()),f.bindingDigest()))throw new IllegalArgumentException("Invalid commit binding");
        // Check identities against the independently validated V71 proof, never today's Payment.
        var p=row.proof();var expected=tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        expected.put("domain",OrderAdmissionFinancialSnapshotCodec.BINDING_DOMAIN);expected.put("version",1);
        expected.put("admissionId",row.id().toString());expected.put("orderId",row.order());expected.put("dbGeneration",row.generation().toString());
        expected.put("originalXid",row.xid());expected.put("verificationXid",p.verificationXid());
        expected.put("requestDigest",snap.requestDigest());expected.put("candidateDigest",snap.candidateDigest());
        expected.put("snapshotVersion",1);expected.put("snapshotDigest",HexFormat.of().formatHex(f.digest()));
        expected.put("originalCommit",micros(p.commit()));expected.put("deadline",micros(p.deadline()));
        expected.put("statusBefore",p.before());expected.put("statusAfter",p.after());expected.put("decision",p.decision());expected.put("guardVersion",p.version());
        if(!Arrays.equals(codec.binding(expected.toString()),f.bindingCanonical()))throw new IllegalArgumentException("Commit binding identity mismatch");
    }
    private static long micros(java.time.Instant value){return Math.addExact(Math.multiplyExact(value.getEpochSecond(),1_000_000L),value.getNano()/1000);}
    private static void same(Row row,byte[] request,byte[] candidate) {
        if(!Arrays.equals(row.request(),request)||!Arrays.equals(row.candidate(),candidate))throw new Denied(Outcome.CONFLICT,"SUBMISSION_CONTENT_CONFLICT");
    }
    private static Result response(Outcome outcome,Row row,String reason) {
        return new Result(outcome,row==null?null:row.id(),row==null?null:row.admission(),row==null?null:row.execution(),reason);
    }
    private static boolean sqlState(Throwable error,String state) {
        for(Throwable e=error;e!=null;e=e.getCause())if(e instanceof SQLException s&&state.equals(s.getSQLState()))return true;
        return false;
    }
}
