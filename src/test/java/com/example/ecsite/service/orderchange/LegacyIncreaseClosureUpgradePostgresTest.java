package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.ecsite.payment.PaymentGateway;

/** Feature140-A: V108 on a database that still holds unfinished and completed legacy increases. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false","spring.flyway.target=107"})
@com.example.ecsite.support.CaptureConnectionTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyIncreaseClosureUpgradePostgresTest {
    @Autowired ApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired OrderIncreaseService increases;
    @Autowired OrderReauthorizationService service;
    @Autowired OrderReauthorizationTransactions results;
    @MockitoBean PaymentGateway gateway;

    String state(UUID op){return jdbc.queryForObject("select state from order_change_operations where id=?",String.class,op);}
    int stock(long product){return jdbc.queryForObject("select stock from products where id=?",Integer.class,product);}
    Map<String,String> saved(){
        var rows=new TreeMap<String,String>();
        for(String table:List.of("order_change_operations","order_change_items","inventory_reservations","order_change_financial_requests",
                "order_change_request_dispatches","payments","payment_transactions","orders","stock_movements"))
            rows.put(table,jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(r) order by to_jsonb(r)::text),'[]'::jsonb)::text from "+table+" r",String.class));
        return rows;
    }
    static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){
        assertThatThrownBy(call).satisfies(e->{
            Throwable t=e;while(t!=null&&!(t instanceof java.sql.SQLException))t=t.getCause();
            assertThat(t).as("%s",e).isNotNull();assertThat(((java.sql.SQLException)t).getSQLState()).isEqualTo("23514");
            assertThat(t).hasMessageContaining("legacy quantity increase is closed");
        });
    }
    @Test void existingIncreasesFinishAndNothingNewStarts(){
        var t=LegacyRecoveredOrderChangeHistoryFixture.wired(context,gateway);
        var completed=t.fixture();var completedOp=t.accept(completed,3);t.mockFlow(completed,completedOp);
        assertThat(service.advance(completed.order(),completedOp.id()).state()).isEqualTo("COMPLETED");
        var withdrawn=t.fixture();var withdrawnOp=t.accept(withdrawn,3);
        var expired=t.fixture();var expiredOp=t.accept(expired,3);
        var prepared=t.fixture();var preparedOp=t.accept(prepared,3);results.prepare(prepared.order(),preparedOp.id());
        var waiting=t.fixture();var waitingOp=t.accept(waiting,3);t.mockFlow(waiting,waitingOp);t.newState.set("requires_action");
        assertThat(service.advance(waiting.order(),waitingOp.id()).state()).isEqualTo("REQUIRES_ACTION");

        var before=saved();
        org.flywaydb.core.Flyway.configure().configuration(flyway.getConfiguration()).target("latest").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='108' and success",Integer.class)).isOne();
        assertThat(saved()).isEqualTo(before);

        // Nothing new: neither an increase nor an authorization of a reserved one, and nothing is left behind.
        var fresh=t.fixture();before=saved();
        rejects(()->t.accept(fresh,3));
        rejects(()->results.prepare(withdrawn.order(),withdrawnOp.id()));
        rejects(()->service.advance(expired.order(),expiredOp.id()));
        assertThat(saved()).isEqualTo(before);

        // Reserved: withdrawal and expiry release the stock exactly once.
        int held=stock(withdrawn.product());
        increases.withdrawReserved(withdrawn.order(),withdrawn.user(),withdrawnOp.id(),withdrawnOp.version());
        assertThat(state(withdrawnOp.id())).isEqualTo("ABORTED");assertThat(stock(withdrawn.product())).isEqualTo(held+1);
        jdbc.update("update order_change_operations set accepted_at=accepted_at-interval '3 hours',deadline_at=deadline_at-interval '3 hours',grace_until=grace_until-interval '3 hours' where id=?",expiredOp.id());
        assertThat(increases.expireReserved(expired.order(),expiredOp.id())).isTrue();
        assertThat(state(expiredOp.id())).isEqualTo("ABORTED");assertThat(stock(expired.product())).isEqualTo(held+1);

        // Started before the closure: withdrawal is still recorded, and a waiting authorization is applied to the order.
        long version=jdbc.queryForObject("select version from order_change_operations where id=?",Long.class,preparedOp.id());
        assertThat(results.requestAbort(new OrderReauthorizationTransactions.AbortRequest(prepared.order(),prepared.user(),preparedOp.id(),UUID.randomUUID(),version,
            OrderReauthorizationTransactions.AbortRequesterType.CUSTOMER,OrderReauthorizationTransactions.AbortReasonCode.CUSTOMER_REQUEST)).result())
            .isEqualTo(OrderReauthorizationTransactions.AbortResult.ACCEPTED);
        t.newState.set("requires_capture");
        assertThat(service.advance(waiting.order(),waitingOp.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select quantity from order_items where id=?",Integer.class,waiting.item())).isEqualTo(3);
        assertThat(jdbc.queryForList("select state from inventory_reservations where operation_id=?",String.class,waitingOp.id())).containsExactly("CONSUMED");
        assertThat(increases.status(completed.order(),completed.user(),completedOp.id()).state()).isEqualTo("COMPLETED");
    }
}
