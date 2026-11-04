package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.orderchange.OrderIncreaseService;
import com.example.ecsite.support.CaptureConnectionTest;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Persisted model-C membership must stop both legacy accept entrances before any write. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false","logging.level.org.springframework=WARN","logging.level.org.hibernate=WARN"})
@CaptureConnectionTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OrderIncreaseModelCEvidencePostgresTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties){
        OrderEditReauthorizationIntegrationTest.dedicatedCommitTrackingDatabase(properties);
    }
    @AfterAll static void stopDatabase(){OrderEditReauthorizationIntegrationTest.stopDedicatedDatabase();}
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired com.example.ecsite.service.orderchange.OrderReauthorizationService finance;
    @Autowired com.example.ecsite.service.orderchange.OrderReauthorizationTransactions financeTransactions;
    @Autowired com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore requestStore;
    @Autowired OrderIncreaseService legacy;
    @MockitoBean PaymentGateway gateway;
    OrderEditReauthorizationIntegrationTest h;
    @BeforeEach void fixture(TestInfo info){
        h=new OrderEditReauthorizationIntegrationTest();h.environment=environment;h.applicationJdbc=jdbc;
        h.manager=manager;h.finance=finance;h.financeTransactions=financeTransactions;h.requestStore=requestStore;h.gateway=gateway;
        h.fixtureDatabase(info);
    }
    @AfterEach void cleanup(){if(h!=null)h.stopRuntime();}
    void edit(OrderEditAdmissionServicePostgresTest.Fixture f,int price){
        long product=h.helper().product(f,price);
        var route=price>6000?OrderChangeRoutePolicy.Route.REAUTHORIZE:OrderChangeRoutePolicy.Route.RETAIN_AUTH;
        UUID id=h.accepted(f,List.of(new Line(null,product,1)),route);reset(gateway);h.mockTransport(f);
        assertThat(h.service().execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
    }
    void history(long order){
        jdbc.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_account_id,changed_by_username) select id,'ORDERED','USER',user_id,'legacy' from orders where id=?",order);
    }
    OrderIncreaseService.Request request(long order,long customer){
        long item=jdbc.queryForObject("select id from order_items where order_id=? order by id limit 1",Long.class,order);
        int quantity=jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,item);
        int revision=jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,order);
        return new OrderIncreaseService.Request(order,customer,UUID.randomUUID(),revision,Map.of(item,quantity+1));
    }
    // Compare every row of the business/evidence tables, including stock and child request/dispatch rows.
    // Tests run serially in this class's isolated database; no background worker is enabled.
    Map<String,String> savedState(){
        var state=new TreeMap<String,String>();
        var tables=jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename<>'flyway_schema_history' order by tablename",String.class);
        for(String table:tables)state.put(table,jdbc.queryForObject(
            "select coalesce(jsonb_agg(to_jsonb(r) order by to_jsonb(r)::text),'[]'::jsonb)::text from "+table+" r",String.class));
        return state;
    }
    @ParameterizedTest @ValueSource(ints={0,5000,6000,7000})
    void enrolledOrdersRejectBothAcceptEntrancesWithoutAnySavedSideEffect(int price){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);history(f.order());
        if(price!=0)edit(f,price);
        var req=request(f.order(),f.customer());var preview=legacy.preview(req);
        clearInvocations(gateway);var before=savedState();
        assertThatThrownBy(()->legacy.accept(req)).isInstanceOf(InvalidOrderStatusException.class).hasMessageContaining("一括編集");
        assertThat(savedState()).isEqualTo(before);
        assertThatThrownBy(()->legacy.acceptConfirmed(preview)).isInstanceOf(InvalidOrderStatusException.class).hasMessageContaining("一括編集");
        assertThat(savedState()).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @Test void legacyOrderStillAcceptsAndReplaysOneReservation(){
        var f=new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();history(f.order());
        long customer=jdbc.queryForObject("select user_id from orders where id=?",Long.class,f.order());
        var req=request(f.order(),customer);
        var accepted=legacy.acceptConfirmed(legacy.preview(req));
        assertThat(accepted.state()).isEqualTo("RESERVED");assertThat(legacy.accept(req)).isEqualTo(accepted);
        assertThat(jdbc.queryForObject("select count(*) from inventory_reservations where operation_id=? and state='HELD'",Integer.class,accepted.id())).isOne();
        verifyNoInteractions(gateway);
    }
    @Test void rejectionDoesNotBlockTheNewBatchPath(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);history(f.order());edit(f,5000);
        assertThatThrownBy(()->legacy.accept(request(f.order(),f.customer()))).isInstanceOf(InvalidOrderStatusException.class);
        edit(f,6000);
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(6000);
    }
}
