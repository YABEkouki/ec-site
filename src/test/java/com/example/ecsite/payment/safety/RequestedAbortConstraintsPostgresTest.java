package com.example.ecsite.payment.safety;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Direct SQL verifies V63 independently of Java permissions, only after the existing Guard. */
class RequestedAbortConstraintsPostgresTest {
    OrderChangeFinancialRequestsPostgresTest fixture;
    JdbcTemplate jdbc;
    UUID operation;
    long oldPayment,newPayment,creation,transaction;
    @BeforeAll static void guard(){PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();}
    @BeforeEach void setup(){
        fixture=new OrderChangeFinancialRequestsPostgresTest();fixture.initialize(true);jdbc=fixture.jdbc;
        creation=fixture.save().id();fixture.db.migrate("63");operation=fixture.operation;newPayment=fixture.payment;
        oldPayment=jdbc.queryForObject("select old_payment_id from order_change_operations where id=?",Long.class,operation);
        jdbc.update("update payments set provider_payment_id='pfw_new',status='AUTHORIZED' where id=?",newPayment);
        jdbc.update("update order_change_operations set abort_requested_at=localtimestamp,abort_request_id=?,abort_requester_type='CUSTOMER',abort_reason_code='CUSTOMER_REQUEST',abort_expected_version=version,version=version+1 where id=?",UUID.randomUUID(),operation);
        jdbc.update("update order_change_operations set state='ABORTING',version=version+1 where id=?",operation);
        transaction=insertTransaction(newPayment,"ORDER_CHANGE_NEW_AUTH_ABORT");
    }
    long insertTransaction(long payment,String purpose){return jdbc.queryForObject("""
        insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose,order_change_operation_id,order_change_order_id)
        values (?,'CANCEL','PENDING',5500,0,?,?,?,?) returning id
        """,Long.class,payment,"cancel-"+UUID.randomUUID(),purpose,operation,fixture.order);}
    long insert(String field,String expression){
        // Values are test literals only. Copy the saved connection/bytes; change one business dimension.
        var columns=new LinkedHashMap<String,String>();
        columns.put("payment_id",Long.toString(newPayment));columns.put("transaction_id",Long.toString(transaction));
        columns.put("purpose","'ORDER_CHANGE_NEW_AUTH_ABORT'");columns.put("request_kind","'NEW_ABORT'");columns.put("transaction_type","'CANCEL'");
        columns.put("amount","5500");columns.put("provider_flow_id","'pfw_new'");
        columns.put("idempotency_key","(select idempotency_key from payment_transactions where id="+transaction+")");
        columns.put("api_path","'/v2/payment_flows/pfw_new/cancel'");
        if(field!=null)columns.put(field,expression);
        var all=List.of("operation_id","order_id","payment_id","transaction_id","purpose","request_kind","transaction_type","amount","provider_flow_id","idempotency_key",
            "connection_id","provider_account_ref","mode","api_origin","http_method","api_path","content_type","body_format_version","request_body","fingerprint_version","request_fingerprint","created_at","replay_not_after");
        return jdbc.queryForObject("insert into order_change_financial_requests("+String.join(",",all)+") select "+
            all.stream().map(c->columns.getOrDefault(c,c)).collect(java.util.stream.Collectors.joining(","))+" from order_change_financial_requests where id=? returning id",Long.class,creation);
    }
    void rejects(Runnable action){assertThatThrownBy(action::run).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    @Test void permitsOnlyNewAbortAndCreatesExactlyOneReady(){
        long id=insert(null,null);
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,id)).isEqualTo("READY");
        rejects(()->insert(null,null));
        assertThat(jdbc.queryForObject("select count(*) from order_change_request_dispatches where request_id=?",Integer.class,id)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"CREATE","OLD_CANCEL"})
    void forbidsOtherKinds(String kind){rejects(()->insert("request_kind","'"+kind+"'"));}
    @ParameterizedTest @ValueSource(strings={"payment_id","provider_flow_id","amount","connection_id","provider_account_ref","mode"})
    void rejectsWrongTargetAndConnection(String field){
        String value=switch(field){case "payment_id"->Long.toString(oldPayment);case "amount"->"5501";case "mode"->"'LIVE'";default->"'other'";};
        rejects(()->insert(field,value));
    }
    @ParameterizedTest @ValueSource(strings={"ABORTED","COMPLETED","COMMIT_PENDING","AUTHORIZED"})
    void forbidsTerminalAndOrdinaryProgress(String state){rejects(()->jdbc.update("update order_change_operations set state=?,version=version+1 where id=?",state,operation));}
    @ParameterizedTest @ValueSource(strings={"MANUAL_REVIEW","ABORT_UNKNOWN"})
    void prohibitsRecoveryToPreparation(String state){
        jdbc.update("update order_change_operations set state=?,version=version+1 where id=?",state,operation);
        rejects(()->jdbc.update("update order_change_operations set state='ABORTING',version=version+1 where id=?",operation));
        rejects(()->insert(null,null));
    }
    @Test void rejectsAnExistingOldCancelTransaction(){insertTransaction(oldPayment,"ORDER_CHANGE_OLD_AUTH_CANCEL");rejects(()->insert(null,null));}
    @Test void rejectsOldDecision(){rejects(()->jdbc.update("update order_change_operations set switch_decided_at=localtimestamp,version=version+1 where id=?",operation));}
    @Test void closureShapeAndImmutabilityAreEnforcedWithoutAllowingAnotherRequest(){
        rejects(()->jdbc.update("update order_change_operations set abort_flow_closed_at=clock_timestamp(),version=version+1 where id=?",operation));
        rejects(()->jdbc.update("update order_change_operations set abort_flow_closed_at=clock_timestamp(),abort_flow_close_digest=decode(repeat('ab',31),'hex'),abort_flow_close_version=1,version=version+1 where id=?",operation));
        rejects(()->jdbc.update("update order_change_operations set abort_flow_closed_at=clock_timestamp(),abort_flow_close_digest=decode(repeat('ab',32),'hex'),abort_flow_close_version=2,version=version+1 where id=?",operation));
        jdbc.update("update order_change_operations set abort_flow_closed_at=clock_timestamp(),abort_flow_close_digest=decode(repeat('ab',32),'hex'),abort_flow_close_version=1,version=version+1 where id=?",operation);
        rejects(()->insert(null,null));
        rejects(()->jdbc.update("update order_change_operations set abort_flow_close_digest=decode(repeat('cd',32),'hex'),version=version+1 where id=?",operation));
        rejects(()->jdbc.update("update order_change_operations set abort_flow_closed_at=null,abort_flow_close_digest=null,abort_flow_close_version=null,version=version+1 where id=?",operation));
    }
}
