package com.example.ecsite.payment.safety;

import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static com.example.ecsite.service.order.evidence.OrderEditAdmissionService.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionSystemException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Real receipt and verification commits on the separately guarded PG17 fixture. No gateway. */
class OrderEditAdmissionServicePostgresTest {
    static PaymentSafetyExpandPostgresTest db;static JdbcTemplate jdbc;
    static final OrderContentSnapshotCodec snapshots=new OrderContentSnapshotCodec();
    record Fixture(long order,long customer,long payment,long auth,long item,long product,UUID epoch) {}
    @BeforeAll static void database(){
        OrderEditAdmissionFoundationPostgresTest.database();db=OrderEditAdmissionFoundationPostgresTest.db;jdbc=db.jdbc;
        LegacyOrderEvidenceEnrollmentPostgresTest.db=db;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=jdbc;
        OrderPaymentEvidenceFoundationPostgresTest.db=db;OrderPaymentEvidenceFoundationPostgresTest.jdbc=jdbc;
    }
    @AfterAll static void cleanup(){OrderEditAdmissionFoundationPostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    @AfterEach void actor(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    static <T>T tx(Supplier<T> action){return new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->action.get());}
    static Fixture fixture(boolean past){
        var legacy=new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();long o=legacy.order();
        jdbc.update("update orders set ordered_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+?::interval+interval '10 hours',change_deadline_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+?::interval+interval '14 hours' where id=?",past?"-2 days":"2 days",past?"-2 days":"2 days",o);
        jdbc.update("update order_items set price=3000,quantity=2,subtotal=6000 where order_id=?",o);
        long owner=jdbc.queryForObject("select user_id from orders where id=?",Long.class,o);
        UUID epoch=UUID.randomUUID();
        tx(()->{
            var read=new JdbcOrderPaymentEvidenceReader(jdbc).readLocked(o);assertThat(read.state()).isEqualTo(JdbcOrderPaymentEvidenceReader.State.READ);
            var start=read.facts().current();var e=new OrderPaymentEvidence.Epoch(1,epoch,o,legacy.payment(),legacy.auth(),6000,0,OrderPaymentEvidence.Origin.INITIAL_AUTH,start,null,null,null);
            byte[] body=snapshots.encode(start);byte[] digest=HexFormat.of().parseHex(OrderEvidenceDigests.epoch(e));
            jdbc.update("""
                insert into order_payment_evidence_epochs(id,order_id,payment_id,authorization_transaction_id,origin_kind,
                  start_content_revision,start_total_amount,authorized_amount,authorization_revision,provider_flow_id,
                  start_snapshot,start_fingerprint,observation_payload,observation_fingerprint,observation_started_at,
                  observation_finished_at,evidence_version,evidence_digest)
                select ?,?,?,?,'INITIAL_AUTH',0,6000,6000,0,p.provider_payment_id,?,sha256(?),decode('7b7d','hex'),sha256(decode('7b7d','hex')),
                  clock_timestamp(),clock_timestamp(),1,? from payments p where id=?
                """,epoch,o,legacy.payment(),legacy.auth(),body,body,digest,legacy.payment());
            jdbc.update("update orders set current_evidence_epoch_id=? where id=?",epoch,o);return null;
        });
        long item=jdbc.queryForObject("select id from order_items where order_id=?",Long.class,o);
        long product=jdbc.queryForObject("select product_id from order_items where order_id=?",Long.class,o);
        return new Fixture(o,owner,legacy.payment(),legacy.auth(),item,product,epoch);
    }
    static Command command(Fixture f){return command(f,1,UUID.randomUUID());}
    static Command command(Fixture f,int quantity,UUID key){
        return tx(()->{
            var read=new JdbcOrderPaymentEvidenceReader(jdbc).readLocked(f.order());assertThat(read.decision().locallyVerified()).isTrue();
            var now=jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class);
            var context=OrderEditCandidateSources.context(read,f.customer(),now);
            var request=new Request(f.order(),read.facts().current().contentRevision(),List.of(new Line(f.item(),f.product(),quantity)));
            var offers=List.of(new Offer(f.product(),"current master name",read.facts().current().items().getFirst().categoryId(),"保存分類",9999,
                read.facts().current().items().getFirst().taxCategoryId(),"STANDARD","標準税率",1000,true));
            var candidate=new OrderEditCandidateCalculator().calculateForAdmission(context,request,offers).candidate();
            return new Command(f.customer(),key,request,candidate);
        });
    }
    static OrderEditAdmissionService service(){return new OrderEditAdmissionService(db.source);}
    static String business(Fixture f){
        return jdbc.queryForObject("""
            select jsonb_build_object('order',(select to_jsonb(o) from orders o where id=?),
              'payments',(select jsonb_agg(to_jsonb(p) order by id) from payments p where order_id=?),
              'transactions',(select jsonb_agg(to_jsonb(t) order by t.id) from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=?),
              'items',(select jsonb_agg(to_jsonb(i) order by id) from order_items i where order_id=?),
              'charges',(select jsonb_agg(to_jsonb(c) order by id) from order_charges c where order_id=?),
              'stock',(select stock from products where id=?))::text
            """,String.class,f.order(),f.order(),f.order(),f.order(),f.order(),f.product());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realCommitsDecideBeforeAndAfterDeadlineWithoutBusinessChanges(boolean past){
        var f=fixture(past);var command=command(f);String before=business(f);var r=service().submit(command);
        assertThat(r.outcome()).isEqualTo(past?Outcome.REJECTED_LATE:Outcome.ACCEPTED);
        assertThat(business(f)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select e.original_commit_at=pg_xact_commit_timestamp(a.original_xid::xid) and e.original_xid<>e.verification_xid from order_edit_admissions a join order_edit_admission_evidences e on e.admission_id=a.id where a.id=?",Boolean.class,r.admissionId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admission_events where admission_id=?",Integer.class,r.admissionId())).isEqualTo(2);
    }
    @Test void sameKeyReplaysAcrossServiceRestartAndStoppedRuntimeButDifferentPayloadConflicts(){
        var f=fixture(false);var c=command(f);var first=service().submit(c);assertThat(first.outcome()).isEqualTo(Outcome.ACCEPTED);
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        assertThat(service().submit(c)).isEqualTo(first);
        var different=new Command(c.customerId(),c.submissionKey(),new Request(f.order(),0,List.of(new Line(f.item(),f.product(),2))),c.confirmed());
        assertThat(service().submit(different).outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isEqualTo(1);
    }
    @Test void stoppedRuntimeRejectsNewReceipt(){
        var f=fixture(false);var c=command(f);jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        assertThat(service().submit(c).outcome()).isEqualTo(Outcome.RUNTIME_UNAVAILABLE);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
    }
    @ParameterizedTest @ValueSource(ints={2,3})
    void noOpAndOverAuthorizationCannotCreateReceipt(int quantity){
        var f=fixture(false);assertThat(service().submit(command(f,quantity,UUID.randomUUID())).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
    }
    static DataSourceTransactionManager lostCommit(int which,boolean commit){
        return new DataSourceTransactionManager(db.source){final AtomicInteger calls=new AtomicInteger();
            @Override protected void doCommit(DefaultTransactionStatus status){
                if(calls.incrementAndGet()==which){if(commit)super.doCommit(status);else super.doRollback(status);throw new TransactionSystemException("Synthetic lost commit response");}
                super.doCommit(status);
            }
        };
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void lostCommitResponseRequiresDurableRecoveryWithoutDuplicateReceipt(int which){
        var f=fixture(false);var c=command(f);String before=business(f);
        var r=new OrderEditAdmissionService(db.source,lostCommit(which,true)).submit(c);
        assertThat(r.outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);assertThat(r.admissionId()).isNotNull();
        assertThat(r.databaseAdmissionState()).isEqualTo(which==1?"RECORDED_UNVERIFIED":"ACCEPTED");
        var recovered=service().recover(f.customer(),f.order(),c.submissionKey());assertThat(recovered.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(service().submit(c).admissionId()).isEqualTo(r.admissionId());assertThat(business(f)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isEqualTo(1);
    }
    @Test void rollbackDoesNotImplyNewReceiptDuringRecovery(){
        var f=fixture(false);var c=command(f);
        assertThat(new OrderEditAdmissionService(db.source,lostCommit(1,false)).submit(c).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        assertThat(service().recover(f.customer(),f.order(),c.submissionKey()).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
        assertThat(service().submit(c).outcome()).isEqualTo(Outcome.ACCEPTED);
    }
    @Test void generationMismatchDoesNotChangeOrReleaseReceipt(){
        var f=fixture(false);var c=command(f);var r=new OrderEditAdmissionService(db.source,lostCommit(1,true)).submit(c);
        String before=jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,r.admissionId());
        UUID generation=UUID.randomUUID();jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",generation);
        jdbc.update("update order_edit_runtime_control set generation_id=?,version=version+1 where id=1",generation);
        var recovery=service().recover(f.customer(),f.order(),c.submissionKey());
        assertThat(recovery.outcome()).isEqualTo(Outcome.RUNTIME_UNAVAILABLE);assertThat(recovery.reason()).isEqualTo("GENERATION_MISMATCH");
        assertThat(recovery.databaseAdmissionState()).isEqualTo("RECORDED_UNVERIFIED");
        assertThat(jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,r.admissionId())).isEqualTo(before);
        assertThatThrownBy(()->tx(()->{new OrderEditAdmissionGuard(jdbc).requireClear(f.order());return null;})).isInstanceOf(com.example.ecsite.exception.InvalidOrderStatusException.class);
    }
    @Test void failedVerificationIsHeldAndExplicitlyResumed(){
        var f=fixture(false);var c=command(f);
        jdbc.execute("create function test_fail_verify() returns trigger language plpgsql as $$ begin raise exception using errcode='23514',message='test missing commit evidence'; end $$");
        jdbc.execute("create trigger aa_test_verify_failure before insert on order_edit_admission_evidences for each row execute function test_fail_verify()");
        try {
            var r=service().submit(c);assertThat(r.outcome()).isEqualTo(Outcome.UNPROVEN);assertThat(r.databaseAdmissionState()).isEqualTo("UNPROVEN");
            assertThat(service().submit(c).outcome()).isEqualTo(Outcome.UNPROVEN);
            var other=new Command(c.customerId(),UUID.randomUUID(),c.request(),c.confirmed());assertThat(service().submit(other).outcome()).isEqualTo(Outcome.CONFLICT);
        } finally {jdbc.execute("drop trigger aa_test_verify_failure on order_edit_admission_evidences");jdbc.execute("drop function test_fail_verify()");}
        assertThat(service().recover(f.customer(),f.order(),c.submissionKey()).outcome()).isEqualTo(Outcome.ACCEPTED);
    }
    @ParameterizedTest @ValueSource(strings={"payment","preparing","cancelled","pending"})
    void freshLocalStateIsRevalidated(String changed){
        var f=fixture(false);var c=command(f);
        switch(changed){
            case "payment"->jdbc.update("update payments set status='CANCELLED' where id=?",f.payment());
            case "preparing"->jdbc.update("update orders set shipping_preparation_started_at=localtimestamp,shipping_preparation_started_by_account_id=1,shipping_preparation_started_by_username='admin' where id=?",f.order());
            case "cancelled"->jdbc.update("update orders set status='CANCELLED' where id=?",f.order());
            default->db.transaction(f.payment(),"CANCEL","PENDING",null);
        }
        assertThat(service().submit(c).outcome()).isEqualTo(Outcome.BLOCKED);
    }
    @Test void currentNewOfferIsFixedAndMustBeConfirmedAgainAfterPriceChange(){
        var f=fixture(false);jdbc.update("update products set price=1000 where id=?",f.product());
        var c=tx(()->{
            var read=new JdbcOrderPaymentEvidenceReader(jdbc).readLocked(f.order());
            var context=OrderEditCandidateSources.context(read,f.customer(),jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class));
            var req=new Request(f.order(),0,List.of(new Line(null,f.product(),1)));
            var offers=jdbc.query("select p.id,p.name,c.id,c.name,p.price,t.id,t.code,t.name,t.tax_rate,p.active and c.active and t.active from products p join categories c on c.id=p.category_id join tax_categories t on t.id=p.tax_category_id where p.id=?",
                (r,n)->new Offer(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getInt(5),r.getLong(6),r.getString(7),r.getString(8),OrderContentSnapshotCodec.taxRateBasisPoints(r.getBigDecimal(9)),r.getBoolean(10)),f.product());
            return new Command(f.customer(),UUID.randomUUID(),req,new OrderEditCandidateCalculator().calculateForAdmission(context,req,offers).candidate());
        });
        assertThat(c.confirmed().newLineReferences()).hasSize(1);
        assertThat(c.confirmed().newLineReferences().getFirst().previewItemId()).isNotEqualTo(f.item());
        jdbc.update("update products set price=1100 where id=?",f.product());
        assertThat(service().submit(c).outcome()).isEqualTo(Outcome.CONFLICT);
        jdbc.update("update products set price=1000 where id=?",f.product());
        assertThat(service().submit(c).outcome()).isEqualTo(Outcome.ACCEPTED);
    }
    @ParameterizedTest @ValueSource(strings={"shipping","cancel","items","address","reauthorize","capture"})
    void admissionCommitSerializesAllExistingOperationGates(String operation) throws Exception {
        var f=fixture(false);var c=command(f);var recorded=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var manager=new DataSourceTransactionManager(db.source){final AtomicBoolean first=new AtomicBoolean(true);
            @Override protected void doCommit(DefaultTransactionStatus status){
                if(first.compareAndSet(true,false)){recorded.countDown();waitFor(finish);}super.doCommit(status);
            }
        };
        var pool=Executors.newFixedThreadPool(2);var pid=new AtomicInteger();
        try {
            var receipt=pool.submit(()->new OrderEditAdmissionService(db.source,manager).submit(c));
            assertThat(recorded.await(10,TimeUnit.SECONDS)).isTrue();
            var actor=pool.submit(()->tx(()->{
                pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                OrderEditAdmissionGuardPostgresTest.invoke(operation,new OrderEditAdmissionFoundationPostgresTest.Fixture(f.order(),f.customer(),f.payment(),f.auth(),f.epoch()));return null;
            }));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->pid.get()!=0&&Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get())));
            finish.countDown();assertThat(receipt.get(15,TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThatThrownBy(()->actor.get(10,TimeUnit.SECONDS)).hasRootCauseInstanceOf(com.example.ecsite.exception.InvalidOrderStatusException.class);
        } finally {finish.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void runtimeLockWaitDoesNotHoldOrderOrBlockReferenceOnlyGuard() throws Exception {
        var f=fixture(false);var c=command(f);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var admin=pool.submit(()->tx(()->{jdbc.queryForObject("select id from order_edit_runtime_control where id=1 for update",Integer.class);locked.countDown();waitFor(release);return null;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var request=pool.submit(()->service().submit(c));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and query like '%f125_admission_require_runtime%' and wait_event_type='Lock')",Boolean.class)));
            tx(()->{jdbc.execute("set local lock_timeout='1s'");new OrderEditAdmissionGuard(jdbc).requireClear(f.order());return null;});
            release.countDown();admin.get(10,TimeUnit.SECONDS);assertThat(request.get(15,TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.ACCEPTED);
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentSameAndDifferentKeysNeverCreateTwoReceipts(boolean sameKey) throws Exception {
        var f=fixture(false);var c=command(f);var other=new Command(c.customerId(),sameKey?c.submissionKey():UUID.randomUUID(),c.request(),c.confirmed());
        var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await(10,TimeUnit.SECONDS);return service().submit(c);});
            var b=pool.submit(()->{start.await(10,TimeUnit.SECONDS);return service().submit(other);});
            var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(results).extracting(OrderEditAdmissionService.Result::outcome).contains(Outcome.ACCEPTED);
            if(sameKey)assertThat(results).extracting(OrderEditAdmissionService.Result::admissionId).containsOnly(results.getFirst().admissionId());
            else assertThat(results).extracting(OrderEditAdmissionService.Result::outcome).containsExactlyInAnyOrder(Outcome.ACCEPTED,Outcome.CONFLICT);
            assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isEqualTo(1);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void earlierCancellationIsRecheckedAfterOrderLockWait() throws Exception {
        var f=fixture(false);var c=command(f);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var actor=pool.submit(()->tx(()->{jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());locked.countDown();waitFor(release);jdbc.update("update orders set status='CANCELLED' where id=?",f.order());return null;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var request=pool.submit(()->service().submit(c));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and query like 'select id from orders where id=%' and wait_event_type='Lock')",Boolean.class)));
            release.countDown();actor.get(10,TimeUnit.SECONDS);assertThat(request.get(15,TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.BLOCKED);
            assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
    static void waitFor(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("test latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void v71ValidButUnboundCanonicalReceiptCannotBeTrusted(boolean decided){
        var f=fixture(false);var c=command(f);var codec=new OrderEditAdmissionPayloadCodec();
        byte[] request=new String(codec.request(f.customer(),c.request()),java.nio.charset.StandardCharsets.UTF_8).replace("\"revision\":0","\"revision\":1").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] candidate=new String(codec.candidate(c.confirmed()),java.nio.charset.StandardCharsets.UTF_8).replace("\"revision\":0","\"revision\":1").replace("\"contentRevision\":0","\"contentRevision\":1").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        codec.decodeRequest(request);codec.decodeCandidate(candidate); // Canonical bytes alone are not authorization.
        UUID id=UUID.randomUUID();
        tx(()->{jdbc.update("""
            insert into order_edit_admissions(id,order_id,customer_id,actor_username,submission_key,
              request_version,request_payload,request_digest,candidate_version,candidate_payload,candidate_digest,
              base_content_revision,base_evidence_epoch_id,base_evidence_history_id,base_fingerprint,
              base_payment_id,base_authorization_id,authorized_amount,authorization_revision,before_amount,after_amount,deadline_at,db_generation_id)
            select ?,o.id,o.user_id,'DB actor',?,1,?,sha256(?),1,?,sha256(?),o.content_revision,e.id,null,e.start_fingerprint,
              e.payment_id,e.authorization_transaction_id,e.authorized_amount,e.authorization_revision,6000,3550,
              o.change_deadline_at at time zone 'Asia/Tokyo',g.generation_id
            from orders o join order_payment_evidence_epochs e on e.id=o.current_evidence_epoch_id cross join order_edit_runtime_control g where o.id=? and g.id=1
            """,id,c.submissionKey(),request,request,candidate,candidate,f.order());return null;});
        if(decided)tx(()->jdbc.queryForObject("select f125_admission_verify(?)",String.class,id));
        String before=jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,id);
        var result=service().recover(f.customer(),f.order(),c.submissionKey());
        assertThat(result.outcome()).isEqualTo(Outcome.UNPROVEN);assertThat(result.reason()).isEqualTo("SAVED_RECEIPT_INVALID");
        assertThat(jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,id)).isEqualTo(before);
    }
    @Test void unresolvedFinancialOperationIsNotBypassed(){
        var f=fixture(false);var c=command(f);
        tx(()->OrderPaymentEvidenceFoundationPostgresTest.operation(new OrderPaymentEvidenceFoundationPostgresTest.Fixture(f.order(),f.customer(),f.payment(),f.auth(),f.epoch(),null,null),0,6000,5000,"RETAIN_AUTH",null));
        assertThat(service().submit(c).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
    }
    @Test void changedRevisionAndHeadRequireNewConfirmationWithValidEvidence(){
        var f=fixture(false);var oldCommand=command(f);
        long history=tx(()->{
            var facts=new JdbcOrderPaymentEvidenceReader(jdbc).readLocked(f.order()).facts();var old=facts.current();var i=old.items().getFirst();
            var changed=new OrderContentSnapshot.Item(i.orderItemId(),i.productId(),"変更後の保存商品名",i.categoryId(),i.categoryName(),i.unitPrice(),i.quantity(),i.subtotal(),i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),i.taxRateBasisPoints());
            var after=new OrderContentSnapshot(1,old.orderId(),old.customerId(),1,List.of(changed),old.charges(),old.itemSubtotal(),old.chargeTotal(),old.taxAmount(),old.totalAmount());
            var op=OrderPaymentEvidenceFoundationPostgresTest.operation(new OrderPaymentEvidenceFoundationPostgresTest.Fixture(f.order(),f.customer(),f.payment(),f.auth(),f.epoch(),null,null),0,6000,6000,"RETAIN_AUTH",null);
            long id=jdbc.queryForObject("select nextval(pg_get_serial_sequence('order_content_change_histories','id'))",Long.class);
            var proof=new OrderPaymentEvidence.Change(1,id,f.order(),op,f.epoch(),null,0,6000,snapshots.fingerprint(old),after,facts.epoch().digest(),null);
            jdbc.update("""
                insert into order_content_change_histories(id,order_id,change_source,changed_by_type,changed_by_account_id,changed_by_username,
                  old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount,
                  before_content_revision,after_content_revision,evidence_epoch_id,previous_history_id,before_fingerprint,after_fingerprint,after_snapshot,evidence_version,evidence_digest)
                values (?,?,'CUSTOMER','USER',?,'fixture',6000,6000,0,0,545,545,6000,6000,0,1,?,null,?,?,?,1,?)
                """,id,f.order(),f.customer(),f.epoch(),HexFormat.of().parseHex(snapshots.fingerprint(old)),HexFormat.of().parseHex(snapshots.fingerprint(after)),snapshots.encode(after),HexFormat.of().parseHex(OrderEvidenceDigests.change(proof)));
            jdbc.update("insert into order_content_change_items(history_id,product_id,product_name,change_type) values (?,?,'変更後の保存商品名','UPDATED')",id,f.product());
            jdbc.update("insert into order_content_change_charges(history_id,charge_type,change_type) values (?,'SHIPPING','UPDATED')",id);
            jdbc.update("update order_items set product_name='変更後の保存商品名' where id=?",f.item());
            jdbc.update("update orders set content_revision=1,evidence_head_history_id=? where id=?",id,f.order());
            jdbc.update("update order_change_operations set state='COMPLETED',content_change_history_id=?,version=version+1 where id=?",id,op);
            return id;
        });
        assertThat(tx(()->new JdbcOrderPaymentEvidenceReader(jdbc).readLocked(f.order()).decision().locallyVerified())).isTrue();
        assertThat(service().submit(oldCommand).outcome()).isEqualTo(Outcome.BLOCKED);
        var accepted=service().submit(command(f));assertThat(accepted.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(jdbc.queryForObject("select base_evidence_history_id from order_edit_admissions where id=?",Long.class,accepted.admissionId())).isEqualTo(history);
    }
}
