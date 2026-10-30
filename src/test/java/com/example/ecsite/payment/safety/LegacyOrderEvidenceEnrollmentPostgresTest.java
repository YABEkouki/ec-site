package com.example.ecsite.payment.safety;

import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.payjp.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Guarded dedicated container only. Real gateway HTTP is replaced by MockRestServiceServer. */
class LegacyOrderEvidenceEnrollmentPostgresTest {
    static PaymentSafetyExpandPostgresTest db;static JdbcTemplate jdbc;
    static final CaptureConnectionIdentity C=new CaptureConnectionIdentity("test","merchant","TEST","https://api.pay.jp");
    record Fixture(long order,long payment,long auth) {}
    @BeforeAll static void database() {
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();db=new PaymentSafetyExpandPostgresTest();db.isolatedSchema();db.migrate("69");jdbc=db.jdbc;
        var constraints=jdbc.queryForList("select conname from pg_constraint where conrelid='order_payment_evidence_epochs'::regclass and contype='c' order by conname",String.class);
        db.migrate("70");
        var after=jdbc.queryForList("select conname from pg_constraint where conrelid='order_payment_evidence_epochs'::regclass and contype='c' order by conname",String.class);
        assertThat(after.size()).isEqualTo(constraints.size());assertThat(after).contains("ck_f125_epoch_origin_boundaries");
    }
    Fixture fixture() {
        long o=db.order(),p=db.payment(o);jdbc.update("update payments set amount=6000 where id=?",p);
        jdbc.update("update orders set total_amount=6000,item_subtotal=6000,tax_amount=545,active_payment_id=?,change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())+interval '1 hour' where id=?",p,o);
        long a=db.transaction(p,"AUTHORIZE","SUCCEEDED","INITIAL_ORDER_AUTHORIZATION");
        jdbc.update("update payment_transactions set amount=6000,completed_at=localtimestamp,provider_transaction_id=(select provider_payment_id from payments where id=?) where id=?",p,a);
        long tax=jdbc.queryForObject("select id from tax_categories where code='STANDARD'",Long.class);
        long category=jdbc.queryForObject("select id from categories order by id limit 1",Long.class);
        long product=jdbc.queryForObject("insert into products(name,price,stock,category_id,tax_category_id) values ('current master name',9999,100,?,?) returning id",Long.class,category,tax);
        jdbc.update("insert into order_items(order_id,product_id,product_name,category_id,category_name,price,quantity,subtotal,tax_category_id,tax_category_code,tax_category_name,tax_rate) values (?,?,'保存商品名',?,'保存分類',6000,1,6000,?,'STANDARD','標準税率',10)",o,product,category,tax);
        jdbc.update("insert into order_charges(order_id,charge_type,name,amount,tax_category_id,tax_category_code,tax_category_name,tax_rate,display_order) values (?,'SHIPPING','保存送料',0,?,'STANDARD','標準税率',10,10)",o,tax);
        return new Fixture(o,p,a);
    }
    PaymentGateway gateway(java.util.function.Function<OrderAuthorizationObservation.Expected,OrderAuthorizationObservation> body) {
        var g=mock(PaymentGateway.class);when(g.observeOrderAuthorization(any())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return body.apply(call.getArgument(0));});return g;
    }
    LegacyOrderEvidenceEnrollment service(PaymentGateway g){return new LegacyOrderEvidenceEnrollment(db.source,g,C);}
    JdbcOrderPaymentEvidenceReader.Read read(long order) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(db.source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(s->new JdbcOrderPaymentEvidenceReader(jdbc).read(order));
    }
    @Test void equalAmountAndRevisionEnrollsWithoutChangingOrderOrPaymentAndIsIdempotent() {
        var f=fixture();var before=jdbc.queryForMap("select content_revision,total_amount,status from orders where id=?",f.order());
        var g=gateway(MockOrderAuthorizationTransport::observe);var s=service(g);
        var result=s.enroll(f.order());assertThat(result.status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
        var r=read(f.order());assertThat(r.state()).isEqualTo(JdbcOrderPaymentEvidenceReader.State.READ);assertThat(r.decision().locallyVerified()).isTrue();
        assertThat(r.facts().epoch().origin()).isEqualTo(OrderPaymentEvidence.Origin.LEGACY_CURRENT_CHECKPOINT);
        assertThat(r.facts().current().items().getFirst().productName()).isEqualTo("保存商品名");
        assertThat(jdbc.queryForMap("select content_revision,total_amount,status from orders where id=?",f.order())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("AUTHORIZED");
        assertThat(s.enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ALREADY_ENROLLED);verify(g,times(1)).observeOrderAuthorization(any());
        assertThat(jdbc.queryForObject("select count(*) from order_payment_evidence_epochs where order_id=?",Integer.class,f.order())).isOne();
    }
    @ParameterizedTest @ValueSource(ints={5000,6000})
    void increasedRevisionCheckpointMayBeDecreasedOrEqual(int amount) {
        var f=fixture();jdbc.update("update orders set content_revision=2,total_amount=?,item_subtotal=?,tax_amount=? where id=?",amount,amount,amount*10/110,f.order());
        jdbc.update("update order_items set price=?,subtotal=? where order_id=?",amount,amount,f.order());
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
    }
    @ParameterizedTest @ValueSource(strings={"over","oldRevision","pending","ambiguousPayment","ambiguousAuth","deadline","preparing","cancelled","badTotal","badSnapshot","successfulCapture"})
    void invalidCandidatesNeverCallGet(String kind) {
        var f=fixture();switch(kind) {
            case "over" -> {jdbc.update("update orders set total_amount=6500,item_subtotal=6500,tax_amount=590 where id=?",f.order());jdbc.update("update order_items set price=6500,subtotal=6500 where order_id=?",f.order());}
            case "oldRevision" -> jdbc.update("update payment_transactions set order_content_revision=1 where id=?",f.auth());
            case "pending" -> db.transaction(f.payment(),"CAPTURE","PENDING",null);
            case "ambiguousPayment" -> db.payment(f.order());
            case "ambiguousAuth" -> db.transaction(f.payment(),"AUTHORIZE","FAILED",null);
            case "deadline" -> jdbc.update("update orders set change_deadline_at=timezone('Asia/Tokyo',clock_timestamp()) where id=?",f.order());
            case "preparing" -> jdbc.update("update orders set shipping_preparation_started_at=localtimestamp,shipping_preparation_started_by_account_id=1,shipping_preparation_started_by_username='admin' where id=?",f.order());
            case "cancelled" -> jdbc.update("update orders set status='CANCELLED' where id=?",f.order());
            case "badTotal" -> jdbc.update("update orders set tax_amount=1 where id=?",f.order());
            case "badSnapshot" -> jdbc.update("update order_items set subtotal=5999 where order_id=?",f.order());
            default -> {long t=db.transaction(f.payment(),"CAPTURE","SUCCEEDED",null);jdbc.update("update payment_transactions set completed_at=localtimestamp where id=?",t);}
        }
        var g=gateway(MockOrderAuthorizationTransport::observe);var result=service(g).enroll(f.order());
        assertThat(result.status()).isIn(LegacyOrderEvidenceEnrollment.Status.BLOCKED,LegacyOrderEvidenceEnrollment.Status.REVIEW_REQUIRED);verifyNoInteractions(g);
    }
    @ParameterizedTest @ValueSource(strings={"order","payment","cancel","shipping"})
    void changesDuringGetDoNotEnroll(String kind) {
        var f=fixture();var g=gateway(e->{
            switch(kind) {
                case "order" -> jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.order());
                case "payment" -> jdbc.update("update payments set updated_at=updated_at+interval '1 second' where id=?",f.payment());
                case "cancel" -> jdbc.update("update orders set status='CANCELLED' where id=?",f.order());
                default -> jdbc.update("update orders set shipping_preparation_started_at=localtimestamp,shipping_preparation_started_by_account_id=1,shipping_preparation_started_by_username='admin' where id=?",f.order());
            }return MockOrderAuthorizationTransport.observe(e);
        });
        assertThat(service(g).enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.CONFLICT);
        assertThat(jdbc.queryForObject("select count(*) from order_payment_evidence_epochs where order_id=?",Integer.class,f.order())).isZero();
    }
    @Test void concurrentEnrollmentHasOneRoot() throws Exception {
        var f=fixture();var barrier=new CyclicBarrier(2);var g=gateway(e->{var token=MockOrderAuthorizationTransport.observe(e);try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception x){throw new AssertionError(x);}return token;});
        var s=service(g);var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->s.enroll(f.order()));var b=pool.submit(()->s.enroll(f.order()));
            assertThat(List.of(a.get(15,TimeUnit.SECONDS).status(),b.get(15,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(LegacyOrderEvidenceEnrollment.Status.ENROLLED,LegacyOrderEvidenceEnrollment.Status.ALREADY_ENROLLED);
        } finally {pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from order_payment_evidence_epochs where order_id=?",Integer.class,f.order())).isOne();
    }
    @Test void failedGetLeavesNoEvidence() {
        var f=fixture();var g=gateway(e->{throw new org.springframework.web.client.ResourceAccessException("mock timeout");});
        assertThat(service(g).enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.OBSERVATION_UNAVAILABLE);
        assertThat(jdbc.queryForObject("select current_evidence_epoch_id from orders where id=?",UUID.class,f.order())).isNull();
    }
    @Test void databaseRollbackAndRetry() {
        var f=fixture();jdbc.execute("create function mock_enrollment_failure() returns trigger language plpgsql as $$ begin raise exception 'mock rollback'; end $$");
        jdbc.execute("create trigger mock_enrollment_failure before insert on order_payment_evidence_epochs for each row execute function mock_enrollment_failure()");
        var s=service(gateway(MockOrderAuthorizationTransport::observe));
        try {assertThat(s.enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.COMMIT_UNKNOWN);}
        finally {jdbc.execute("drop trigger mock_enrollment_failure on order_payment_evidence_epochs");jdbc.execute("drop function mock_enrollment_failure()");}
        assertThat(jdbc.queryForObject("select current_evidence_epoch_id from orders where id=?",UUID.class,f.order())).isNull();
        assertThat(s.enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
    }
    @Test void delayedObservationIsNotPersisted() {
        var f=fixture();var ticks=new AtomicLong();
        var g=gateway(e->{var token=MockOrderAuthorizationTransport.observe(e,java.time.Clock.systemUTC(),ticks::get);ticks.set(6_000_000_000L);return token;});
        assertThat(service(g).enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.OBSERVATION_UNAVAILABLE);
        assertThat(jdbc.queryForObject("select current_evidence_epoch_id from orders where id=?",UUID.class,f.order())).isNull();
    }
    @Test void lostCommitResponseIsResolvedByRereadingWithoutAnotherGet() {
        var f=fixture();var fail=new AtomicBoolean(true);
        var proxy=new org.springframework.jdbc.datasource.DelegatingDataSource(db.source) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var c=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(ignored,method,args)->{
                    try {
                        if(method.getName().equals("commit")&&!c.isReadOnly()&&fail.compareAndSet(true,false)) {c.commit();throw new java.sql.SQLException("mock lost commit response","08006");}
                        return method.invoke(c,args);
                    }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        var g=gateway(MockOrderAuthorizationTransport::observe);var s=new LegacyOrderEvidenceEnrollment(proxy,g,C);
        assertThat(s.enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.COMMIT_UNKNOWN);
        assertThat(s.enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ALREADY_ENROLLED);
        verify(g,times(1)).observeOrderAuthorization(any());
        assertThat(jdbc.queryForObject("select count(*) from order_payment_evidence_epochs where order_id=?",Integer.class,f.order())).isOne();
    }
    @Test void abortedHistoricalAuthorizationIsNotReinterpretedAsCompletedReauthorization() {
        var f=fixture();UUID op=UUID.randomUUID();long p=db.payment(f.order());jdbc.update("update payments set status='FAILED',provider_payment_id=null where id=?",p);
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
             old_payment_id,new_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
             shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
             accepted_at,deadline_at,grace_until,state,created_at,updated_at)
            select ?,id,user_id,?,repeat('a',64),0,?,?,6000,7000,7000,0,0,'送料',0,1,'STANDARD','標準',10,10,
             ordered_at,change_deadline_at,change_deadline_at+interval '10 minutes','ABORTED',localtimestamp,localtimestamp from orders where id=?
            """,op,UUID.randomUUID(),f.payment(),p,f.order());
        long auth=db.transaction(p,"AUTHORIZE","FAILED",null);
        jdbc.update("update payment_transactions set purpose='ORDER_CHANGE_AUTHORIZATION',order_change_operation_id=?,order_change_order_id=? where id=?",op,f.order(),auth);
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
    }
    void appendRetainedHistory(Fixture f,int amount,boolean corruptDigest) {
        var before=read(f.order());var base=before.facts().current();var e=before.facts().epoch();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(db.source));
        tx.execute(status->{
            jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());
            UUID op=UUID.randomUUID();
            jdbc.update("""
              insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
               old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
               shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
               accepted_at,deadline_at,grace_until,state,created_at,updated_at,execution_version,execution_route,
               base_evidence_epoch_id,base_evidence_history_id,old_authorization_transaction_id,old_authorized_amount,old_authorization_revision,
               admission_observation_payload,admission_observation_fingerprint)
              select ?,id,user_id,?,repeat('a',64),content_revision,?,total_amount,?,?,0,?,'保存送料',0,1,'STANDARD','標準税率',10,10,
               ordered_at,change_deadline_at,change_deadline_at+interval '10 minutes','RESERVED',localtimestamp,localtimestamp,
               1,'RETAIN_AUTH',current_evidence_epoch_id,evidence_head_history_id,?,6000,0,convert_to('{}','UTF8'),sha256(convert_to('{}','UTF8')) from orders where id=?
              """,op,UUID.randomUUID(),f.payment(),amount,amount,amount*10/110,f.auth(),f.order());
            var item=base.items().getFirst();var after=new OrderContentSnapshot(1,base.orderId(),base.customerId(),base.contentRevision()+1,
                List.of(new OrderContentSnapshot.Item(item.orderItemId(),item.productId(),item.productName(),item.categoryId(),item.categoryName(),amount,1,amount,item.taxCategoryId(),item.taxCategoryCode(),item.taxCategoryName(),item.taxRateBasisPoints())),base.charges(),amount,0,amount*10/110,amount);
            long id=jdbc.queryForObject("select nextval(pg_get_serial_sequence('order_content_change_histories','id'))",Long.class);
            var codec=new OrderContentSnapshotCodec();String prior=before.facts().headHistoryId()==null?e.digest():before.facts().histories().getLast().digest();
            var change=new OrderPaymentEvidence.Change(1,id,f.order(),op,e.id(),before.facts().headHistoryId(),base.contentRevision(),base.totalAmount(),codec.fingerprint(base),after,prior,null);
            String digest=corruptDigest?"ff".repeat(32):OrderEvidenceDigests.change(change);
            jdbc.update("""
              insert into order_content_change_histories(id,order_id,change_source,changed_by_type,changed_by_username,
               old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount,
               before_content_revision,after_content_revision,evidence_epoch_id,previous_history_id,before_fingerprint,after_fingerprint,after_snapshot,evidence_version,evidence_digest)
              values (?,?,'CUSTOMER','USER','fixture',?,?,0,0,?,?,?,?,?,?,?,?,?,?,?,1,?)
              """,id,f.order(),base.itemSubtotal(),amount,base.taxAmount(),after.taxAmount(),base.totalAmount(),amount,base.contentRevision(),after.contentRevision(),e.id(),before.facts().headHistoryId(),HexFormat.of().parseHex(codec.fingerprint(base)),HexFormat.of().parseHex(codec.fingerprint(after)),codec.encode(after),HexFormat.of().parseHex(digest));
            jdbc.update("update order_items set price=?,subtotal=? where order_id=?",amount,amount,f.order());
            jdbc.update("update orders set item_subtotal=?,total_amount=?,tax_amount=?,content_revision=?,evidence_head_history_id=? where id=?",amount,amount,after.taxAmount(),after.contentRevision(),id,f.order());
            jdbc.update("update order_change_operations set state='COMPLETED',content_change_history_id=?,version=version+1 where id=?",id,op);return null;
        });
    }
    @Test void readerAssemblesWholeRetainedChainAndRejectsBadStoredDigest() {
        var f=fixture();var s=service(gateway(MockOrderAuthorizationTransport::observe));assertThat(s.enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
        appendRetainedHistory(f,5000,false);appendRetainedHistory(f,6000,false);
        var read=read(f.order());assertThat(read.decision().disposition()).isEqualTo(OrderPaymentEvidenceDecision.Disposition.EVIDENCE_RETAINED_AUTH);assertThat(read.facts().histories()).hasSize(2);
        var bad=fixture();assertThat(s.enroll(bad.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
        appendRetainedHistory(bad,5000,true);assertThat(read(bad.order()).decision().locallyVerified()).isFalse();
        assertThat(s.enroll(bad.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.REVIEW_REQUIRED);
    }
    @ParameterizedTest @ValueSource(strings={"initialAmount","initialRevision","checkpointOver","checkpointOlder","digestShape","fingerprint"})
    void postgresStillRejectsInvalidOriginBoundariesAndHashShapes(String mutation) {
        var f=fixture();
        if(mutation.equals("checkpointOlder")) {jdbc.update("update orders set content_revision=1 where id=?",f.order());jdbc.update("update payment_transactions set order_content_revision=1 where id=?",f.auth());}
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).enroll(f.order()).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);
        var current=read(f.order()).facts().current();var original=current.items().getFirst();
        int amount=mutation.equals("initialAmount")?5000:mutation.equals("checkpointOver")?6500:6000;
        int revision=mutation.equals("initialRevision")?1:0;
        var body=new OrderContentSnapshot(1,current.orderId(),current.customerId(),revision,List.of(new OrderContentSnapshot.Item(original.orderItemId(),original.productId(),original.productName(),original.categoryId(),original.categoryName(),amount,1,amount,original.taxCategoryId(),original.taxCategoryCode(),original.taxCategoryName(),original.taxRateBasisPoints())),current.charges(),amount,0,amount*10/110,amount);
        var codec=new OrderContentSnapshotCodec();byte[] snapshot=codec.encode(body),fingerprint=HexFormat.of().parseHex(codec.fingerprint(body));
        if(mutation.equals("fingerprint"))fingerprint=new byte[32];
        final byte[] fp=fingerprint;String origin=mutation.startsWith("initial")?"INITIAL_AUTH":"LEGACY_CURRENT_CHECKPOINT";
        byte[] digest=mutation.equals("digestShape")?new byte[1]:new byte[32];
        db.rejects("23514",()->jdbc.update("""
            insert into order_payment_evidence_epochs
            select (jsonb_populate_record(null::order_payment_evidence_epochs,to_jsonb(e)||jsonb_build_object(
              'id',?::uuid,'origin_kind',?::text,'start_content_revision',?::int,'start_total_amount',?::int,
              'start_snapshot',?::bytea,'start_fingerprint',?::bytea,'evidence_digest',?::bytea))).*
            from order_payment_evidence_epochs e where order_id=?
            """,UUID.randomUUID(),origin,revision,amount,snapshot,fp,digest,f.order()));
    }
}
