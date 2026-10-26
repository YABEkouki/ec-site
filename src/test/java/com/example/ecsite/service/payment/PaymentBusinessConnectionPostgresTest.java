package com.example.ecsite.service.payment;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.service.OrderStatusHistoryService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Only run through run-isolated-tests.sh: the test initializer guards before datasource creation. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@com.example.ecsite.support.CaptureConnectionTest
class PaymentBusinessConnectionPostgresTest {
    @Autowired PaymentService service;
    @Autowired ActivePaymentService active;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired OrderStatusHistoryService histories;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;
    <T>T tx(Supplier<T> work){return new TransactionTemplate(manager).execute(s->work.get());}
    Long order() {return tx(()->{
        var now=orders.findCurrentTimeInTokyo();
        var user=new User();user.setUsername("purpose-"+UUID.randomUUID());user.setPassword("password");user.setEnabled(true);user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
        var o=orders.saveAndFlush(new Order(user.getId(),100,now,now.plusHours(1)));
        histories.record(o,null,OrderStatus.ORDERED,OrderStatusHistoryActorType.USER,user.getId(),user.getUsername(),null);
        return o.getId();
    });}
    @Test void newAuthorizationStoresPurposeAndActiveReferenceInSameTransaction(){
        Long id=order();var started=tx(()->service.startAuthorization(orders.findByIdForUpdate(id).orElseThrow()));
        assertThat(started.snapshot().purpose()).isEqualTo(PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,id)).isEqualTo(started.paymentId());
        assertThat(jdbc.queryForObject("select purpose from payment_transactions where id=?",String.class,started.transactionId())).isEqualTo("INITIAL_ORDER_AUTHORIZATION");
    }
    @Test void legacyAuthorizedPaymentIsInitializedOnlyAfterFullClassification(){
        Long id=order();Long pid=tx(()->{
            var o=orders.findByIdForUpdate(id).orElseThrow();var now=orders.findCurrentTimeInTokyo();
            var p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);p.setProviderPaymentId("pfw_"+UUID.randomUUID(),now);p.markAuthorized(now);payments.saveAndFlush(p);
            var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,UUID.randomUUID().toString(),now);t.markSucceeded(p.getProviderPaymentId(),now);transactions.saveAndFlush(t);return p.getId();
        });
        assertThat(active.initializeForOrderChange(id)).isEqualTo(pid);
        assertThat(active.initializeForOrderChange(id)).isEqualTo(pid);
        assertThat(jdbc.queryForObject("select purpose from payment_transactions where payment_id=?",String.class,pid)).isNull();
    }
    @Test void initialAuthorizationFailureClearsActiveReference(){
        Long id=order();var started=tx(()->service.startAuthorization(orders.findByIdForUpdate(id).orElseThrow()));
        var snapshot=tx(()->{var o=orders.findByIdForUpdate(id).orElseThrow();service.setProviderPaymentId(o,started.snapshot(),"pfw_failure_"+id);return PaymentOperationSnapshot.from(transactions.findById(started.transactionId()).orElseThrow());});
        assertThat(tx(()->service.applyAuthorizationResult(orders.findByIdForUpdate(id).orElseThrow(),snapshot,new AuthorizationResult(AuthorizationResultStatus.FAILED,snapshot.providerPaymentId(),null,null)))).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,id)).isNull();
    }
    @Test void cancellationPersistsPurposeAndClearsReferenceOnSuccess(){
        Long id=order();var started=tx(()->service.startAuthorization(orders.findByIdForUpdate(id).orElseThrow()));
        var auth=tx(()->{var o=orders.findByIdForUpdate(id).orElseThrow();service.setProviderPaymentId(o,started.snapshot(),"pfw_cancel_"+id);return PaymentOperationSnapshot.from(transactions.findById(started.transactionId()).orElseThrow());});
        assertThat(tx(()->service.applyAuthorizationResult(orders.findByIdForUpdate(id).orElseThrow(),auth,new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,auth.providerPaymentId(),null,null)))).isEqualTo(PaymentResultApplication.APPLIED);
        var cancel=tx(()->service.startCancellation(orders.findByIdForUpdate(id).orElseThrow(),new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,7L,"admin",null)));
        assertThat(cancel.snapshot().purpose()).isEqualTo(PaymentTransactionPurpose.ORDER_CANCELLATION);
        assertThat(tx(()->service.applyCancellationResult(orders.findByIdForUpdate(id).orElseThrow(),cancel.snapshot(),new CancellationResult(CancellationResultStatus.CANCELLED,auth.providerPaymentId())))).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,id)).isNull();
    }
    @Test void legacyPendingAuthorizationRecoversWithoutPurposeOrActiveBackfill(){
        Long id=order();var snapshot=tx(()->{
            var o=orders.findByIdForUpdate(id).orElseThrow();var now=orders.findCurrentTimeInTokyo();
            var p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);p.setProviderPaymentId("pfw_legacy_"+id,now);payments.saveAndFlush(p);
            var t=transactions.saveAndFlush(new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,UUID.randomUUID().toString(),now));return PaymentOperationSnapshot.from(t);
        });
        assertThat(tx(()->service.applyAuthorizationResult(orders.findByIdForUpdate(id).orElseThrow(),snapshot,new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,snapshot.providerPaymentId(),null,null)))).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,id)).isNull();
        assertThat(jdbc.queryForObject("select purpose from payment_transactions where id=?",String.class,snapshot.transactionId())).isNull();
    }
    @Test void ambiguousPaymentsCannotInitializeActive(){
        Long id=order();tx(()->{var o=orders.findByIdForUpdate(id).orElseThrow();var now=orders.findCurrentTimeInTokyo();
            for(int i=0;i<2;i++){var p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,100,now);p.setProviderPaymentId("pfw_multi_"+UUID.randomUUID(),now);p.markAuthorized(now);payments.saveAndFlush(p);var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,100,0,UUID.randomUUID().toString(),now);t.markSucceeded(p.getProviderPaymentId(),now);transactions.saveAndFlush(t);}return null;});
        assertThatThrownBy(()->active.initializeForOrderChange(id)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,id)).isNull();
    }
    @Test void targetedInitializationAllowsDecreasedAmountWithRevisionDifference(){
        Long id=order();Long pid=authorized(id,200);
        jdbc.update("update orders set content_revision=1 where id=?",id);
        assertThat(active.initializeForOrderChange(id)).isEqualTo(pid);
    }
    @Test void targetedInitializationRejectsSameAmountRevisionMismatch(){
        Long id=order();authorized(id,100);
        jdbc.update("update orders set content_revision=1 where id=?",id);
        assertThatThrownBy(()->active.initializeForOrderChange(id)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,id)).isNull();
    }
    Long authorized(Long id,int amount){return tx(()->{
        var o=orders.findByIdForUpdate(id).orElseThrow();var now=orders.findCurrentTimeInTokyo();
        var p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,amount,now);p.setProviderPaymentId("pfw_init_"+UUID.randomUUID(),now);p.markAuthorized(now);payments.saveAndFlush(p);
        var t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,amount,0,UUID.randomUUID().toString(),now);t.markSucceeded(p.getProviderPaymentId(),now);transactions.saveAndFlush(t);return p.getId();
    });}
}
