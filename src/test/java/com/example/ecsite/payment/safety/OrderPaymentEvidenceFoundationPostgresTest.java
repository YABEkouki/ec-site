package com.example.ecsite.payment.safety;

import com.example.ecsite.service.order.evidence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Only the existing IsolatedTestLauncher may provide the guarded container connection. */
class OrderPaymentEvidenceFoundationPostgresTest {
    static PaymentSafetyExpandPostgresTest db;
    static JdbcTemplate jdbc;
    static final OrderContentSnapshotCodec CODEC=new OrderContentSnapshotCodec();
    record Fixture(long order,long customer,long payment,long auth,UUID epoch,byte[] snapshot,byte[] fingerprint) {}
    @BeforeAll static void database() {
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        db=new PaymentSafetyExpandPostgresTest();db.isolatedSchema();db.migrate("68");jdbc=db.jdbc;
        long[] legacy=db.legacyFixture();
        var old=fixture();long oldHistory=legacyHistory(old,6000,5000);UUID oldOperation=legacyOperation(old);
        var before=db.legacyRows();db.migrate("69");db.migrate("70");
        for(var entry:before.entrySet()) {
            String remove=entry.getKey().equals("orders")?"-'current_evidence_epoch_id'-'evidence_head_history_id'":"";
            String sql="select (to_jsonb(t)-'active_payment_id'-'purpose'"+remove+")::text from "+entry.getKey()+" t order by id";
            assertThat(jdbc.queryForList(sql,String.class)).isEqualTo(entry.getValue());
        }
        assertThat(jdbc.queryForObject("select current_evidence_epoch_id is null from orders where id=?",Boolean.class,legacy[0])).isTrue();
        assertThat(jdbc.queryForObject("select evidence_version is null and before_content_revision is null from order_content_change_histories where id=?",Boolean.class,oldHistory)).isTrue();
        assertThat(jdbc.queryForObject("select execution_version is null and execution_route is null from order_change_operations where id=?",Boolean.class,oldOperation)).isTrue();
        jdbc.update("update order_change_operations set state='ABORTED',version=version+1 where id=?",oldOperation);
        jdbc.update("update order_content_change_histories set reason='legacy preserved' where id=?",oldHistory);
        db.rejects("55000",()->jdbc.update("update order_change_operations set execution_version=1 where id=?",oldOperation));
        for(String sql:List.of("update capture_requests set connection_id=connection_id","delete from capture_requests","truncate capture_requests"))db.rejects("55000",()->jdbc.execute(sql));
    }
    static <T>T tx(Supplier<T> body) { return new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->body.get()); }
    static byte[] hash(byte[] b) { try{return java.security.MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception e){throw new AssertionError(e);} }
    static byte[] snapshot(long order,long customer,int revision,int amount) {
        int tax=java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.TEN).divide(java.math.BigDecimal.valueOf(110),0,java.math.RoundingMode.DOWN).intValueExact();
        return CODEC.encode(new OrderContentSnapshot(1,order,customer,revision,
                List.of(new OrderContentSnapshot.Item(3,7,"商品",4,"分類",amount,1,amount,5,"STANDARD","標準",1000)),
                List.of(new OrderContentSnapshot.Charge(6,"SHIPPING","送料",0,5,"STANDARD","標準",1000,10)),amount,0,tax,amount));
    }
    static Fixture fixture() {
        long order=db.order(),p=db.payment(order);
        long customer=jdbc.queryForObject("select user_id from orders where id=?",Long.class,order);
        jdbc.update("update orders set total_amount=6000,item_subtotal=6000,tax_amount=545,change_deadline_at=ordered_at+interval '1 hour',active_payment_id=? where id=?",p,order);
        jdbc.update("update payments set amount=6000 where id=?",p);
        long auth=db.transaction(p,"AUTHORIZE","SUCCEEDED","INITIAL_ORDER_AUTHORIZATION");
        jdbc.update("update payment_transactions set amount=6000,completed_at=localtimestamp,provider_transaction_id=(select provider_payment_id from payments where id=?) where id=?",p,auth);
        byte[] body=snapshot(order,customer,0,6000);
        return new Fixture(order,customer,p,auth,UUID.randomUUID(),body,hash(body));
    }
    static void insertEpoch(Fixture f,String origin,int revision,int amount,UUID op,Long history,UUID previous) {
        byte[] body=snapshot(f.order(),f.customer(),revision,amount),observation="{}".getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
            insert into order_payment_evidence_epochs(id,order_id,payment_id,authorization_transaction_id,origin_kind,
              origin_operation_id,origin_history_id,previous_epoch_id,start_content_revision,start_total_amount,
              authorized_amount,authorization_revision,provider_flow_id,start_snapshot,start_fingerprint,
              observation_payload,observation_fingerprint,observation_started_at,observation_finished_at,evidence_version,evidence_digest)
            select ?,?,?,?, ?,?,?,?, ?,?,p.amount,t.order_content_revision,p.provider_payment_id,?,?, ?,?,clock_timestamp(),clock_timestamp(),1,?
            from payments p join payment_transactions t on t.id=? where p.id=?
            """,f.epoch(),f.order(),f.payment(),f.auth(),origin,op,history,previous,revision,amount,body,hash(body),observation,hash(observation),hash(new byte[]{1}),f.auth(),f.payment());
    }
    static void enroll(Fixture f) {
        tx(()->{jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());
            insertEpoch(f,"INITIAL_AUTH",0,6000,null,null,null);
            jdbc.update("update orders set current_evidence_epoch_id=? where id=?",f.epoch(),f.order());return null;});
    }
    static UUID operation(Fixture f,int beforeRevision,int before,int after,String route,Long previous) {
        UUID id=UUID.randomUUID();byte[] observation="{}".getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
             old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
             shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
             accepted_at,deadline_at,grace_until,state,created_at,updated_at,execution_version,execution_route,
             base_evidence_epoch_id,base_evidence_history_id,old_authorization_transaction_id,old_authorized_amount,
             old_authorization_revision,admission_observation_payload,admission_observation_fingerprint)
            select ?,o.id,o.user_id,?,repeat('a',64),?, ?,?,?,?,0,0,'送料',0,5,'STANDARD','標準',10,10,
              o.ordered_at,o.change_deadline_at,o.change_deadline_at+interval '10 minutes','RESERVED',localtimestamp,localtimestamp,
              1,?,?,?,e.authorization_transaction_id,e.authorized_amount,e.authorization_revision,?,?
            from orders o join order_payment_evidence_epochs e on e.id=? where o.id=?
            """,id,UUID.randomUUID(),beforeRevision,f.payment(),before,after,after,route,f.epoch(),previous,observation,hash(observation),f.epoch(),f.order());
        return id;
    }
    static long insertHistory(Fixture f,UUID op,Long previous,int revision,int before,int after,byte[] beforeFp) {
        byte[] body=snapshot(f.order(),f.customer(),revision+1,after);
        return jdbc.queryForObject("""
            insert into order_content_change_histories(order_id,change_source,changed_by_type,changed_by_account_id,
             changed_by_username,old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,
             old_total_amount,new_total_amount,before_content_revision,after_content_revision,evidence_epoch_id,
             previous_history_id,before_fingerprint,after_fingerprint,after_snapshot,evidence_version,evidence_digest)
            values (?,'CUSTOMER','USER',?,'fixture',?,?,0,0,0,0,?,?,?,?,?,?,?,?,?,1,?) returning id
            """,Long.class,f.order(),f.customer(),before,after,before,after,revision,revision+1,f.epoch(),previous,beforeFp,hash(body),body,hash(new byte[]{2}));
    }
    static long change(Fixture f,Long previous,int revision,int before,int after) {
        return tx(()->{
            jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());
            var op=operation(f,revision,before,after,"RETAIN_AUTH",previous);
            long history=insertHistory(f,op,previous,revision,before,after,hash(snapshot(f.order(),f.customer(),revision,before)));
            jdbc.update("insert into order_content_change_items(history_id,product_id,product_name,change_type) values (?,7,'商品','UPDATED')",history);
            jdbc.update("insert into order_content_change_charges(history_id,charge_type,change_type) values (?,'SHIPPING','UPDATED')",history);
            jdbc.update("update orders set evidence_head_history_id=?,content_revision=?,item_subtotal=?,total_amount=?,tax_amount=? where id=?",history,revision+1,after,after,(after*10)/110,f.order());
            jdbc.update("update order_change_operations set state='COMPLETED',version=version+1,content_change_history_id=? where id=?",history,op);
            return history;
        });
    }
    @Test void initialAndContinuousEvidenceCanReturnToAuthorizationAmount() {
        var f=fixture();enroll(f);long a=change(f,null,0,6000,5000);long b=change(f,a,1,5000,6000);
        assertThat(jdbc.queryForObject("select evidence_head_history_id from orders where id=?",Long.class,f.order())).isEqualTo(b);
    }
    @Test void legacyCheckpointDoesNotBackfillHistory() {
        var f=fixture();jdbc.update("update orders set content_revision=2,total_amount=5000,item_subtotal=5000,tax_amount=454 where id=?",f.order());
        tx(()->{insertEpoch(f,"LEGACY_CURRENT_CHECKPOINT",2,5000,null,null,null);jdbc.update("update orders set current_evidence_epoch_id=? where id=?",f.epoch(),f.order());return null;});
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?",Integer.class,f.order())).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"update","delete","truncate"})
    void epochIsImmutable(String kind) {
        var f=fixture();enroll(f);
        String sql=switch(kind){case "update"->"update order_payment_evidence_epochs set evidence_version=evidence_version where id='"+f.epoch()+"'";case "delete"->"delete from order_payment_evidence_epochs where id='"+f.epoch()+"'";default->"truncate order_payment_evidence_epochs cascade";};
        db.rejects("55000",()->jdbc.execute(sql));
    }
    @Test void duplicatePaymentAndAuthorizationAreRejected() {
        var f=fixture();enroll(f);var duplicate=new Fixture(f.order(),f.customer(),f.payment(),f.auth(),UUID.randomUUID(),f.snapshot(),f.fingerprint());
        db.rejects("23505",()->insertEpoch(duplicate,"INITIAL_AUTH",0,6000,null,null,null));
    }
    @Test void malformedRevisionAndFingerprintCannotBeCommitted() {
        var f=fixture();enroll(f);
        db.rejects("23514",()->tx(()->{insertHistory(f,null,null,1,6000,5000,f.fingerprint());return null;}));
        db.rejects("23514",()->tx(()->{insertHistory(f,null,null,0,6000,5000,hash(new byte[]{9}));return null;}));
    }
    @Test void ordersCannotDropEvidenceOrSelectForeignEpoch() {
        var f=fixture();enroll(f);var other=fixture();enroll(other);
        db.rejects("55000",()->jdbc.update("update orders set current_evidence_epoch_id=null where id=?",f.order()));
        assertThatThrownBy(()->jdbc.update("update orders set current_evidence_epoch_id=? where id=?",other.epoch(),f.order())).isInstanceOf(RuntimeException.class);
    }
    @Test void sameOrderPaymentCannotBeSwitchedWithoutCompletedReauthorization() {
        var f=fixture();enroll(f);
        long p=db.payment(f.order());jdbc.update("update payments set amount=6000 where id=?",p);
        long a=db.transaction(p,"AUTHORIZE","SUCCEEDED","INITIAL_ORDER_AUTHORIZATION");
        jdbc.update("update payment_transactions set amount=6000,completed_at=localtimestamp,provider_transaction_id=(select provider_payment_id from payments where id=?) where id=?",p,a);
        var next=new Fixture(f.order(),f.customer(),p,a,UUID.randomUUID(),f.snapshot(),f.fingerprint());
        db.rejects("23514",()->tx(()->{insertEpoch(next,"INITIAL_AUTH",0,6000,null,null,null);jdbc.update("update orders set current_evidence_epoch_id=?,active_payment_id=? where id=?",next.epoch(),p,f.order());return null;}));
    }
    @Test void admittedOperationIdentityIsImmutable() {
        var f=fixture();enroll(f);UUID id=tx(()->operation(f,0,6000,5000,"RETAIN_AUTH",null));
        db.rejects("55000",()->jdbc.update("update order_change_operations set execution_route='REAUTHORIZE' where id=?",id));
        db.rejects("55000",()->jdbc.update("update order_change_operations set old_authorized_amount=7000 where id=?",id));
    }
    @Test void historyAndChildrenCannotBeChangedAfterCompletion() {
        var f=fixture();enroll(f);long h=change(f,null,0,6000,5000);
        db.rejects("55000",()->jdbc.update("update order_content_change_histories set reason='rewrite' where id=?",h));
        db.rejects("55000",()->jdbc.update("delete from order_content_change_histories where id=?",h));
        db.rejects("55000",()->jdbc.update("insert into order_content_change_items(history_id,product_id,product_name,change_type) values (?,7,'late','UPDATED')",h));
    }
    @Test void rollbackPermitsRetryWithoutOrphanEvidence() {
        var f=fixture();
        assertThatThrownBy(()->tx(()->{insertEpoch(f,"INITIAL_AUTH",0,6000,null,null,null);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        enroll(f);assertThat(jdbc.queryForObject("select count(*) from order_payment_evidence_epochs where id=?",Integer.class,f.epoch())).isOne();
    }

    static long legacyHistory(Fixture f,int before,int after) {
        return jdbc.queryForObject("""
            insert into order_content_change_histories(order_id,change_source,changed_by_type,changed_by_username,
              old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,
              old_total_amount,new_total_amount) values (?,'CUSTOMER','USER','fixture',?,?,0,0,0,0,?,?) returning id
            """,Long.class,f.order(),before,after,before,after);
    }
    static UUID legacyOperation(Fixture f) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
              old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
              shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
              accepted_at,deadline_at,grace_until,state,created_at,updated_at)
            select ?,id,user_id,?,repeat('a',64),0,?,6000,7000,7000,0,0,'送料',0,5,'STANDARD','標準',10,10,
              ordered_at,change_deadline_at,change_deadline_at+interval '10 minutes','RESERVED',localtimestamp,localtimestamp
            from orders where id=?
            """,id,UUID.randomUUID(),f.payment(),f.order());
        return id;
    }
    @Test void completedReauthorizationCreatesNewGenerationThenAllowsRetainedChange() {
        var f=fixture();enroll(f);
        Fixture next=reauthorize(f,null);
        change(next,null,1,7000,6500);
        assertThat(jdbc.queryForObject("select previous_epoch_id from order_payment_evidence_epochs where id=?",UUID.class,next.epoch())).isEqualTo(f.epoch());
    }
    static Fixture reauthorize(Fixture f,Long existingHistory) {
        return tx(()->{
            jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());
            UUID op=operation(f,0,6000,7000,"REAUTHORIZE",null);
            long p=db.payment(f.order());jdbc.update("update payments set amount=7000 where id=?",p);
            jdbc.update("update order_change_operations set new_payment_id=?,state='AUTHORIZING' where id=?",p,op);
            long a=db.transaction(p,"AUTHORIZE","SUCCEEDED",null);
            jdbc.update("update payment_transactions set purpose='ORDER_CHANGE_AUTHORIZATION',amount=7000,order_content_revision=1,order_change_operation_id=?,order_change_order_id=(select order_id from payments where payments.id=payment_transactions.payment_id),completed_at=localtimestamp,provider_transaction_id=(select provider_payment_id from payments where id=?) where id=?",op,p,a);
            long cancel=db.transaction(f.payment(),"CANCEL","SUCCEEDED",null);
            jdbc.update("update payment_transactions set purpose='ORDER_CHANGE_OLD_AUTH_CANCEL',amount=6000,order_change_operation_id=?,order_change_order_id=(select order_id from payments where payments.id=payment_transactions.payment_id),completed_at=localtimestamp,provider_transaction_id=(select provider_payment_id from payments where id=?) where id=?",op,f.payment(),cancel);
            jdbc.update("update payments set status='CANCELLED' where id=?",f.payment());
            long h=existingHistory==null?legacyHistory(f,6000,7000):existingHistory;
            jdbc.update("update order_change_operations set state='COMPLETED',switch_decided_at=localtimestamp,content_change_history_id=? where id=?",h,op);
            var n=new Fixture(f.order(),f.customer(),p,a,UUID.randomUUID(),snapshot(f.order(),f.customer(),1,7000),hash(snapshot(f.order(),f.customer(),1,7000)));
            insertEpoch(n,"REAUTH_COMPLETED",1,7000,op,h,f.epoch());
            jdbc.update("update orders set current_evidence_epoch_id=?,evidence_head_history_id=null,active_payment_id=?,content_revision=1,total_amount=7000,item_subtotal=7000,tax_amount=636 where id=?",n.epoch(),p,f.order());
            return n;
        });
    }
    @Test void wrongFinancialOwnersAndUnsuccessfulAuthorizationAreRejected() {
        var f=fixture();var other=fixture();
        var foreignPayment=new Fixture(f.order(),f.customer(),other.payment(),other.auth(),UUID.randomUUID(),f.snapshot(),f.fingerprint());
        assertThatThrownBy(()->insertEpoch(foreignPayment,"INITIAL_AUTH",0,6000,null,null,null)).isInstanceOf(RuntimeException.class);
        var foreignAuth=new Fixture(f.order(),f.customer(),f.payment(),other.auth(),UUID.randomUUID(),f.snapshot(),f.fingerprint());
        assertThatThrownBy(()->insertEpoch(foreignAuth,"INITIAL_AUTH",0,6000,null,null,null)).isInstanceOf(RuntimeException.class);
        jdbc.update("update payment_transactions set status='FAILED' where id=?",f.auth());
        db.rejects("23514",()->insertEpoch(f,"INITIAL_AUTH",0,6000,null,null,null));
    }
    @ParameterizedTest @ValueSource(ints={-1,1})
    void invalidEpochRevisionCannotBeInstalled(int revision) {
        var f=fixture();
        assertThatThrownBy(()->tx(()->{insertEpoch(f,"INITIAL_AUTH",revision,6000,null,null,null);return null;})).isInstanceOf(RuntimeException.class);
    }
    @Test void epochAmountsAndDeferredInstallationAreChecked() {
        var f=fixture();
        db.rejects("23514",()->tx(()->{insertEpoch(f,"INITIAL_AUTH",0,6500,null,null,null);return null;}));
        db.rejects("23514",()->tx(()->{insertEpoch(f,"INITIAL_AUTH",0,6000,null,null,null);return null;}));
        assertThat(jdbc.queryForObject("select count(*) from order_payment_evidence_epochs where order_id=?",Integer.class,f.order())).isZero();
        enroll(f);
        db.rejects("23503",()->jdbc.update("update payment_transactions set amount=5900 where id=?",f.auth()));
        db.rejects("23503",()->jdbc.update("update payments set amount=5900 where id=?",f.payment()));
        // Ordinary lifecycle transitions remain possible; financial admission is a Java responsibility.
        jdbc.update("update payments set status='CAPTURED' where id=?",f.payment());
    }
    @ParameterizedTest @ValueSource(strings={"'evidence_version',2","'evidence_digest',null","'start_fingerprint','00'","'observation_fingerprint','00'"})
    void epochVersionNullShapeAndHashesAreChecked(String replacement) {
        var f=fixture();enroll(f);
        db.rejects(replacement.equals("'evidence_digest',null")?"23502":"23514",()->jdbc.update("insert into order_payment_evidence_epochs select (jsonb_populate_record(null::order_payment_evidence_epochs,to_jsonb(e)||jsonb_build_object("+replacement+"))).* from order_payment_evidence_epochs e where id=?",f.epoch()));
    }
    @Test void historyRevisionDuplicatesBranchesAndForeignEpochAreRejected() {
        var f=fixture();enroll(f);long first=change(f,null,0,6000,5000);
        db.rejects("23505",()->insertHistory(f,null,null,0,6000,5000,f.fingerprint()));
        long second=change(f,first,1,5000,5500);
        db.rejects("23505",()->insertHistory(f,null,first,1,5000,5400,hash(snapshot(f.order(),f.customer(),1,5000))));
        var other=fixture();enroll(other);
        assertThatThrownBy(()->insertHistory(other,null,second,2,5500,5000,hash(snapshot(f.order(),f.customer(),2,5500)))).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"order_content_change_items","order_content_change_charges"})
    void completedChildrenRejectUpdateDeleteAndReparent(String table) {
        var f=fixture();enroll(f);long h=change(f,null,0,6000,5000);
        for(String sql:List.of("update "+table+" set history_id=history_id where history_id=?","delete from "+table+" where history_id=?"))
            db.rejects("55000",()->jdbc.update(sql,h));
        db.rejects("55000",()->jdbc.update("insert into order_content_change_charges(history_id,charge_type,change_type) values (?,'SHIPPING','UPDATED')",h));
    }
    @Test void orderHeadAndActivePaymentCannotDivergeButCancellationKeepsEvidence() {
        var f=fixture();enroll(f);long h=change(f,null,0,6000,5000);
        assertThatThrownBy(()->jdbc.update("update orders set evidence_head_history_id=null where id=?",f.order())).isInstanceOf(RuntimeException.class);
        db.rejects("23514",()->jdbc.update("update orders set content_revision=2 where id=?",f.order()));
        db.rejects("23514",()->jdbc.update("update orders set active_payment_id=null where id=?",f.order()));
        jdbc.update("update orders set status='CANCELLED',active_payment_id=null where id=?",f.order());
        assertThat(jdbc.queryForObject("select evidence_head_history_id from orders where id=?",Long.class,f.order())).isEqualTo(h);
    }
    @Test void retainedRouteCannotCreateFinancialWorkOrUseStaleBase() {
        var f=fixture();enroll(f);UUID op=operation(f,0,6000,5000,"RETAIN_AUTH",null);
        db.rejects("23514",()->jdbc.update("update order_change_operations set state='AUTHORIZING' where id=?",op));
        long transaction=db.transaction(f.payment(),"CANCEL","PENDING",null);
        db.rejects("23514",()->jdbc.update("update payment_transactions set purpose='ORDER_CHANGE_OLD_AUTH_CANCEL',order_change_operation_id=?,order_change_order_id=(select order_id from payments where payments.id=payment_transactions.payment_id) where id=?",op,transaction));
        jdbc.update("update order_change_operations set state='ABORTED' where id=?",op);
        change(f,null,0,6000,5000);
        db.rejects("23514",()->operation(f,0,6000,5500,"RETAIN_AUTH",null));
    }
    @Test void legacyHistoryRemainsWritableButCannotBeBackfilled() {
        var f=fixture();long h=legacyHistory(f,6000,5000);
        jdbc.update("update order_content_change_histories set reason='legacy update' where id=?",h);
        enroll(f);
        db.rejects("55000",()->jdbc.update("update order_content_change_histories set evidence_version=1 where id=?",h));
        jdbc.update("update order_content_change_histories set reason='still legacy' where id=?",h);
    }

    static <T> T bounded(Future<T> future) throws Exception { return future.get(20,TimeUnit.SECONDS); }
    static void awaitBlocked(String label) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<end) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where application_name=? and wait_event_type='Lock')",Boolean.class,label))) return;
            Thread.sleep(15);
        }
        fail("contender did not reach a database lock wait");
    }
    static void namedTransaction(String label,Runnable body) {
        tx(()->{jdbc.queryForObject("select set_config('application_name',?,true)",String.class,label);body.run();return null;});
    }
    @ParameterizedTest @ValueSource(strings={"repeatable read","serializable"})
    void staleSnapshotIsolationCannotBypassFreeze(String isolation) {
        var f=fixture();long h=legacyHistory(f,6000,5000);
        db.rejects("25000",()->tx(()->{jdbc.execute("set transaction isolation level "+isolation);jdbc.update("insert into order_content_change_items(history_id,product_id,product_name,change_type) values (?,7,'blocked','UPDATED')",h);return null;}));
    }
    @Test void concurrentEpochCreationSerializesAndLoserRollsBack() throws Exception {
        var f=fixture();var pool=Executors.newFixedThreadPool(2);var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            Future<?> winner=pool.submit(()->tx(()->{insertEpoch(f,"INITIAL_AUTH",0,6000,null,null,null);jdbc.update("update orders set current_evidence_epoch_id=? where id=?",f.epoch(),f.order());inserted.countDown();await(release);return null;}));
            assertThat(inserted.await(10,TimeUnit.SECONDS)).isTrue();
            String label="f125_root_"+UUID.randomUUID();
            Future<?> loser=pool.submit(()->db.rejects("23505",()->namedTransaction(label,()->insertEpoch(new Fixture(f.order(),f.customer(),f.payment(),f.auth(),UUID.randomUUID(),f.snapshot(),f.fingerprint()),"INITIAL_AUTH",0,6000,null,null,null))));
            awaitBlocked(label);release.countDown();bounded(winner);bounded(loser);
        } finally {release.countDown();pool.shutdownNow();}
    }
    static void await(CountDownLatch latch) { try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);} }
    @ParameterizedTest @ValueSource(strings={"item","charge","reparent","delete"})
    void sourceHistoryFreezeSerializesConcurrentChildWrites(String action) throws Exception {
        var f=fixture();enroll(f);long source=legacyHistory(f,6000,7000);long other=legacyHistory(f,6000,7000);
        long child=jdbc.queryForObject("insert into order_content_change_items(history_id,product_id,product_name,change_type) values (?,7,'legacy','UPDATED') returning id",Long.class,action.equals("reparent")?other:source);
        var pool=Executors.newFixedThreadPool(2);var frozen=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            Future<?> winner=pool.submit(()->tx(()->{reauthorize(f,source);frozen.countDown();await(release);return null;}));
            assertThat(frozen.await(10,TimeUnit.SECONDS)).isTrue();
            String label="f125_freeze_"+UUID.randomUUID();
            Future<?> loser=pool.submit(()->db.rejects("55000",()->namedTransaction(label,()->{
                switch(action) {
                    case "item" -> jdbc.update("insert into order_content_change_items(history_id,product_id,product_name,change_type) values (?,7,'late','UPDATED')",source);
                    case "charge" -> jdbc.update("insert into order_content_change_charges(history_id,charge_type,change_type) values (?,'SHIPPING','UPDATED')",source);
                    case "reparent" -> jdbc.update("update order_content_change_items set history_id=? where id=?",source,child);
                    default -> jdbc.update("delete from order_content_change_items where id=?",child);
                }
            })));
            awaitBlocked(label);release.countDown();bounded(winner);bounded(loser);
            db.rejects("55000",()->jdbc.update("update order_content_change_histories set reason='source rewrite' where id=?",source));
            db.rejects("55000",()->jdbc.update("delete from order_content_change_histories where id=?",source));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void concurrentHistoryBranchAndMutationAreRejectedWhileLegacyUpdateSucceeds() throws Exception {
        var f=fixture();enroll(f);long first=change(f,null,0,6000,5000);var legacy=fixture();long legacyHistory=legacyHistory(legacy,6000,5000);
        var pool=Executors.newFixedThreadPool(2);var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            Future<?> winner=pool.submit(()->tx(()->{change(f,first,1,5000,5500);inserted.countDown();await(release);return null;}));
            assertThat(inserted.await(10,TimeUnit.SECONDS)).isTrue();
            String label="f125_branch_"+UUID.randomUUID();
            Future<?> loser=pool.submit(()->db.rejects("23505",()->namedTransaction(label,()->insertHistory(f,null,first,1,5000,5400,hash(snapshot(f.order(),f.customer(),1,5000))))));
            awaitBlocked(label);
            db.rejects("55000",()->jdbc.update("update order_content_change_histories set reason='concurrent rewrite' where id=?",first));
            jdbc.update("update order_content_change_histories set reason='concurrent legacy' where id=?",legacyHistory);
            release.countDown();bounded(winner);bounded(loser);
        } finally {release.countDown();pool.shutdownNow();}
    }
}
