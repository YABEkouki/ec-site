package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.ecsite.payment.PaymentGateway;
import static org.assertj.core.api.Assertions.*;

/** V78 storage only. Uses the existing mocked reauthorization fixture, never a closure/stock writer. */
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
@org.springframework.test.annotation.DirtiesContext
class OrderEditFailureClosureFoundationPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AutowireCapableBeanFactory beans;
    @MockitoBean PaymentGateway gateway;
    OrderEditCardPostgresTest fixture;
    UUID control;
    TransactionTemplate tx;
    @BeforeEach void basis(TestInfo info) {
        fixture=new OrderEditCardPostgresTest();beans.autowireBean(fixture);fixture.gateway=gateway;fixture.fixture(info);
        tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        control=UUID.randomUUID();
    }
    void locked(Runnable action) { tx.execute(s->{jdbc.queryForObject("select f125_reauth_lock(?,?)",Object.class,fixture.f.order(),fixture.operation);action.run();return null;}); }
    void intent() {locked(()->jdbc.update("""
        insert into order_edit_failure_closure_controls(id,admission_id,operation_id,order_id,generation_id,
          old_payment_id,new_payment_id,authorization_id,create_request_id,flow_id,requester_type,actor_name,
          reason,execution_version,history_digest)
        select ?,r.id,op.id,r.order_id,r.db_generation_id,r.base_payment_id,op.new_payment_id,q.transaction_id,
          q.id,p.provider_payment_id,'CUSTOMER',r.actor_username,'THREE_DS_FAILED',x.version,f125_failure_history(r.id)
        from order_edit_admissions r join order_change_operations op on op.order_id=r.order_id and op.submission_key=r.submission_key
        join payments p on p.id=op.new_payment_id join order_change_financial_requests q on q.operation_id=op.id and q.request_kind='CREATE'
        join order_edit_execution_controls x on x.admission_id=r.id where r.id=?
        """,control,fixture.receipt));}
    String document(String kind) {return jdbc.queryForObject("""
        select jsonb_build_object('flow_id',c.flow_id,'amount',q.amount,'currency','jpy','capture_method','manual',
         'livemode',q.mode='LIVE','metadata',jsonb_build_object('order_id',c.order_id::text,'order_change_operation_id',c.operation_id::text),
         'connection_id',q.connection_id,'provider_account_ref',q.provider_account_ref,'api_origin',q.api_origin,
         'status',case when ?='THREE_DS_FAILURE' then 'requires_payment_method' else 'canceled' end,
         'last_error_code','three_d_secure_failed','amount_received',null,'amount_capturable',null,'next_action','{}'::jsonb,
         'canceled_at',case when ?='THREE_DS_FAILURE' then null else to_jsonb(clock_timestamp()) end)::text
        from order_edit_failure_closure_controls c join order_change_financial_requests q on q.id=c.create_request_id where c.id=?
        """,String.class,kind,kind,control);}
    UUID observe(String kind,String document) {
        UUID id=UUID.randomUUID();locked(()->jdbc.update("""
        insert into order_edit_failure_closure_observations(id,control_id,control_version,kind,document,
          observed_started_at,observed_finished_at,history_digest,canonical,digest)
        select ?,id,version,?,?::jsonb,clock_timestamp(),clock_timestamp(),f125_failure_history(admission_id),null,null
        from order_edit_failure_closure_controls where id=?
        """,id,kind,document,control));return id;
    }
    void advance(long version,String state) {jdbc.queryForObject("select f125_failure_advance(?,?,?)",Object.class,control,version,state);}
    UUID terminal() {intent();observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));advance(0,"FAILURE_OBSERVED");var id=observe("FLOW_TERMINAL",document("FLOW_TERMINAL"));advance(1,"TERMINAL_OBSERVED");return id;}
    void rejected(Runnable action) {assertThatThrownBy(action::run).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    String business() {return jdbc.queryForObject("select encode(f125_failure_history(?),'hex')",String.class,fixture.receipt);}

    @Test void migrationInstallsStorageWithoutActivatingAnyBusinessWriter() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='78' and success",Integer.class)).isEqualTo(1);
        String before=business();terminal();assertThat(business()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select state from order_change_operations where id=?",String.class,fixture.operation)).isEqualTo("AUTHORIZING");
        assertThat(jdbc.queryForObject("select count(*) from order_edit_inventory_plans where admission_id=? and reservation_state='HELD'",Integer.class,fixture.receipt)).isEqualTo(1);
    }
    @Test void controlIsUniqueImmutableAndCasProtected() {
        intent();UUID first=control;control=UUID.randomUUID();rejected(this::intent);control=first;
        rejected(()->advance(0,"TERMINAL_OBSERVED"));
        observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));advance(0,"FAILURE_OBSERVED");
        rejected(()->advance(0,"FAILURE_OBSERVED"));rejected(()->advance(1,"REQUESTED"));
        rejected(()->jdbc.update("delete from order_edit_failure_closure_controls where id=?",control));
        rejected(()->jdbc.update("update order_edit_failure_closure_controls set flow_id='other',version=version+1 where id=?",control));
    }
    @Test void ownershipGenerationAndHistoryMismatchAreRejected() {
        intent();
        rejected(()->jdbc.update("update order_edit_failure_closure_controls set old_payment_id=new_payment_id,version=version+1 where id=?",control));
        rejected(()->jdbc.update("update order_edit_failure_closure_controls set generation_id=gen_random_uuid(),version=version+1 where id=?",control));
        rejected(()->locked(()->jdbc.update("""
         insert into order_edit_failure_closure_observations(id,control_id,control_version,kind,document,observed_started_at,observed_finished_at,history_digest,canonical,digest)
         values(gen_random_uuid(),?,0,'THREE_DS_FAILURE',?::jsonb,clock_timestamp(),clock_timestamp(),sha256('wrong'::bytea),null,null)
         """,control,document("THREE_DS_FAILURE"))));
    }
    @ParameterizedTest @ValueSource(strings={"0","1","\"0\"","0.5","true","{}","[]"})
    void nonNullMoneyIsNotCoerced(String value) {
        intent();String d=document("THREE_DS_FAILURE");
        String first=jdbc.queryForObject("select jsonb_set(?::jsonb,'{amount_received}',?::jsonb)::text",String.class,d,value);
        rejected(()->observe("THREE_DS_FAILURE",first));
        String invalid=jdbc.queryForObject("select jsonb_set(?::jsonb,'{amount_capturable}',?::jsonb)::text",String.class,d,value);
        String second=invalid;rejected(()->observe("THREE_DS_FAILURE",second));
    }
    @Test void missingMoneyAndUnknownFieldsAreRejected() {
        intent();String d=document("THREE_DS_FAILURE");
        for(String k:List.of("amount_received","amount_capturable","status","metadata")) {
            String invalid=jdbc.queryForObject("select (?::jsonb-?)::text",String.class,d,k);rejected(()->observe("THREE_DS_FAILURE",invalid));
        }
        String secretField=jdbc.queryForObject("select (?::jsonb||'{\"client_secret\":\"dummy\"}'::jsonb)::text",String.class,d);
        rejected(()->observe("THREE_DS_FAILURE",secretField));
    }
    @Test void digestVersionAndAppendOnlyRulesAreEnforced() {
        intent();UUID id=observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));
        rejected(()->jdbc.update("update order_edit_failure_closure_observations set digest=sha256('x'::bytea) where id=?",id));
        rejected(()->jdbc.update("delete from order_edit_failure_closure_observations where id=?",id));
        rejected(()->jdbc.execute("truncate order_edit_failure_closure_observations"));
        rejected(()->jdbc.update("update order_edit_failure_closure_controls set evidence_version=2 where id=?",control));
        assertThat(jdbc.queryForObject("select digest=sha256(canonical) from order_edit_failure_closure_observations where id=?",Boolean.class,id)).isTrue();
    }
    @Test void unknownResolutionIsAppendOnlyAndNotExecutionPermission() {
        UUID proof=terminal();String before=jdbc.queryForObject("select jsonb_agg(to_jsonb(r) order by r.id)::text from order_edit_execution_attempt_results r where admission_id=?",String.class,fixture.receipt);
        UUID id=UUID.randomUUID();locked(()->jdbc.update("""
          insert into order_edit_failure_unknown_resolutions(id,control_id,unknown_result_id,terminal_observation_id,control_version,disposition,document,canonical,digest)
          select ?,?,r.id,?,2,'TERMINATED_UNAPPLIED',null,null,null from order_edit_execution_attempt_results r
          where r.admission_id=? and r.disposition='UNKNOWN'
          """,id,control,proof,fixture.receipt));
        assertThat(jdbc.queryForObject("select jsonb_agg(to_jsonb(r) order by r.id)::text from order_edit_execution_attempt_results r where admission_id=?",String.class,fixture.receipt)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_failure_unknown_resolutions where id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition not in ('UNKNOWN','REVIEW')",Integer.class,fixture.receipt)).isZero();
        rejected(()->jdbc.update("delete from order_edit_failure_unknown_resolutions where id=?",id));
        rejected(()->jdbc.update("update order_edit_execution_attempt_results set reason_code='REWRITE' where admission_id=?",fixture.receipt));
    }
    @Test void rollbackLeavesNeitherIntentNorObservation() {
        assertThatThrownBy(()->tx.execute(s->{intent();observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_failure_closure_controls where id=?",Integer.class,control)).isZero();
    }
    @Test void invalidInsertDigestAndControlVersionAreRejected() {
        intent();String d=document("THREE_DS_FAILURE");
        for(String fragment:List.of("sha256('bad'::bytea),sha256('bad'::bytea),0,1", "null,null,99,1", "null,null,0,2")) {
            rejected(()->locked(()->jdbc.update("""
              insert into order_edit_failure_closure_observations(id,control_id,kind,document,observed_started_at,observed_finished_at,
                history_digest,canonical,digest,control_version,evidence_version)
              select gen_random_uuid(),id,'THREE_DS_FAILURE',?::jsonb,clock_timestamp(),clock_timestamp(),
                f125_failure_history(admission_id),"""+fragment+" from order_edit_failure_closure_controls where id=?",d,control)));
        }
    }
    @Test void foreignOrderAndCancelRequestAreRejected() {
        intent();
        rejected(()->locked(()->jdbc.update("""
          insert into order_edit_failure_closure_controls(id,admission_id,operation_id,order_id,generation_id,
            old_payment_id,new_payment_id,authorization_id,create_request_id,flow_id,requester_type,actor_name,
            reason,execution_version,history_digest)
          select gen_random_uuid(),admission_id,operation_id,order_id+100000,generation_id,old_payment_id,new_payment_id,
            authorization_id,create_request_id,flow_id,requester_type,actor_name,reason,execution_version,history_digest
          from order_edit_failure_closure_controls where id=?
          """,control)));
        observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));
        Long wrong=jdbc.queryForObject("select create_request_id from order_edit_failure_closure_controls where id=?",Long.class,control);
        rejected(()->jdbc.queryForObject("select f125_failure_advance(?,0,'FAILURE_OBSERVED',?)",Object.class,control,wrong));
    }
    @Test void terminalRequiresFailurePhaseAndCorrectTimestamp() {
        intent();rejected(()->observe("FLOW_TERMINAL",document("FLOW_TERMINAL")));
        observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));advance(0,"FAILURE_OBSERVED");
        String invalid=jdbc.queryForObject("select jsonb_set(?::jsonb,'{canceled_at}','null')::text",String.class,document("FLOW_TERMINAL"));
        rejected(()->observe("FLOW_TERMINAL",invalid));
    }
    @Test void concurrentCasHasOneWinner() throws Exception {
        intent();observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> task=()->{start.await();try{advance(0,"FAILURE_OBSERVED");return true;}catch(org.springframework.dao.DataAccessException conflict){return false;}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select version from order_edit_failure_closure_controls where id=?",Long.class,control)).isEqualTo(1L);
    }

    @Test void historyDigestIsIndependentOfSessionTimezone() {
        String utc=tx.execute(s->{jdbc.execute("set local timezone='UTC'");return business();});
        String tokyo=tx.execute(s->{jdbc.execute("set local timezone='Asia/Tokyo'");return business();});
        assertThat(tokyo).isEqualTo(utc);
    }

    @ParameterizedTest @ValueSource(strings={"now","yesterday","2026-11-10","infinity","2026-99-99T00:00:00Z"})
    void terminalTimestampMustBeAbsoluteAndValid(String timestamp) {
        intent();observe("THREE_DS_FAILURE",document("THREE_DS_FAILURE"));advance(0,"FAILURE_OBSERVED");
        String invalid=jdbc.queryForObject("select jsonb_set(?::jsonb,'{canceled_at}',to_jsonb(?::text))::text",String.class,document("FLOW_TERMINAL"),timestamp);
        rejected(()->observe("FLOW_TERMINAL",invalid));
    }

}
