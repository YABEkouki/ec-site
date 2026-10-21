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
    @BeforeEach void setup(){
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
    @Test void expandsAndReusesExactRequest(){
        var saved=save();var again=save();assertThat(again.id()).isEqualTo(saved.id());
        assertThat(again.httpRequest().body()).isEqualTo(request().body());
        assertThat(jdbc.queryForObject("select purpose from payment_transactions where id=?",String.class,tx)).isEqualTo("ORDER_CHANGE_AUTHORIZATION");
    }
    @Test void immutableEvenForEmptyOrNoOpMutations(){
        save();for(String sql:List.of("update order_change_financial_requests set mode=mode","delete from order_change_financial_requests","truncate order_change_financial_requests"))
            db.rejects("55000",()->jdbc.execute(sql));
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
