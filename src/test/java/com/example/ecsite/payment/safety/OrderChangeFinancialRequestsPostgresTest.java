package com.example.ecsite.payment.safety;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.ecsite.payment.orderchange.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;

/** The launcher Guard verifies Docker provenance before this fixture connects. */
class OrderChangeFinancialRequestsPostgresTest {
    PaymentSafetyExpandPostgresTest db;
    JdbcTemplate jdbc;
    UUID operation;
    long order,payment,tx;
    Map<String,List<String>> before;
    final CaptureConnectionIdentity identity=new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp");
    @BeforeAll static void guard(){PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();}
    @BeforeEach void setup(){initialize(true);}
    void initialize(boolean tracking){
        db=new PaymentSafetyExpandPostgresTest();db.isolatedSchema();db.migrate("57");jdbc=db.jdbc;
        order=db.order();payment=db.payment(order);operation=UUID.randomUUID();
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
              old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
              shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
              accepted_at,deadline_at,grace_until,state,created_at,updated_at)
            select ?,id,user_id,?,repeat('a',64),0,?,5500,5500,5500,0,0,'shipping',0,1,'standard','standard',10,0,
              localtimestamp,localtimestamp+interval '1 hour',localtimestamp+interval '70 minutes','RESERVED',localtimestamp,localtimestamp
            from orders where id=?
            """,operation,UUID.randomUUID(),payment,order);
        var legacy=db.legacyFixture();
        jdbc.update("update orders set active_payment_id=? where id=?",legacy[1],legacy[0]);
        jdbc.update("update payment_transactions set purpose='SHIPMENT_CAPTURE' where id=?",legacy[2]);
        before=legacyRows(db);
        db.migrate("58");
        assertThat(legacyRows(db)).isEqualTo(before);
        payment=db.payment(order);
        jdbc.update("update payments set provider_payment_id=null,status='PENDING' where id=?",payment);
        jdbc.update("update order_change_operations set new_payment_id=? where id=?",payment,operation);
        tx=jdbc.queryForObject("""
            insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose,order_change_operation_id,order_change_order_id)
            values (?,'AUTHORIZE','PENDING',5500,0,?,'ORDER_CHANGE_AUTHORIZATION',?,?) returning id
            """,Long.class,payment,"key-"+operation,operation,order);
        if(tracking)db.migrate("61");
    }
    OrderChangeHttpRequest request(){return OrderChangeHttpRequest.create(identity,operation,order,5500,"key-"+operation);}
    static Map<String,List<String>> legacyRows(PaymentSafetyExpandPostgresTest db){
        var rows=new LinkedHashMap<String,List<String>>();
        for(String table:List.of("orders","payments","payment_transactions","order_status_histories","capture_requests","order_change_operations"))
            rows.put(table,db.jdbc.queryForList("select (to_jsonb(t)-'new_payment_id'-'order_change_order_id'-'order_change_operation_id')::text from "+table+" t order by id",String.class));
        return rows;
    }
    StoredOrderChangeRequest save(){return new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->
        new OrderChangeFinancialRequestStore(jdbc).save(payment,tx,request()));}
    @Test void upgradeTo64PreservesFinancialRowsAndEnablesLimitedGuards(){
        save();db.migrate("63");
        var tables=List.of("orders","payments","payment_transactions","order_change_operations","order_change_financial_requests","order_change_request_dispatches");
        var before=new TreeMap<String,List<String>>();
        for(var table:tables)before.put(table,jdbc.queryForList("select to_jsonb(t)::text from "+table+" t order by to_jsonb(t)::text",String.class));
        db.migrate("64");
        for(var table:tables)assertThat(jdbc.queryForList("select to_jsonb(t)::text from "+table+" t order by to_jsonb(t)::text",String.class)).isEqualTo(before.get(table));
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid in ('payments'::regclass,'payment_transactions'::regclass,'order_change_financial_requests'::regclass,'order_change_request_dispatches'::regclass) and tgname in ('closed_new_payment_guard','closed_new_transaction_guard','zz_closed_new_transaction_guard','zz_closed_new_request_guard','closed_new_dispatch_guard','zz_closed_new_dispatch_guard') and tgenabled='O'",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select i.indisvalid and i.indisready from pg_index i where i.indexrelid='idx_change_closed_new_payment'::regclass",Boolean.class)).isTrue();
    }
    @Test void upgradeTo63PreservesExistingRowsAndDispatch(){
        var saved=save();db.migrate("62");
        var row=jdbc.queryForObject("select to_jsonb(o)::text from order_change_operations o where id=?",String.class,operation);
        var dispatch=jdbc.queryForMap("select * from order_change_request_dispatches where request_id=?",saved.id());
        db.migrate("63");
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success",Integer.class)).isEqualTo(63);
        assertThat(jdbc.queryForObject("select (to_jsonb(o)-'abort_flow_closed_at'-'abort_flow_close_digest'-'abort_flow_close_version')::text from order_change_operations o where id=?",String.class,operation))
            .isEqualTo(row);
        assertThat(jdbc.queryForMap("select * from order_change_request_dispatches where request_id=?",saved.id())).isEqualTo(dispatch);
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is null and abort_flow_close_digest is null and abort_flow_close_version is null from order_change_operations where id=?",Boolean.class,operation)).isTrue();
    }
    @Test void upgradeTo62DoesNotBackfillAbortIntentOrChangeDispatch(){
        var saved=save();
        var before=jdbc.queryForObject("select to_jsonb(o)::text from order_change_operations o where id=?",String.class,operation);
        var dispatch=jdbc.queryForMap("select * from order_change_request_dispatches where request_id=?",saved.id());
        db.migrate("62");
        assertThat(jdbc.queryForObject("select (to_jsonb(o)-'abort_requested_at'-'abort_request_id'-'abort_requester_type'-'abort_reason_code'-'abort_expected_version')::text from order_change_operations o where id=?",String.class,operation)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select abort_requested_at is null and abort_request_id is null and abort_requester_type is null and abort_reason_code is null and abort_expected_version is null from order_change_operations where id=?",Boolean.class,operation)).isTrue();
        assertThat(jdbc.queryForMap("select * from order_change_request_dispatches where request_id=?",saved.id())).isEqualTo(dispatch);
    }
    @Test void expandsAndReusesExactRequest(){
        var saved=save();var again=save();assertThat(again.id()).isEqualTo(saved.id());
        assertThat(again.httpRequest().body()).isEqualTo(request().body());
        assertThat(jdbc.queryForObject("select purpose from payment_transactions where id=?",String.class,tx)).isEqualTo("ORDER_CHANGE_AUTHORIZATION");
    }
    @Test void immutableEvenForEmptyOrNoOpMutations(){
        save();for(String sql:List.of("update order_change_financial_requests set mode=mode","delete from order_change_financial_requests","truncate order_change_financial_requests cascade"))
            db.rejects("55000",()->jdbc.execute(sql));
        // The new child FK rejects non-CASCADE TRUNCATE before row/statement triggers run.
        db.rejects("0A000",()->jdbc.execute("truncate order_change_financial_requests"));
    }
    @Test void futurePurposeRequiresOperationAndType(){
        db.rejects("23514",()->jdbc.update("update payment_transactions set order_change_operation_id=null where id=?",tx));
        db.rejects("23514",()->jdbc.update("update payment_transactions set transaction_type='CAPTURE' where id=?",tx));
    }
    @Test void wrongOrderMembershipIsRejected(){
        long other=db.payment(db.order());
        db.rejects("23503",()->jdbc.update("update payment_transactions set payment_id=? where id=?",other,tx));
    }
    @Test void persistedRequestPinsTransactionKeyAndAmount(){
        save();db.rejects("23503",()->jdbc.update("update payment_transactions set idempotency_key='other' where id=?",tx));
        db.rejects("23503",()->jdbc.update("update payment_transactions set amount=5501 where id=?",tx));
    }
    @Test void rollbackLeavesNoRequest(){
        new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->{
            new OrderChangeFinancialRequestStore(jdbc).save(payment,tx,request());s.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_change_request_dispatches",Integer.class)).isZero();
    }
    @Test void directSaveRequiresExistingTransaction(){
        assertThatThrownBy(()->new OrderChangeFinancialRequestStore(jdbc).save(payment,tx,request())).isInstanceOf(IllegalStateException.class);
    }
    @Test void senderLoadsOnlyCommittedRequestAndPostsOutsideTransaction(){
        var saved=save();var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.payjp.PayJpPaymentGateway.class);
        org.mockito.Mockito.when(gateway.sendOrderChangeRequest(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var persisted=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request();
            assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where id=?",Integer.class,persisted.id())).isEqualTo(1);
            assertThat(persisted.httpRequest().body()).isEqualTo(saved.httpRequest().body());return null;
        });
        var sender=new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source));
        sender.send(saved.id());org.mockito.Mockito.verify(gateway).sendOrderChangeRequest(org.mockito.ArgumentMatchers.any());
    }
    @Test void aNewSenderCannotPostAnAlreadyStartedRequest() {
        var saved=save();
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        var manager=new DataSourceTransactionManager(db.source);
        new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,manager).send(saved.id());
        assertThatThrownBy(()->new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,manager).send(saved.id()))
            .isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).sendOrderChangeRequest(org.mockito.ArgumentMatchers.any());
    }
    @Test void legacyRequestIsNotBackfilledOrPosted(){
        initialize(false);var saved=save();db.migrate("61");
        assertThat(save().id()).isEqualTo(saved.id()); // Reusing legacy bytes never repairs eligibility.
        assertThat(jdbc.queryForObject("select count(*) from order_change_request_dispatches",Integer.class)).isZero();
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        assertThatThrownBy(()->new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source)).send(saved.id()))
            .isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
        db.rejects("55000",()->jdbc.update("insert into order_change_request_dispatches(request_id,state) values (?,'READY')",saved.id()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=com.example.ecsite.payment.PaymentGatewayErrorCategory.class,
        names={"TRANSPORT_RESULT_UNKNOWN","SERVER_RESULT_UNKNOWN","IDEMPOTENCY_WAIT_TIMEOUT"})
    void transportFailureConsumesPermitWithoutChangingBusinessData(com.example.ecsite.payment.PaymentGatewayErrorCategory category){
        var saved=save();var unchanged=legacyRows(db);
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        org.mockito.Mockito.when(gateway.sendOrderChangeRequest(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new com.example.ecsite.payment.PaymentGatewayException(category,null,null,true));
        var sender=new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source));
        assertThatThrownBy(()->sender.send(saved.id())).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
        assertThatThrownBy(()->sender.send(saved.id())).isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).sendOrderChangeRequest(org.mockito.ArgumentMatchers.any());
        assertThat(dispatchState(saved.id())).isEqualTo("STARTED");assertThat(legacyRows(db)).isEqualTo(unchanged);
    }
    @Test void startRollbackDoesNotConsumePermit(){
        var saved=save();var store=new OrderChangeFinancialRequestStore(jdbc);
        new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->{store.findForDispatch(saved.id());s.setRollbackOnly();return null;});
        assertThat(dispatchState(saved.id())).isEqualTo("READY");
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        new OrderChangeRequestSender(store,gateway,new DataSourceTransactionManager(db.source)).send(saved.id());
        assertThat(dispatchState(saved.id())).isEqualTo("STARTED");
    }
    String dispatchState(long id){return jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,id);}
    @Test void crashAfterStartCommitLeavesNoRetryPermit(){
        var saved=save();var store=new OrderChangeFinancialRequestStore(jdbc);
        // Process dies after start commit but before entering HTTP transport.
        new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->store.findForDispatch(saved.id()));
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        assertThatThrownBy(()->new OrderChangeRequestSender(store,gateway,new DataSourceTransactionManager(db.source)).send(saved.id()))
            .isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
        db.rejects("55000",()->jdbc.update("update order_change_request_dispatches set state='READY',started_at=null where request_id=?",saved.id()));
        db.rejects("55000",()->jdbc.execute("delete from order_change_request_dispatches"));
        db.rejects("55000",()->jdbc.execute("truncate order_change_request_dispatches"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void lostCommitResponseNeverEntersTransport(boolean committed){
        var saved=save();var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        var manager=new DataSourceTransactionManager(db.source){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                if(committed)super.doCommit(status);else super.doRollback(status);
                throw new org.springframework.transaction.TransactionSystemException("Synthetic lost commit response");
            }
        };
        assertThatThrownBy(()->new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,manager).send(saved.id()))
            .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
        assertThat(dispatchState(saved.id())).isEqualTo(committed?"STARTED":"READY");
        if(committed)assertThatThrownBy(()->new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source)).send(saved.id()))
            .isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
    }
    @Test void concurrentSendCanEnterTransportOnlyOnce() throws Exception{
        var saved=save();var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        var entered=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(gateway.sendOrderChangeRequest(org.mockito.ArgumentMatchers.any())).thenAnswer(c->{
            assertThat(dispatchState(saved.id())).isEqualTo("STARTED");entered.countDown();
            assertThat(finish.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return null;
        });
        var sender=new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var first=pool.submit(()->sender.send(saved.id()));
            assertThat(entered.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->sender.send(saved.id())).isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
            finish.countDown();first.get(15,java.util.concurrent.TimeUnit.SECONDS);
            org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).sendOrderChangeRequest(org.mockito.ArgumentMatchers.any());
        }finally{finish.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @Test void uncommittedOrRolledBackRequestCannotReachGateway(){
        final long[] id={0};
        new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->{
            id[0]=new OrderChangeFinancialRequestStore(jdbc).save(payment,tx,request()).id();s.setRollbackOnly();return null;});
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.payjp.PayJpPaymentGateway.class);
        var sender=new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source));
        assertThatThrownBy(()->sender.send(id[0])).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }
    @Test void repeatedPurposeAndKeyAreRejectedByDatabase(){
        db.rejects("23505",()->jdbc.update("""
            insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose,order_change_operation_id,order_change_order_id)
            values (?,'AUTHORIZE','PENDING',5500,0,'different-key','ORDER_CHANGE_AUTHORIZATION',?,?)
            """,payment,operation,order));
        db.rejects("23505",()->jdbc.update("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key) values (?,'AUTHORIZE','PENDING',5500,0,?)",payment,"key-"+operation));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void oldCancelAndNewAbortHaveSeparatePersistedPurposes(boolean abort){
        long old=((Number)jdbc.queryForMap("select old_payment_id from order_change_operations where id=?",operation).get("old_payment_id")).longValue();
        {
            long target=abort?payment:old;
            if(abort){jdbc.update("update payments set provider_payment_id='pfw_new' where id=?",target);
                jdbc.update("update order_change_operations set state='ABORTING' where id=?",operation);}
            else jdbc.update("update order_change_operations set switch_decided_at=localtimestamp,state='OLD_CANCEL_PENDING' where id=?",operation);
            String flow=jdbc.queryForObject("select provider_payment_id from payments where id=?",String.class,target);
            var req=OrderChangeHttpRequest.cancel(identity,operation,order,5500,"cancel-"+abort+operation,flow,abort);
            long cancel=jdbc.queryForObject("""
                insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose,order_change_operation_id,order_change_order_id)
                values (?,'CANCEL','PENDING',5500,0,?,?,?,?) returning id
                """,Long.class,target,req.idempotencyKey(),req.purpose().name(),operation,order);
            var saved=new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->new OrderChangeFinancialRequestStore(jdbc).save(target,cancel,req));
            assertThat(saved.httpRequest().purpose()).isEqualTo(req.purpose());
            var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
            var sender=new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source));
            sender.send(saved.id());assertThat(dispatchState(saved.id())).isEqualTo("STARTED");
            assertThatThrownBy(()->sender.send(saved.id())).isInstanceOf(OrderChangeFinancialRequestStore.DispatchUnavailable.class);
            org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).sendOrderChangeRequest(org.mockito.ArgumentMatchers.any());
            if(abort)db.rejects("23514",()->jdbc.update("update order_change_operations set switch_decided_at=localtimestamp,state='OLD_CANCEL_PENDING' where id=?",operation));
        }
    }
    @Test void lateDdlFailureRollsBackWholeMigration(){
        var prior=new PaymentSafetyExpandPostgresTest();prior.isolatedSchema();prior.migrate("57");prior.legacyFixture();
        var unchanged=legacyRows(prior);prior.jdbc.execute("create index idx_change_requests_payment on payments(id)");
        assertThatThrownBy(()->prior.migrate("58")).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(legacyRows(prior)).isEqualTo(unchanged);
        assertThat(prior.jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=? and column_name='new_payment_id'",Integer.class,prior.schema)).isZero();
        assertThat(prior.jdbc.queryForObject("select count(*) from flyway_schema_history where version='58'",Integer.class)).isZero();
    }
    @Test void newPaymentCannotAliasOldPayment(){
        db.rejects("23514",()->jdbc.update("update order_change_operations set new_payment_id=old_payment_id where id=?",operation));
    }
    @Test void abortedOperationCannotSendStoredCreate(){
        var saved=save();jdbc.update("update order_change_operations set state='ABORTED' where id=?",operation);
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.payjp.PayJpPaymentGateway.class);
        var sender=new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source));
        assertThatThrownBy(()->sender.send(saved.id())).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SUCCEEDED","FAILED"})
    void settledTransactionIsObservationOnly(String status){
        var saved=save();jdbc.update("update payment_transactions set status=? where id=?",status,tx);
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.payjp.PayJpPaymentGateway.class);
        var sender=new OrderChangeRequestSender(new OrderChangeFinancialRequestStore(jdbc),gateway,new DataSourceTransactionManager(db.source));
        assertThatThrownBy(()->sender.send(saved.id())).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }
}
