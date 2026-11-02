package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.ecsite.service.order.evidence.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.evidence.OrderAdmissionFinancialSnapshotCodecTest.*;

/** Guarded PG17, real Tx-A/Tx-B commits, no gateway or development database. */
class OrderAdmissionFinancialSnapshotPostgresTest {
    static PaymentSafetyExpandPostgresTest db; static JdbcTemplate jdbc; static UUID legacyReceipt;
    final OrderAdmissionFinancialSnapshotCodec codec=new OrderAdmissionFinancialSnapshotCodec();
    @BeforeAll static void database(){OrderEditAdmissionServicePostgresTest.database();db=OrderEditAdmissionServicePostgresTest.db;jdbc=db.jdbc;
        // Create a genuine old-format receipt before V72; migration must not invent its past basis.
        UUID generation=UUID.randomUUID();
        jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",generation);
        jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',generation_id=?,version=version+1 where id=1",generation);
        OrderEditAdmissionFoundationPostgresTest.generation=generation;
        legacyReceipt=OrderEditAdmissionFoundationPostgresTest.record(OrderEditAdmissionFoundationPostgresTest.fixture(false));
        OrderEditAdmissionFoundationPostgresTest.verify(legacyReceipt);
        db.migrate("72");}
    @AfterAll static void cleanup(){OrderEditAdmissionServicePostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    UUID receipt(){var f=OrderEditAdmissionServicePostgresTest.fixture(false);var r=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));assertThat(r.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);return r.admissionId();}
    static String hash(byte[] value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    String audit(UUID id){return jdbc.queryForObject("select financial_snapshot::text from order_edit_admissions where id=?",String.class,id);}
    @Test void javaAndDatabaseShareIndependentFixedVector(){
        assertThat(jdbc.queryForObject("select encode(f125_financial_encode(?::jsonb),'hex')",String.class,VECTOR)).isEqualTo(HEX);
        assertThat(jdbc.queryForObject("select encode(sha256(f125_financial_encode(?::jsonb)),'hex')",String.class,VECTOR)).isEqualTo(DIGEST);
    }
    @Test void recordsSnapshotAndBindsActualOriginalCommit(){
        UUID id=receipt();var s=codec.decode(audit(id));
        assertThat(s.admissionId()).isEqualTo(id);assertThat(s.payments()).hasSize(1);assertThat(s.transactions()).hasSize(1);
        assertThat(s.orderAmount()).isEqualTo(6000);assertThat(s.authorizedAmount()).isEqualTo(6000);
        assertThat(jdbc.queryForObject("select financial_canonical from order_edit_admissions where id=?",byte[].class,id)).isEqualTo(codec.canonical(s));
        assertThat(jdbc.queryForObject("select financial_digest from order_edit_admissions where id=?",byte[].class,id)).isEqualTo(codec.digest(s));
        String binding=jdbc.queryForObject("select financial_binding::text from order_edit_admission_evidences where admission_id=?",String.class,id);
        assertThat(jdbc.queryForObject("select financial_binding_canonical from order_edit_admission_evidences where admission_id=?",byte[].class,id)).isEqualTo(codec.binding(binding));
        assertThat(jdbc.queryForObject("select financial_binding_digest from order_edit_admission_evidences where admission_id=?",byte[].class,id)).isEqualTo(codec.bindingDigest(binding));
        assertThat(jdbc.queryForObject("select original_commit_at=pg_xact_commit_timestamp(original_xid::xid) from order_edit_admission_evidences where admission_id=?",Boolean.class,id)).isTrue();
    }
    @Test void immutableEvenWhenVersionAndCanonicalAreReplacedTogether(){
        UUID id=receipt();String before=audit(id);
        for(String assignment:List.of("financial_snapshot_version=2","financial_snapshot='{}'::jsonb","financial_canonical=decode('00','hex')","financial_digest=decode(repeat('00',32),'hex')","financial_snapshot_version=null,financial_snapshot=null,financial_canonical=null,financial_digest=null"))
            db.rejects("55000",()->jdbc.update("update order_edit_admissions set "+assignment+",version=version+1 where id=?",id));
        db.rejects("55000",()->jdbc.update("delete from order_edit_admissions where id=?",id));
        db.rejects("55000",()->jdbc.update("update order_edit_admission_evidences set financial_binding_digest=decode(repeat('00',32),'hex') where admission_id=?",id));
        assertThat(audit(id)).isEqualTo(before);
    }
    @Test void replayReturnsOriginalSnapshotAndNewContentConflicts(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);var a=OrderEditAdmissionServicePostgresTest.service().submit(c);
        String before=audit(a.admissionId());assertThat(OrderEditAdmissionServicePostgresTest.service().submit(c)).isEqualTo(a);
        var other=OrderEditAdmissionServicePostgresTest.command(f,2,c.submissionKey());
        assertThat(OrderEditAdmissionServicePostgresTest.service().submit(other).outcome()).isEqualTo(OrderEditAdmissionService.Outcome.CONFLICT);
        assertThat(audit(a.admissionId())).isEqualTo(before);
    }
    @Test void changedFinancialStateIsNotUsedAsWorkerAdmissionBasis(){
        UUID id=receipt();String before=audit(id);
        long payment=codec.decode(before).payment();
        jdbc.update("update payments set status='CANCELLED' where id=?",payment);
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{jdbc.queryForObject("select f125_execution_initialize(?)",Object.class,id);return null;}));
        assertThat(audit(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_controls where admission_id=?",Integer.class,id)).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
    void lostCommitResponseRecoversSameSnapshotAndBinding(int lostTx){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        var result=new OrderEditAdmissionService(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(lostTx,true)).submit(c);
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.COMMIT_UNKNOWN);
        String before=audit(result.admissionId());var recovered=OrderEditAdmissionServicePostgresTest.service().recover(f.customer(),f.order(),c.submissionKey());
        assertThat(recovered.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);assertThat(audit(result.admissionId())).isEqualTo(before);
    }
    @Test void rolledBackTxAHasNoReceiptOrSnapshot(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        new OrderEditAdmissionService(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(1,false)).submit(c);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
    }
    static UUID recordStructural(OrderEditAdmissionFoundationPostgresTest.Fixture f){
        return OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_admission_require_runtime()",UUID.class);
            jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());
            jdbc.queryForList("select id from order_change_operations where order_id=? order by id for update",UUID.class,f.order());
            jdbc.queryForList("select id from payments where order_id=? order by id for update",Long.class,f.order());
            jdbc.queryForList("select id from payment_transactions where payment_id in(select id from payments where order_id=?) order by payment_id,id for update",Long.class,f.order());
            UUID id=UUID.randomUUID();
            jdbc.update("""
                insert into order_edit_admissions(id,order_id,customer_id,actor_username,submission_key,
                  request_version,request_payload,request_digest,candidate_version,candidate_payload,candidate_digest,
                  base_content_revision,base_evidence_epoch_id,base_evidence_history_id,base_fingerprint,base_payment_id,
                  base_authorization_id,authorized_amount,authorization_revision,before_amount,after_amount,deadline_at,db_generation_id)
                select ?,o.id,o.user_id,'DB actor',?,1,decode('010203','hex'),sha256(decode('010203','hex')),
                  1,decode('040506','hex'),sha256(decode('040506','hex')),o.content_revision,e.id,o.evidence_head_history_id,
                  coalesce(h.after_fingerprint,e.start_fingerprint),e.payment_id,e.authorization_transaction_id,e.authorized_amount,e.authorization_revision,
                  o.total_amount,4500,o.change_deadline_at at time zone 'Asia/Tokyo',g.generation_id
                from orders o join order_payment_evidence_epochs e on e.id=o.current_evidence_epoch_id
                left join order_content_change_histories h on h.id=o.evidence_head_history_id and h.evidence_epoch_id=e.id
                cross join order_edit_runtime_control g where o.id=? and g.id=1
                """,id,UUID.randomUUID(),f.order());return id;
        });
    }
    @Test void legacyReceiptRemainsUnenrolledAndCannotBecomeWorkerBasis(){
        assertThat(jdbc.queryForObject("select financial_snapshot from order_edit_admissions where id=?",String.class,legacyReceipt)).isNull();
        assertThat(jdbc.queryForObject("select financial_binding from order_edit_admission_evidences where admission_id=?",String.class,legacyReceipt)).isNull();
        db.rejects("23514",()->jdbc.queryForObject("select f125_financial_assert_current(?)",Object.class,legacyReceipt));
        db.rejects("55000",()->jdbc.update("update order_edit_admissions set financial_snapshot_version=1,version=version+1 where id=?",legacyReceipt));
    }
    @Test void structuralReceiptKeepsAuthorizationAmountAndRevisionDistinct(){
        var f=OrderEditAdmissionFoundationPostgresTest.fixture(false);
        var e=new OrderPaymentEvidenceFoundationPostgresTest.Fixture(f.order(),f.customer(),f.payment(),f.authorization(),f.epoch(),null,null);
        OrderPaymentEvidenceFoundationPostgresTest.change(e,null,0,6000,5000);
        UUID id=recordStructural(f);var snapshot=codec.decode(audit(id));
        assertThat(snapshot.orderAmount()).isEqualTo(5000);assertThat(snapshot.authorizedAmount()).isEqualTo(6000);
        assertThat(snapshot.revision()).isEqualTo(1);assertThat(snapshot.authorizationRevision()).isZero();
        assertThat(snapshot.operations()).hasSize(1);assertThat(snapshot.head()).isNotNull();
    }
    @Test void structuralReceiptIncludesCompletedReauthorizationHistory(){
        var f=OrderEditAdmissionFoundationPostgresTest.fixture(false);
        var next=OrderPaymentEvidenceFoundationPostgresTest.reauthorize(new OrderPaymentEvidenceFoundationPostgresTest.Fixture(f.order(),f.customer(),f.payment(),f.authorization(),f.epoch(),null,null),null);
        UUID id=recordStructural(new OrderEditAdmissionFoundationPostgresTest.Fixture(f.order(),f.customer(),next.payment(),next.auth(),next.epoch()));
        var snapshot=codec.decode(audit(id));assertThat(snapshot.payments()).hasSize(2);assertThat(snapshot.transactions()).hasSize(3);
        assertThat(snapshot.operations()).singleElement().satisfies(o->assertThat(o.state()).isEqualTo("COMPLETED"));
        assertThat(snapshot.payment()).isEqualTo(next.payment());assertThat(snapshot.authorization()).isEqualTo(next.auth());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CAPTURE","CANCEL"})
    void pendingFinancialTransactionsCannotBeAdmitted(String type){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var command=OrderEditAdmissionServicePostgresTest.command(f);
        db.transaction(f.payment(),type,"PENDING",type.equals("CAPTURE")?"SHIPMENT_CAPTURE":"ORDER_CANCELLATION");
        assertThat(OrderEditAdmissionServicePostgresTest.service().submit(command).outcome()).isEqualTo(OrderEditAdmissionService.Outcome.BLOCKED);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CAPTURED","CANCELLED"})
    void changedPaymentStatusCannotBeAdmitted(String status){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var command=OrderEditAdmissionServicePostgresTest.command(f);
        jdbc.update("update payments set status=? where id=?",status,f.payment());
        assertThat(OrderEditAdmissionServicePostgresTest.service().submit(command).outcome()).isEqualTo(OrderEditAdmissionService.Outcome.BLOCKED);
    }
    @Test void requestAndDispatchFactsAreSavedWithoutSendingAnything(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID op=UUID.randomUUID();long pending=db.payment(f.order());
        jdbc.update("update payments set provider_payment_id=null,status='PENDING' where id=?",pending);
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
             old_payment_id,new_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
             shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
             accepted_at,deadline_at,grace_until,state,created_at,updated_at)
            select ?,id,user_id,?,repeat('a',64),0,?,?,6000,5500,5500,0,0,'送料',0,1,'STANDARD','標準',10,10,
             ordered_at,change_deadline_at,change_deadline_at+interval '10 minutes','RESERVED',localtimestamp,localtimestamp from orders where id=?
            """,op,UUID.randomUUID(),f.payment(),pending,f.order());
        long transaction=db.transaction(pending,"AUTHORIZE","PENDING",null);
        String key=jdbc.queryForObject("select idempotency_key from payment_transactions where id=?",String.class,transaction);
        jdbc.update("update payment_transactions set purpose='ORDER_CHANGE_AUTHORIZATION',order_change_operation_id=?,order_change_order_id=? where id=?",op,f.order(),transaction);
        var identity=new com.example.ecsite.payment.capture.CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp");
        var request=com.example.ecsite.payment.orderchange.OrderChangeHttpRequest.create(identity,op,f.order(),5500,key);
        var saved=OrderEditAdmissionServicePostgresTest.tx(()->new com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore(jdbc).save(pending,transaction,request));
        // A historically abandoned, never-dispatched CREATE is an explicit supported local fact.
        jdbc.update("update payments set status='FAILED' where id=?",pending);
        jdbc.update("update payment_transactions set status='FAILED' where id=?",transaction);
        jdbc.update("update order_change_operations set state='ABORTED',version=version+1 where id=?",op);
        var result=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);
        var snapshot=codec.decode(audit(result.admissionId()));assertThat(snapshot.requests()).singleElement().satisfies(r->{
            assertThat(r.id()).isEqualTo(saved.id());assertThat(r.bodyDigest()).isEqualTo(hash(request.body()));
        });
        assertThat(snapshot.dispatches()).singleElement().satisfies(d->{assertThat(d.state()).isEqualTo("READY");assertThat(d.started()).isNull();});
        assertThat(jdbc.queryForObject("select financial_canonical from order_edit_admissions where id=?",byte[].class,result.admissionId())).isEqualTo(codec.canonical(snapshot));
    }
    @Test void generationMismatchCannotRewriteSnapshotOrProof(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        var result=new OrderEditAdmissionService(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(1,true)).submit(c);String before=audit(result.admissionId());
        UUID generation=UUID.randomUUID();jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",generation);
        jdbc.update("update order_edit_runtime_control set generation_id=?,version=version+1 where id=1",generation);
        assertThat(OrderEditAdmissionServicePostgresTest.service().recover(f.customer(),f.order(),c.submissionKey()).outcome()).isEqualTo(OrderEditAdmissionService.Outcome.RUNTIME_UNAVAILABLE);
        assertThat(audit(result.admissionId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admission_evidences where admission_id=?",Integer.class,result.admissionId())).isZero();
    }

    @Test void failedTxBLeavesOriginalSnapshotAndCanRecoverBinding(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        var result=new OrderEditAdmissionService(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(2,false)).submit(c);
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.COMMIT_UNKNOWN);String before=audit(result.admissionId());
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admission_evidences where admission_id=?",Integer.class,result.admissionId())).isZero();
        assertThat(OrderEditAdmissionServicePostgresTest.service().recover(f.customer(),f.order(),c.submissionKey()).outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);
        assertThat(audit(result.admissionId())).isEqualTo(before);
    }
    @Test void laterCaptureRequestCannotReplaceAdmissionTimeFacts(){
        UUID id=receipt();String before=audit(id);var original=codec.decode(before);
        long transaction=db.transaction(original.payment(),"CAPTURE","FAILED","SHIPMENT_CAPTURE");
        jdbc.update("update payment_transactions set amount=6000 where id=?",transaction);
        String key=jdbc.queryForObject("select idempotency_key from payment_transactions where id=?",String.class,transaction);
        String flow=original.payments().getFirst().providerId();
        var request=com.example.ecsite.payment.capture.CaptureHttpRequest.create(new com.example.ecsite.payment.capture.CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp"),flow,6000,key);
        long capture=jdbc.queryForObject("""
            insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,
             connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,amount_to_capture,order_content_revision,
             idempotency_key,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after)
            values (?,?,'CAPTURE','PAYJP','CARD',?,'primary','merchant','TEST','https://api.pay.jp',?,?,?,6000,0,?,1,?,1,?,clock_timestamp(),clock_timestamp()+interval '1 hour') returning id
            """,Long.class,original.payment(),transaction,flow,request.method(),request.path(),request.contentType(),key,request.body(),request.fingerprint());
        var current=codec.decode(jdbc.queryForObject("select f125_financial_build(a)::text from order_edit_admissions a where id=?",String.class,id));
        assertThat(current.captures()).singleElement().satisfies(c->{assertThat(c.id()).isEqualTo(capture);assertThat(c.bodyDigest()).isEqualTo(hash(request.body()));});
        assertThat(audit(id)).isEqualTo(before);assertThat(original.captures()).isEmpty();
        db.rejects("23514",()->jdbc.queryForObject("select f125_financial_assert_current(?)",Object.class,id));
    }

}
