package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.OrderWriteContext;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class CaptureShipmentPolicyPostgresTest {
    @Autowired CaptureShipmentPolicy policy;
    @Autowired OrderWriteContext context;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;
    @AfterEach void noExternalCalls() { verifyNoInteractions(gateway); }

    private Fixture fixture(String stage) {
        return new TransactionTemplate(manager).execute(status -> {
            LocalDateTime now=orders.findCurrentTimeInTokyo(); String unique=UUID.randomUUID().toString();
            User user=new User(); user.setUsername("capture-policy-"+unique); user.setPassword("password");
            user.setCreatedAt(now); user.setUpdatedAt(now); user.setEnabled(true); users.saveAndFlush(user);
            LocalDateTime ordered=now.minusDays(1).toLocalDate().atTime(10,0);
            Order order=new Order(user.getId(),200,ordered,ordered.withHour(14)); order.beginShippingPreparation(ordered.withHour(14),7L,"preparer");
            orders.saveAndFlush(order);
            Payment payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,now);
            String provider="policy_"+unique; payment.setProviderPaymentId(provider,now); payment.markAuthorized(now); payments.saveAndFlush(payment);
            var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,"auth_"+unique,now);
            auth.markSucceeded(provider,now); transactions.saveAndFlush(auth);
            PaymentOperationSnapshot expected=null; Long captureId=null;
            if(!stage.equals("NEW")) {
                var capture=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,"cap_"+unique,now,
                        new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,9L,"shipper","note"));
                transactions.saveAndFlush(capture); captureId=capture.getId(); expected=PaymentOperationSnapshot.from(capture);
                order.confirmCardShipment(now,9L,"shipper",payment.getId());
                if(stage.equals("ALREADY_APPLIED")) {
                    capture.markSucceeded(provider,now); payment.markCaptured(now); order.recordShipmentCaptureSucceeded(payment.getId(),now);
                }
            }
            em.flush(); return new Fixture(order.getId(),user.getId(),payment.getId(),captureId,expected);
        });
    }
    private Map<String,Object> state(Fixture f) {
        Map<String,Object> state=new LinkedHashMap<>(); state.put("order",jdbc.queryForMap("select * from orders where id=?",f.orderId()));
        state.put("payments",jdbc.queryForList("select * from payments where order_id=? order by id",f.orderId()));
        state.put("transactions",jdbc.queryForList("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id",f.orderId()));
        state.put("history",jdbc.queryForList("select * from order_status_histories where order_id=? order by id",f.orderId())); return state;
    }
    @ParameterizedTest @ValueSource(strings={"NEW","CONTINUE","ALREADY_APPLIED"})
    void classifiesRealPersistedDataWithoutQueriesUpdatesOrGateway(String stage) {
        Fixture f=fixture(stage); var before=state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            context.requireCleanWritableReadCommitted(); Order order=orders.findByIdForUpdate(f.orderId()).orElseThrow();
            context.refreshWritableOrder(order); context.refreshPayments(order);
            var current=payments.findByOrderIdForUpdate(f.orderId());
            List<PaymentTransaction> history=new ArrayList<>();
            for(Payment payment:current) history.addAll(transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()));
            var statistics=em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
            boolean enabled=statistics.isStatisticsEnabled(); statistics.setStatisticsEnabled(true);
            long statements=statistics.getPrepareStatementCount(); long flushes=statistics.getFlushCount();
            try {
                assertThat(policy.classify(order,current,history,f.expected()).disposition().name()).isEqualTo(stage);
                assertThat(statistics.getPrepareStatementCount()).isEqualTo(statements);
                assertThat(statistics.getFlushCount()).isEqualTo(flushes);
                assertThat(em.unwrap(Session.class).isDirty()).isFalse();
            } finally { statistics.setStatisticsEnabled(enabled); }
        });
        assertThat(state(f)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"payment","transaction","unrelated"})
    void checksOnlyUpdateTargetsBeforeRefreshAndPreservesDatabase(String readOnly) {
        Fixture f=fixture("CONTINUE"); var before=state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            context.requireCleanWritableReadCommitted(); orders.findByIdForUpdate(f.orderId()).orElseThrow();
            Payment payment=payments.findByIdForUpdate(f.paymentId()).orElseThrow();
            PaymentTransaction capture=transactions.findById(f.captureId()).orElseThrow();
            Object target=readOnly.equals("payment")?payment:readOnly.equals("transaction")?capture:em.find(User.class,f.userId());
            Session session=em.unwrap(Session.class); session.setReadOnly(target,true);
            assertThat(session.isDefaultReadOnly()).isFalse();
            var factory=em.getEntityManagerFactory().unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class);
            var registry=factory.getServiceRegistry().getService(org.hibernate.event.service.spi.EventListenerRegistry.class);
            var group=registry.getEventListenerGroup(org.hibernate.event.spi.EventType.REFRESH);
            var originals=new ArrayList<org.hibernate.event.spi.RefreshEventListener>(); group.listeners().forEach(originals::add);
            var refreshed=new ArrayList<Object>();
            org.hibernate.event.spi.RefreshEventListener tracker=new org.hibernate.event.spi.RefreshEventListener() {
                public void onRefresh(org.hibernate.event.spi.RefreshEvent event) { refreshed.add(event.getObject()); }
                public void onRefresh(org.hibernate.event.spi.RefreshEvent event,org.hibernate.event.spi.RefreshContext context) { refreshed.add(event.getObject()); }
            };
            registry.prependListeners(org.hibernate.event.spi.EventType.REFRESH,tracker);
            try {
                if(readOnly.equals("unrelated")) {
                    context.refreshWritableCaptureTargets(payment,capture);
                    assertThat(refreshed).contains(payment,capture).doesNotContain(target);
                } else {
                    assertThatIllegalStateException().isThrownBy(() -> context.refreshWritableCaptureTargets(payment,capture));
                    assertThat(refreshed).isEmpty(); assertThat(session.isReadOnly(target)).isTrue();
                }
            } finally { registry.setListeners(org.hibernate.event.spi.EventType.REFRESH,originals.toArray(org.hibernate.event.spi.RefreshEventListener[]::new)); }
        });
        assertThat(state(f)).isEqualTo(before);
    }
    private record Fixture(Long orderId,Long userId,Long paymentId,Long captureId,PaymentOperationSnapshot expected) {}
}
