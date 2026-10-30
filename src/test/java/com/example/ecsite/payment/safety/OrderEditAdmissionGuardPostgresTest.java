package com.example.ecsite.payment.safety;

import java.util.UUID;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.orderchange.OrderChangeStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.*;
import com.example.ecsite.service.orderchange.OrderIncreaseService;
import com.example.ecsite.service.payment.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.example.ecsite.security.AdminUserDetails;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.payment.safety.OrderEditAdmissionFoundationPostgresTest.*;

/** Uses the existing guarded, commit-tracking PostgreSQL fixture, never development DB configuration. */
class OrderEditAdmissionGuardPostgresTest {
    @BeforeAll static void database() { OrderEditAdmissionFoundationPostgresTest.database(); }
    @AfterAll static void cleanup() { OrderEditAdmissionFoundationPostgresTest.cleanup(); }
    @BeforeEach void runtime(TestInfo info) { new OrderEditAdmissionFoundationPostgresTest().runtime(info); }
    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }
    static Order order(long id) { var now=LocalDateTime.now();var result=new Order(1L,6000,now.minusHours(1),now.plusDays(1)); ReflectionTestUtils.setField(result,"id",id);return result; }
    static OrderPaymentGuard guard() { return new OrderPaymentGuard(null,null,new OrderChangeStore(jdbc),new com.example.ecsite.service.order.evidence.OrderEditAdmissionGuard(jdbc)); }
    @Test void recordedUnverifiedReceiptBlocksCommonOrderOperation() {
        var f=fixture(false);record(f);
        assertThatThrownBy(()->tx(()->{guard().requireNoOrderChange(order(f.order()));return null;}))
            .isInstanceOf(InvalidOrderStatusException.class);
    }
    @Test void acceptedReceiptBlocksCommonOrderOperation() {
        var f=fixture(false);UUID id=record(f);verify(id);
        assertThatThrownBy(()->tx(()->{guard().requireNoOrderChange(order(f.order()));return null;}))
            .isInstanceOf(InvalidOrderStatusException.class);
    }
    @ParameterizedTest @ValueSource(strings={"shipping","cancel","items","address","reauthorize","capture"})
    void realServiceEntryRejectsAdmissionBeforeItsMutation(String operation) {
        var f=fixture(false);UUID id=record(f);
        if(Set.of("cancel","reauthorize","capture").contains(operation)) verify(id);
        if(operation.equals("items"))tx(()->{jdbc.queryForObject("select f125_admission_hold(?,'TEST_UNPROVEN')",Object.class,id);return null;});
        if(operation.equals("capture"))tx(()->{
            jdbc.queryForObject("select f125_admission_lock(?)",Object.class,id);
            for(String state:List.of("OBSERVING","APPLYING","RECOVERY_PENDING"))
                jdbc.update("update order_edit_admissions set execution_state=?,version=version+1 where id=?",state,id);
            return null;
        });
        String before=jdbc.queryForObject("select to_jsonb(o)::text from orders o where id=?",String.class,f.order());
        assertThatThrownBy(()->tx(()->{invoke(operation,f);return null;})).isInstanceOf(InvalidOrderStatusException.class);
        assertThat(jdbc.queryForObject("select to_jsonb(o)::text from orders o where id=?",String.class,f.order())).isEqualTo(before);
    }
    // Real entry methods and real DB gate; unrelated collaborators are mocked to keep financial IO impossible.
    static void invoke(String operation,Fixture f) {
        var o=order(f.order());var repository=mock(OrderRepository.class);
        when(repository.findByIdForUpdate(f.order())).thenReturn(Optional.of(o));
        when(repository.findByIdAndUserIdForUpdate(f.order(),f.customer())).thenReturn(Optional.of(o));
        var dependencies=new HashMap<Class<?>,Object>();
        dependencies.put(OrderRepository.class,repository);dependencies.put(OrderPaymentGuard.class,guard());
        dependencies.put(OrderChangeStore.class,new OrderChangeStore(jdbc));
        dependencies.put(OrderWriteContext.class,mock(OrderWriteContext.class));
        var actor=new AdminUserDetails(1L,"test-admin","unused",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"unused",actor.getAuthorities()));
        switch(operation) {
            case "shipping"->create(OrderShippingPreparationService.class,dependencies).start(f.order());
            case "cancel"->create(PaymentService.class,dependencies).startCancellation(o,null);
            case "items"->create(OrderService.class,dependencies).changeItemsForUser(f.order(),f.customer(),"test",null);
            case "address"->create(OrderService.class,dependencies).changeShippingAddressForUser(f.order(),f.customer(),"test",null);
            case "reauthorize"->create(OrderIncreaseService.class,dependencies).accept(new OrderIncreaseService.Request(f.order(),f.customer(),UUID.randomUUID(),0,Map.of(1L,2)));
            case "capture"->create(CaptureShipmentOperations.class,dependencies).start(f.order(),1L,"test-admin",null);
            default->throw new IllegalArgumentException(operation);
        }
    }
    static <T>T create(Class<T> type,Map<Class<?>,Object> dependencies) {
        try {
            var constructor=type.getConstructors()[0];
            Object[] arguments=Arrays.stream(constructor.getParameterTypes()).map(t->dependencies.containsKey(t)?dependencies.get(t):mock(t)).toArray();
            return type.cast(constructor.newInstance(arguments));
        } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
    }
    @ParameterizedTest @ValueSource(strings={"CLOSED_UNAPPLIED","FAILED_UNAPPLIED","REJECTED_LATE","COMPLETED"})
    void safeTerminalAdmissionAllowsExistingGuard(String terminal) {
        long orderId;
        if(terminal.equals("COMPLETED")) {
            new OrderEditAdmissionFoundationPostgresTest().matchingCompletedV69OperationCanCloseAdmissionAtomically();
            orderId=jdbc.queryForObject("select order_id from order_edit_admissions where execution_state='COMPLETED' order by created_at desc limit 1",Long.class);
        } else {
            var f=fixture(terminal.equals("REJECTED_LATE"));UUID id=record(f);orderId=f.order();
            if(terminal.equals("REJECTED_LATE"))verify(id);
            else tx(()->{jdbc.queryForObject("select f125_admission_lock(?)",Object.class,id);jdbc.update("update order_edit_admissions set execution_state=?,version=version+1 where id=?",terminal,id);return null;});
        }
        long target=orderId;
        assertThatCode(()->tx(()->{guard().requireNoOrderChange(order(target));return null;})).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"shipping","cancel"})
    void serviceWaitsForAdmissionCommitThenRechecks(String operation) throws Exception {
        var f=fixture(false);var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var waitingPid=new java.util.concurrent.atomic.AtomicInteger();
        var workers=Executors.newFixedThreadPool(2);
        try {
            var admission=workers.submit(()->tx(()->{
                UUID id=insert(f,UUID.randomUUID());inserted.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("test lock wait");}
                catch(InterruptedException e){throw new IllegalStateException(e);}return id;
            }));
            assertThat(inserted.await(10,TimeUnit.SECONDS)).isTrue();
            var action=workers.submit(()->tx(()->{waitingPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));entered.countDown();invoke(operation,f);return null;}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->Boolean.TRUE.equals(
                jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,waitingPid.get())));
            assertThat(action.isDone()).isFalse();
            release.countDown();assertThat(admission.get(10,TimeUnit.SECONDS)).isNotNull();
            assertThatThrownBy(()->action.get(10,TimeUnit.SECONDS)).hasRootCauseInstanceOf(InvalidOrderStatusException.class);
        } finally {release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void earlierCancellationMakesSubsequentAdmissionFailRevalidation() {
        var f=fixture(false);
        tx(()->{guard().requireNoOrderChange(order(f.order()));jdbc.update("update orders set status='CANCELLED' where id=?",f.order());return null;});
        db.rejects("23514",()->record(f));
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
    }
    @Test void writableReadCommittedTransactionIsMandatoryEvenWithoutAnyAdmission() {
        var f=fixture(false);
        assertThatThrownBy(()->guard().requireNoOrderChange(order(f.order()))).isInstanceOf(IllegalStateException.class);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.source));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(()->transaction.execute(s->{guard().requireNoOrderChange(order(f.order()));return null;}))
            .isInstanceOf(IllegalStateException.class);
    }
}
