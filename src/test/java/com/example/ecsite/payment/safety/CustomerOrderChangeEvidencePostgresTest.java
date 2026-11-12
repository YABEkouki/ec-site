package com.example.ecsite.payment.safety;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.service.order.history.*;
import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reuses the guarded PG17 foundation and real Feature125/legacy writers; no provider transport. */
class CustomerOrderChangeEvidencePostgresTest extends OrderEditReauthorizationIntegrationTest {
    static org.testcontainers.postgresql.PostgreSQLContainer readerDatabase;
    @org.springframework.test.context.DynamicPropertySource
    static void dedicatedCommitTrackingDatabase(org.springframework.test.context.DynamicPropertyRegistry properties){
        readerDatabase=OrderEditAdmissionFoundationPostgresTest.commitTrackingContainer();readerDatabase.start();
        properties.add("spring.datasource.url",readerDatabase::getJdbcUrl);
        properties.add("spring.datasource.username",readerDatabase::getUsername);
        properties.add("spring.datasource.password",readerDatabase::getPassword);
        properties.add("spring.flyway.url",readerDatabase::getJdbcUrl);
        properties.add("spring.flyway.user",readerDatabase::getUsername);
        properties.add("spring.flyway.password",readerDatabase::getPassword);
    }
    @AfterAll static void stopDedicatedDatabase(){if(readerDatabase!=null)readerDatabase.stop();}

    @Autowired CustomerOrderChangeHistoryReadService historyReadService;
    @Autowired com.example.ecsite.service.OrderService orderService;
    @Autowired com.example.ecsite.service.orderchange.OrderIncreaseService increases;
    @Autowired org.springframework.context.ApplicationContext applicationContext;

    void customer(long id){
        String username=db.jdbc.queryForObject("select username from users where id=?",String.class,id);
        var principal=new CustomUserDetails(id,username,"unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_USER"));
    }
    TransactionTemplate readTx(DataSource source){
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(5);return tx;
    }
    OrderChangeEvidenceBundle read(RecordingSource source,long order,long owner){
        return readTx(source).execute(s->new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(order,owner));
    }
    @Test void serviceUsesPrincipalAndRealJpaReadOnlyTransaction(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);customer(f.customer());clearInvocations(gateway);
        var result=historyReadService.readForCustomer(f.order());
        assertThat(result.status()).isEqualTo(Status.COMPLETE);assertThat(result.order().customerId()).isEqualTo(f.customer());
        assertThat(result.rows(EvidenceSet.EPOCHS)).hasSize(1);verifyNoInteractions(gateway);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(()->historyReadService.readForCustomer(f.order())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void foreignAndMissingOrdersRunOnlyOwnershipSelect(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        for(long[] request:List.of(new long[]{f.order(),f.customer()+999999},new long[]{Long.MAX_VALUE,f.customer()})){
            var source=new RecordingSource(db.source);
            assertThatThrownBy(()->read(source,request[0],request[1])).isInstanceOf(OrderNotFoundException.class);
            assertThat(source.sql).hasSize(1);assertThat(source.sql.getFirst()).contains("from orders where id=");
        }
    }
    @Test void realReadHasOneConnectionNoWritesAndConstantQueryCount(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var source=new RecordingSource(db.source);
        var first=read(source,f.order(),f.customer());int count=source.sql.size();
        assertThat(first.status()).isEqualTo(Status.COMPLETE);assertThat(source.connections).isEqualTo(1);
        assertThat(source.sql).allSatisfy(sql->assertThat(sql.stripLeading().toLowerCase(Locale.ROOT)).startsWith("select "));
        // Structurally valid, unverified legacy rows for acquisition/query-count testing.
        OrderEditAdmissionServicePostgresTest.tx(()->{
            for(int i=0;i<12;i++)db.jdbc.update("insert into order_content_change_histories(order_id,change_source,changed_by_type,changed_by_account_id,changed_by_username,old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount) values (?,'CUSTOMER','USER',?,'fixture',6000,3000,0,550,545,322,6000,3550)",f.order(),f.customer());
            return null;
        });
        source.sql.clear();var many=read(source,f.order(),f.customer());
        assertThat(many.rows(EvidenceSet.HISTORIES)).hasSize(12);assertThat(source.sql).hasSize(count);
    }
    @Test void retainedAndReauthorizedEvidenceIncludeAllEpochsAndRawSnapshots(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long product=helper().product(f,7000);
        UUID retained=accepted(f,List.of(new Line(f.item(),f.product(),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);mockTransport(f);
        assertThat(service().execute(retained).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        UUID reauth=accepted(f,List.of(new Line(null,product,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);reset(gateway);oldCancelled.set(false);mockTransport(f);
        assertThat(service().execute(reauth).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        customer(f.customer());clearInvocations(gateway);var bundle=historyReadService.readForCustomer(f.order());
        assertThat(bundle.rows(EvidenceSet.HISTORIES)).hasSize(2);assertThat(bundle.rows(EvidenceSet.EPOCHS)).hasSize(2);
        assertThat(bundle.rows(EvidenceSet.RESULTS)).filteredOn(r->"APPLIED".equals(r.value("disposition"))).hasSize(2);
        assertThat(bundle.rows(EvidenceSet.HISTORIES)).filteredOn(r->r.value("evidence_version")==null).hasSize(1);
        assertThat(bundle.rows(EvidenceSet.EPOCHS)).allSatisfy(r->assertThat(r.binaries().get("start_snapshot").bytes()).isNotEmpty());
        assertThat(bundle.diagnostics()).isEmpty();verifyNoInteractions(gateway);
    }
    @Test void admissionAndAttemptsWithoutHistoryAreNotLostByJoin(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        var execution=new OrderEditExecutionCoordinator(db.source);execution.claim(new OrderEditExecution.Claim(id,UUID.randomUUID(),0));
        customer(f.customer());var result=historyReadService.readForCustomer(f.order());
        assertThat(result.rows(EvidenceSet.HISTORIES)).isEmpty();assertThat(result.rows(EvidenceSet.ADMISSIONS)).hasSize(1);
        assertThat(result.rows(EvidenceSet.CONTROLS)).hasSize(1);assertThat(result.rows(EvidenceSet.ATTEMPTS)).hasSize(1);
    }
    @Test void boundsNeverReturnTruncatedUsableEvidence(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        for(var limits:List.of(new OrderChangeEvidenceLimits(1000,10000,1,16000000,2000000),
                new OrderChangeEvidenceLimits(1000,10000,20000,1,2000000),
                new OrderChangeEvidenceLimits(1000,10000,20000,16000000,1))){
            var source=new RecordingSource(db.source);
            var result=readTx(source).execute(s->new JdbcCustomerOrderChangeEvidenceReader(source,limits).readForCustomer(f.order(),f.customer()));
            assertThat(result.status()).isEqualTo(Status.LIMIT_EXCEEDED);assertThat(result.evidence()).isEmpty();assertThat(result.diagnostics()).isNotEmpty();
            assertThat(source.sql).noneSatisfy(sql->assertThat(sql).startsWith("select t.*"));
        }
    }
    @Test void connectionMismatchAndWrongIsolationFailClosed(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var source=new RecordingSource(db.source);
        assertThatThrownBy(()->new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(f.order(),f.customer())).isInstanceOf(IllegalStateException.class);
        assertThat(source.sql).isEmpty();
        var wrong=readTx(source);wrong.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(()->wrong.execute(s->new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(f.order(),f.customer()))).isInstanceOf(IllegalStateException.class);
        assertThat(source.sql).hasSize(1);
        assertThatThrownBy(()->readTx(db.source).execute(s->new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(f.order(),f.customer()))).isInstanceOf(IllegalStateException.class);
    }
    @Test void repeatableReadRetainsPreUpdateVersionAcrossIndependentConnection()throws Exception {
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var source=new RecordingSource(db.source);
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        source.afterOwnership=()->{reached.countDown();try{if(!release.await(4,TimeUnit.SECONDS))throw new IllegalStateException("test barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}};
        var executor=Executors.newSingleThreadExecutor();
        try{
            var reading=executor.submit(()->read(source,f.order(),f.customer()));
            assertThat(reached.await(4,TimeUnit.SECONDS)).isTrue();
            // An independently committed, unverified history row exercises the snapshot boundary.
            db.jdbc.update("insert into order_content_change_histories(order_id,change_source,changed_by_type,changed_by_username,old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount) values (?,'CUSTOMER','USER','fixture',6000,3000,0,550,545,322,6000,3550)",f.order());
            release.countDown();var result=reading.get(8,TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(Status.COMPLETE);assertThat(source.connections).isEqualTo(1);
            assertThat(result.rows(EvidenceSet.HISTORIES)).isEmpty();
            source.afterOwnership=null;assertThat(read(source,f.order(),f.customer()).rows(EvidenceSet.HISTORIES)).hasSize(1);
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void actualLegacyDecreaseAndDeletionUseExistingWriter(){
        var old=new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();long owner=db.jdbc.queryForObject("select user_id from orders where id=?",Long.class,old.order());
        db.jdbc.update("update order_items set quantity=2,subtotal=12000 where order_id=?",old.order());
        db.jdbc.update("insert into order_items(order_id,product_id,product_name,category_id,category_name,price,quantity,subtotal,tax_category_id,tax_category_code,tax_category_name,tax_rate) select order_id,product_id,product_name,category_id,category_name,price,1,6000,tax_category_id,tax_category_code,tax_category_name,tax_rate from order_items where order_id=?",old.order());
        db.jdbc.update("update orders set total_amount=18000,item_subtotal=18000,tax_amount=1636 where id=?",old.order());
        db.jdbc.update("update payments set amount=18000 where id=?",old.payment());
        db.jdbc.update("update payment_transactions set amount=18000 where id=?",old.auth());
        var item=db.jdbc.queryForMap("select id,product_id from order_items where order_id=? order by id limit 1",old.order());
        var form=new com.example.ecsite.form.OrderItemChangeForm();form.setContentRevision(0);
        var line=new com.example.ecsite.form.OrderItemChangeForm.Item();line.setOrderItemId(((Number)item.get("id")).longValue());line.setQuantity(1);form.getItems().add(line);
        long removed=db.jdbc.queryForObject("select max(id) from order_items where order_id=?",Long.class,old.order());
        var deletion=new com.example.ecsite.form.OrderItemChangeForm.Item();deletion.setOrderItemId(removed);deletion.setQuantity(0);form.getItems().add(deletion);
        assertThat(orderService.changeItemsForUser(old.order(),owner,"fixture",form)).isTrue();
        customer(owner);var read=historyReadService.readForCustomer(old.order());
        assertThat(read.rows(EvidenceSet.OPERATIONS)).isEmpty();assertThat(read.rows(EvidenceSet.ITEM_CHANGES)).hasSize(2);
        assertThat(read.rows(EvidenceSet.ITEM_CHANGES)).filteredOn(r->"REMOVED".equals(r.value("change_type"))).singleElement().satisfies(r->assertThat(r.value("new_quantity")).isNull());
    }
    @Test void actualLegacyReauthorizationHasOperationButNoFeature125Admission(){
        var f=com.example.ecsite.service.orderchange.LegacyOrderChangeEvidenceFixture.complete(applicationContext,gateway);
        UUID audit=UUID.randomUUID();
        db.jdbc.update("insert into order_change_closure_attempts(id,operation_id,order_id,new_payment_id,trigger_kind,context_digest,status,started_at,created_at) select ?,id,order_id,new_payment_id,'RECOVERY',decode(repeat('00',32),'hex'),'STARTED',clock_timestamp(),clock_timestamp() from order_change_operations where id=?",audit,f.operationId());
        customer(f.customerId());clearInvocations(gateway);var result=historyReadService.readForCustomer(f.orderId());
        assertThat(result.rows(EvidenceSet.LEGACY_CLOSURE_ATTEMPTS)).singleElement().satisfies(r->assertThat(r.value("id")).isEqualTo(audit.toString()));
        assertThat(result.rows(EvidenceSet.HISTORIES)).hasSize(1);assertThat(result.rows(EvidenceSet.ADMISSIONS)).isEmpty();
        assertThat(result.rows(EvidenceSet.OPERATIONS)).singleElement().satisfies(r->{assertThat(r.value("execution_version")).isNull();assertThat(r.value("state")).isEqualTo("COMPLETED");});
        verifyNoInteractions(gateway);
    }
    @Test void requiresNewSuspendsWritableCallerAndRestoresIt(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);customer(f.customer());
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(applicationContext.getBean(DataSource.class));
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            int before=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
            assertThat(jdbc.queryForObject("select current_setting('transaction_read_only')",String.class)).isEqualTo("off");
            assertThat(historyReadService.readForCustomer(f.order()).status()).isEqualTo(Status.COMPLETE);
            assertThat(jdbc.queryForObject("select pg_backend_pid()",Integer.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select current_setting('transaction_read_only')",String.class)).isEqualTo("off");
        });
    }
    @Test void exactBudgetBoundarySucceedsAndParentOverflowIsExplicit(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var source=new RecordingSource(db.source);
        var baseline=read(source,f.order(),f.customer());
        var exact=new OrderChangeEvidenceLimits(1000,10000,(int)baseline.coverage().rows(),baseline.coverage().rawBytes(),2000000);
        assertThat(readTx(source).execute(tx->new JdbcCustomerOrderChangeEvidenceReader(source,exact).readForCustomer(f.order(),f.customer())).status()).isEqualTo(Status.COMPLETE);
        for(int i=0;i<2;i++)db.jdbc.update("insert into order_content_change_histories(order_id,change_source,changed_by_type,changed_by_username,old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount) values (?,'CUSTOMER','USER','fixture',6000,3000,0,550,545,322,6000,3550)",f.order());
        var small=new OrderChangeEvidenceLimits(1,10000,20000,16000000,2000000);
        var result=readTx(source).execute(tx->new JdbcCustomerOrderChangeEvidenceReader(source,small).readForCustomer(f.order(),f.customer()));
        assertThat(result.status()).isEqualTo(Status.LIMIT_EXCEEDED);assertThat(result.evidence()).isEmpty();
    }
    @Test void databaseReadWriteIsRejectedEvenWithSpringReadOnlyFlag(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var source=new RecordingSource(db.source);
        var write=new TransactionTemplate(new DataSourceTransactionManager(source));write.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(()->write.execute(tx->{
            org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            try{return new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(f.order(),f.customer());}
            finally{org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);}
        })).isInstanceOf(IllegalStateException.class);
        assertThat(source.sql).hasSize(1);
    }
    @Test void failedThreeDsClosureRemainsUnverifiedEvidenceNotAChange(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=helper().product(f,7000);
        UUID receipt=accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);
        mockTransport(f);newStatus="requires_payment_method";
        assertThat(service().execute(receipt).status()).isEqualTo(OrderEditExecutionService.Status.HELD);
        customer(f.customer());
        when(gateway.observeOrderEditFailure(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.Context context=call.getArgument(0);
            com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.LocalFacts facts=call.getArgument(1);
            var own=context.ownership();var fields=new LinkedHashMap<String,Object>();
            fields.put("id",own.flowId());fields.put("amount",own.amount());fields.put("currency","jpy");
            fields.put("capture_method","manual");fields.put("livemode",false);fields.put("status","requires_payment_method");
            fields.put("last_payment_error",Map.of("code","three_d_secure_failed"));fields.put("amount_received",null);
            fields.put("amount_capturable",null);fields.put("next_action",Map.of());fields.put("canceled_at",null);
            fields.put("metadata",Map.of("order_id",Long.toString(own.orderId()),"order_change_operation_id",own.operationId().toString()));
            var now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            return new com.example.ecsite.payment.orderchange.ThreeDsFailureEvidenceVerifier().failure(context,facts,own.connection(),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(fields),
                new com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.Window(now,now),now);
        });
        assertThat(new OrderEditFailureClosureService(db.source,gateway).terminate(receipt)).isEqualTo(OrderEditFailureClosureService.Outcome.CLOSED_UNAPPLIED);
        clearInvocations(gateway);var result=historyReadService.readForCustomer(f.order());
        assertThat(result.rows(EvidenceSet.HISTORIES)).isEmpty();assertThat(result.rows(EvidenceSet.UNAPPLIED)).hasSize(1);
        assertThat(result.rows(EvidenceSet.FAILURE_CONTROLS)).hasSize(1);assertThat(result.rows(EvidenceSet.FAILURE_BUSINESS_CLOSURES)).hasSize(1);
        assertThat(result.rows(EvidenceSet.ADMISSIONS)).singleElement().satisfies(r->assertThat(r.value("execution_state")).isEqualTo("FAILED_UNAPPLIED"));
        verifyNoInteractions(gateway);
    }
    @Test void largeUnverifiedPayloadIsBoundedBeforeTransferAndNeverDecoded(){
        var legacy=new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();
        long owner=db.jdbc.queryForObject("select user_id from orders where id=?",Long.class,legacy.order());
        byte[] observation=new byte[2*1024*1024];observation[observation.length-1]=127;
        OrderEditAdmissionServicePostgresTest.tx(()->{
            var current=new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(legacy.order()).facts().current();
            UUID epoch=UUID.randomUUID();var codec=new OrderContentSnapshotCodec();byte[] snapshot=codec.encode(current);
            var proof=new OrderPaymentEvidence.Epoch(1,epoch,legacy.order(),legacy.payment(),legacy.auth(),6000,0,OrderPaymentEvidence.Origin.INITIAL_AUTH,current,null,null,null);
            db.jdbc.update("""
                insert into order_payment_evidence_epochs(id,order_id,payment_id,authorization_transaction_id,origin_kind,
                    start_content_revision,start_total_amount,authorized_amount,authorization_revision,provider_flow_id,
                    start_snapshot,start_fingerprint,observation_payload,observation_fingerprint,observation_started_at,observation_finished_at,evidence_version,evidence_digest)
                select ?,?,?,?,'INITIAL_AUTH',0,6000,6000,0,provider_payment_id,?,sha256(?),?,sha256(?),clock_timestamp(),clock_timestamp(),1,?
                from payments where id=?
                """,epoch,legacy.order(),legacy.payment(),legacy.auth(),snapshot,snapshot,observation,observation,
                HexFormat.of().parseHex(OrderEvidenceDigests.epoch(proof)),legacy.payment());
            db.jdbc.update("update orders set current_evidence_epoch_id=? where id=?",epoch,legacy.order());return null;
        });
        var source=new RecordingSource(db.source);
        var restricted=new OrderChangeEvidenceLimits(1000,10000,20000,1024*1024,2*1024*1024);
        var limited=readTx(source).execute(tx->new JdbcCustomerOrderChangeEvidenceReader(source,restricted).readForCustomer(legacy.order(),owner));
        assertThat(limited.status()).isEqualTo(Status.LIMIT_EXCEEDED);assertThat(limited.evidence()).isEmpty();
        assertThat(source.sql).noneSatisfy(sql->assertThat(sql).startsWith("select t.*"));
        var full=read(source,legacy.order(),owner);
        assertThat(full.rows(EvidenceSet.EPOCHS)).singleElement().satisfies(row->{
            byte[] raw=row.binaries().get("observation_payload").bytes();assertThat(raw).hasSize(2097152);assertThat(raw[raw.length-1]).isEqualTo((byte)127);
        });
    }
    static final class RecordingSource extends DelegatingDataSource {
        final List<String> sql=Collections.synchronizedList(new ArrayList<>());int connections;Runnable afterOwnership;
        RecordingSource(DataSource target){super(target);}
        @Override public Connection getConnection()throws SQLException {return wrap(super.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(super.getConnection(user,password));}
        Connection wrap(Connection real){connections++;return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            try{
                Object value=method.invoke(real,args);
                if(method.getName().equals("prepareStatement")&&args[0] instanceof String text){
                    sql.add(text);var statement=(PreparedStatement)value;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                        try{Object response=m.invoke(statement,a);if(m.getName().equals("executeQuery")&&text.contains("from orders where id=")&&afterOwnership!=null)afterOwnership.run();return response;}
                        catch(InvocationTargetException e){throw e.getCause();}
                    });
                }
                return value;
            }catch(InvocationTargetException e){throw e.getCause();}
        });}
    }
}
