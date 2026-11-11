package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.ecsite.payment.PaymentGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Intent persistence is distinct from provider cancellation and business closure. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext
class OrderEditFailureIntentPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AutowireCapableBeanFactory beans;
    @MockitoBean PaymentGateway gateway;
    OrderEditFailureClosureFoundationPostgresTest basis;
    long executionVersion,operationVersion;
    UUID generation;
    byte[] history;
    String actorName;
    @BeforeEach void fixture(TestInfo info) {
        basis=new OrderEditFailureClosureFoundationPostgresTest();beans.autowireBean(basis);
        basis.gateway=gateway;basis.basis(info);
        executionVersion=jdbc.queryForObject("select version from order_edit_execution_controls where admission_id=?",Long.class,basis.fixture.receipt);
        operationVersion=jdbc.queryForObject("select version from order_change_operations where id=?",Long.class,basis.fixture.operation);
        generation=jdbc.queryForObject("select db_generation_id from order_edit_admissions where id=?",UUID.class,basis.fixture.receipt);
        history=jdbc.queryForObject("select f125_failure_history(?)",byte[].class,basis.fixture.receipt);
        actorName=jdbc.queryForObject("select actor_username from order_edit_admissions where id=?",String.class,basis.fixture.receipt);
    }
    UUID request(UUID id,long execution,long operation,UUID gen,byte[] digest) {
        return jdbc.queryForObject("select f125_failure_request_intent(?,?,?,?,?,?,?,?)",UUID.class,
            id,basis.fixture.receipt,execution,operation,gen,digest,"CUSTOMER",actorName);
    }
    UUID request(){return request(basis.control,executionVersion,operationVersion,generation,history);}
    @Test void installsIntentCompareAndSetEntryPoint() {
        assertThat(jdbc.queryForObject("select to_regprocedure('f125_failure_request_intent(uuid,uuid,bigint,bigint,uuid,bytea,text,text)') is not null",Boolean.class)).isTrue();
    }
    @Test void recordsImmutableIntentWithoutChangingBusinessAndReplaysSameCommand() {
        String before=basis.business();assertThat(request()).isEqualTo(basis.control);
        assertThat(request()).isEqualTo(basis.control);
        assertThat(basis.business()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select intent_digest=sha256(f125_failure_intent_bytes(c)) from order_edit_failure_closure_controls c where id=?",Boolean.class,basis.control)).isTrue();
        basis.rejected(()->request(UUID.randomUUID(),executionVersion,operationVersion,generation,history));
    }
    @Test void rejectsStaleExecutionOperationGenerationAndHistory() {
        basis.rejected(()->request(basis.control,executionVersion+1,operationVersion,generation,history));
        basis.rejected(()->request(basis.control,executionVersion,operationVersion+1,generation,history));
        basis.rejected(()->request(basis.control,executionVersion,operationVersion,UUID.randomUUID(),history));
        basis.rejected(()->request(basis.control,executionVersion,operationVersion,generation,new byte[32]));
    }
    @Test void rejectsWrongOwner() {
        basis.rejected(()->jdbc.queryForObject("select f125_failure_request_intent(?,?,?,?,?,?,?,?)",UUID.class,
            basis.control,basis.fixture.receipt,executionVersion,operationVersion,generation,history,"CUSTOMER","other"));
    }
    @Test void normalFinancialAndExecutionWritesAreFenced() {
        request();
        basis.rejected(()->basis.locked(()->jdbc.update("update order_change_operations set version=version+1 where id=?",basis.fixture.operation)));
        basis.rejected(()->basis.locked(()->jdbc.update("update payments set status='AUTHORIZED' where id=(select new_payment_id from order_change_operations where id=?)",basis.fixture.operation)));
        basis.rejected(()->basis.locked(()->jdbc.update("update payment_transactions set status='SUCCEEDED' where order_change_operation_id=?",basis.fixture.operation)));
        basis.rejected(()->basis.locked(()->jdbc.update("update order_edit_execution_controls set version=version+1 where admission_id=?",basis.fixture.receipt)));
        basis.rejected(()->basis.locked(()->jdbc.update("update orders set content_revision=content_revision+1 where id=?",basis.fixture.f.order())));
        basis.rejected(()->basis.locked(()->jdbc.update("update order_edit_inventory_plans set reservation_state='RELEASED' where admission_id=?",basis.fixture.receipt)));
    }
    @Test void intentDigestCannotBeReplacedAndDoesNotResolveUnknown() {
        request();String before=basis.business();
        basis.rejected(()->jdbc.update("update order_edit_failure_closure_controls set intent_digest=sha256('other'::bytea),version=version+1 where id=?",basis.control));
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition='UNKNOWN'",Integer.class,basis.fixture.receipt)).isEqualTo(1);
        assertThat(basis.business()).isEqualTo(before);
    }
    @Test void stoppedRuntimeRejectsNewIntent() {
        basis.fixture.stopRuntime();
        try {basis.rejected(this::request);}
        finally {basis.fixture.resumeTestRuntime();}
    }
    @Test void authenticationIssuanceBeforeIntentInvalidatesItsCas() {
        authenticate();assertThat(basis.fixture.cards.start(basis.fixture.f.order(),basis.fixture.f.customer(),basis.fixture.receipt)).isPresent();
        basis.rejected(this::request);
    }
    @Test void lateAuthorizationCannotChangePaymentAfterIntent() {
        request();String before=basis.business();
        assertThatThrownBy(()->basis.fixture.transactions.authorization(basis.fixture.f.order(),basis.fixture.operation,
            basis.fixture.observation(basis.fixture.flow,"requires_capture",basis.fixture.amount,
                Map.of("order_id",Long.toString(basis.fixture.f.order()),"order_change_operation_id",basis.fixture.operation.toString()))))
            .hasStackTraceContaining("Closure intent fences normal order-edit mutation");
        assertThat(basis.business()).isEqualTo(before);
    }
    @Test void rollbackDoesNotLeaveIntentOrFence() {
        try {basis.locked(()->{request();throw new IllegalStateException("rollback");});}catch(IllegalStateException expected){}
        assertThat(jdbc.queryForObject("select count(*) from order_edit_failure_closure_controls where id=?",Integer.class,basis.control)).isZero();
        assertThat(request()).isEqualTo(basis.control);
    }
    com.example.ecsite.service.order.evidence.OrderEditFailureIntentService.Command command(){
        return new com.example.ecsite.service.order.evidence.OrderEditFailureIntentService.Command(basis.control,basis.fixture.receipt,
            executionVersion,operationVersion,generation,HexFormat.of().formatHex(history));
    }
    @AfterEach void clearActor(){basis.fixture.clearActor();}
    void authenticate(){
        var p=new com.example.ecsite.security.CustomUserDetails(basis.fixture.f.customer(),actorName,"unused",true,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(p,"unused",p.getAuthorities()));
    }
    @Test void customerServiceRequiresAuthenticationAndPreservesSavedIntent() {
        var service=new com.example.ecsite.service.order.evidence.OrderEditFailureIntentService(jdbc.getDataSource());
        assertThatThrownBy(()->service.request(command())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        authenticate();
        assertThat(service.request(command()).outcome().name()).isEqualTo("RECORDED");
        assertThat(service.recover(command()).outcome().name()).isEqualTo("OBSERVED");
        assertThat(service.request(command()).outcome().name()).isEqualTo("RECORDED");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void lostCommitResponseIsReadWithoutRepeatingCommand(boolean committed) {
        authenticate();var count=new java.util.concurrent.atomic.AtomicInteger();
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){
                count.incrementAndGet();if(committed)super.doCommit(s);else super.doRollback(s);
                throw new org.springframework.transaction.TransactionSystemException("Synthetic lost response");
            }
        };
        var service=new com.example.ecsite.service.order.evidence.OrderEditFailureIntentService(jdbc.getDataSource(),manager);
        assertThat(service.request(command()).outcome().name()).isEqualTo(committed?"OBSERVED":"NOT_OBSERVED");
        assertThat(count).hasValue(1);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_failure_closure_controls where id=?",Integer.class,basis.control)).isEqualTo(committed?1:0);
    }
    @Test void twoConcurrentCommandsCannotBothCreateIntent() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var tasks=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{
                start.await();try{return request(UUID.randomUUID(),executionVersion,operationVersion,generation,history)!=null;}
                catch(org.springframework.dao.DataAccessException rejected){return false;}
            })).toList();start.countDown();
            int successes=0;for(var task:tasks)if(task.get(10,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);
        }finally {pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @ParameterizedTest @ValueSource(strings={"operation","authorization","dispatch","order"})
    void intentCommitSerializesAndRejectsWaitingNormalWriter(String kind) throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var saved=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var trying=new java.util.concurrent.CountDownLatch(1);
        try {
            var intent=pool.submit(()->basis.locked(()->{request();saved.countDown();
                try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("test lock timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));assertThat(saved.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->{trying.countDown();basis.locked(()->{
                switch(kind){
                case "operation" -> jdbc.update("update order_change_operations set version=version+1 where id=?",basis.fixture.operation);
                case "authorization" -> jdbc.update("update payment_transactions set status='SUCCEEDED' where order_change_operation_id=?",basis.fixture.operation);
                case "dispatch" -> jdbc.update("update order_change_request_dispatches set state='STARTED' where request_id in(select id from order_change_financial_requests where operation_id=?)",basis.fixture.operation);
                default -> jdbc.update("update orders set content_revision=content_revision+1 where id=?",basis.fixture.f.order());
                }
            });});assertThat(trying.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->writer.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();intent.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(()->writer.get(10,java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class).hasStackTraceContaining("Closure intent fences normal order-edit mutation");
        }finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
}
