package com.example.ecsite.payment.safety;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.orderchange.OrderChangeRequestSender;
import com.example.ecsite.security.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.order.evidence.OrderEditCandidate.Line;
import com.example.ecsite.service.orderchange.*;
import com.example.ecsite.support.isolation.IsolatedTestMode;

/** Feature140-B: batch edits (Feature125) never reach the legacy screens, expiry candidates or recovery targets.
 * Uses the fixture of {@link OrderEditReauthorizationIntegrationTest} on the latest schema. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@com.example.ecsite.support.CaptureConnectionTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@IsolatedTestMode(IsolatedTestMode.Mode.SHARED)
class LegacyChangeScopePostgresTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired AdminOldCancelRecoveryViewService recoveryViews;
    @MockitoBean PaymentGateway gateway;
    OrderEditReauthorizationIntegrationTest t;

    @BeforeEach void fixture(TestInfo info){
        t=new OrderEditReauthorizationIntegrationTest();
        context.getAutowireCapableBeanFactory().autowireBean(t);t.gateway=gateway;t.fixtureDatabase(info);
    }
    @AfterEach void stop(){t.stopRuntime();}

    UUID operation(long order){return t.db.jdbc.queryForObject("select id from order_change_operations where order_id=? order by created_at desc limit 1",UUID.class,order);}
    Map<String,Object> row(UUID op){return t.db.jdbc.queryForMap("select state,execution_version,execution_route,financial_review_reason from order_change_operations where id=?",op);}
    Map<String,String> saved(){
        var rows=new TreeMap<String,String>();
        for(String table:List.of("orders","order_items","products","payments","payment_transactions","stock_movements","order_change_operations",
                "order_change_financial_requests","order_change_request_dispatches","order_edit_admissions","order_edit_inventory_plans","order_change_recovery_audits"))
            rows.put(table,t.db.jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(r) order by to_jsonb(r)::text),'[]'::jsonb)::text from "+table+" r",String.class));
        return rows;
    }
    AdminUserDetails admin(){return new AdminUserDetails(9L,"admin","unused",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));}
    void assertHidden(long order,long customer,UUID op) throws Exception {
        var before=saved();clearInvocations(gateway);
        var owner=user(new CustomUserDetails(customer,"customer","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        String root="/orders/"+order+"/increase/"+op;
        for(var request:List.of(get(root),get(root+"/withdraw"),
                post(root+"/withdraw").with(csrf()).param("requestId",UUID.randomUUID().toString()).param("expectedVersion","0"),
                post(root+"/payment").with(csrf()),get(root+"/payment"),get(root+"/payment/return"),post(root+"/payment/continue").with(csrf())))
            mvc.perform(request.with(owner)).andExpect(status().isNotFound());
        String recovery="/admin/orders/"+order+"/changes/"+op+"/old-cancel-recovery";
        mvc.perform(get(recovery).with(user(admin()))).andExpect(status().isNotFound());
        mvc.perform(post(recovery).with(user(admin())).with(csrf()).param("requestId",UUID.randomUUID().toString())
            .param("expectedVersion","0").param("reason","VERIFY_OLD_CANCEL")).andExpect(status().isNotFound());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin(),"unused",admin().getAuthorities()));
        try{assertThat(recoveryViews.eligible(order)).isEmpty();}finally{SecurityContextHolder.clearContext();}
        assertThat(saved()).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @Test void completedRetainedAuthorizationIsHidden() throws Exception {
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);t.mockTransport(f);
        UUID id=t.accepted(f,List.of(new Line(f.item(),f.product(),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(t.service().execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        UUID op=operation(f.order());
        assertThat(row(op)).containsEntry("execution_route","RETAIN_AUTH").containsEntry("state","COMPLETED");
        // The reason the scope is execution_version: a retained authorization is not f125-owned.
        assertThat(t.db.jdbc.queryForObject("select f125_reauth_is_owned(?,?)",Boolean.class,f.order(),op)).isFalse();
        assertHidden(f.order(),f.customer(),op);
    }
    @Test void unsettledReauthorizationIsHidden() throws Exception {
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=t.helper().product(f,7000);t.mockTransport(f);t.newStatus="processing";
        UUID id=t.accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);
        assertThat(t.service().execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
        UUID op=operation(f.order());
        assertThat(row(op)).containsEntry("execution_route","REAUTHORIZE");assertThat(row(op).get("state")).isNotIn("COMPLETED","ABORTED");
        assertHidden(f.order(),f.customer(),op);t.assertHeld(id);
    }
    @Test void reauthorizationInTheRecoveryConditionIsHidden() throws Exception {
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=t.helper().product(f,7000);t.mockTransport(f);
        // The old authorization is reported canceled, but with an amount that is not the saved one.
        doAnswer(call->{
            var request=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request().httpRequest();
            t.operation=request.operationId();t.newFlow="pfw_f125_"+t.operation;
            if("CREATE".equals(request.kind())){t.afterAmount=request.amount();return t.observation(t.newFlow,"requires_payment_method",t.afterAmount,Map.of());}
            t.oldCancelled.set(true);return t.observation(t.oldFlow,"canceled",5999,Map.of());
        }).when(gateway).sendOrderChangeRequest(any());
        doAnswer(call->{
            String flow=call.getArgument(0);
            if(flow.equals(t.oldFlow))return t.oldCancelled.get()?t.observation(flow,"canceled",5999,Map.of()):t.observation(flow,"requires_capture",6000,Map.of());
            return t.observation(flow,t.newStatus,t.afterAmount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",t.operation.toString()));
        }).when(gateway).observeOrderChangeFlow(anyString());
        UUID id=t.accepted(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);
        assertThat(t.service().execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
        UUID op=operation(f.order());
        assertThat(row(op)).containsEntry("state","MANUAL_REVIEW").containsEntry("financial_review_reason","CANCEL_FLOW_EVIDENCE_MISMATCH");
        assertThat(t.db.jdbc.queryForObject("""
            select exists(select 1 from order_change_operations o join order_change_financial_requests r on r.operation_id=o.id and r.order_id=o.order_id
              and r.payment_id=o.old_payment_id and r.request_kind='OLD_CANCEL' and r.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL' where o.id=?)
            """,Boolean.class,op)).isTrue();
        assertHidden(f.order(),f.customer(),op);
    }
    @Test void expiredReservedBatchEditIsNotAnExpiryCandidate(){
        var f=OrderPaymentEvidenceFoundationPostgresTest.fixture();
        t.db.jdbc.update("update orders set ordered_at=localtimestamp-interval '3 days',change_deadline_at=localtimestamp-interval '2 days' where id=?",f.order());
        OrderPaymentEvidenceFoundationPostgresTest.enroll(f);
        // Seen from the transaction that records it, then rolled back.
        assertThatThrownBy(()->OrderPaymentEvidenceFoundationPostgresTest.tx(()->{
            t.db.jdbc.queryForObject("select id from orders where id=? for update",Long.class,f.order());
            UUID op=OrderPaymentEvidenceFoundationPostgresTest.operation(f,0,6000,5000,"RETAIN_AUTH",null);
            assertThat(t.db.jdbc.queryForObject("""
                select state='RESERVED' and abort_request_id is null and switch_decided_at is null and new_payment_id is null
                  and grace_until<=timezone('Asia/Tokyo',clock_timestamp()) from order_change_operations where id=?
                """,Boolean.class,op)).isTrue();
            var store=new OrderChangeStore(t.db.jdbc);
            assertThat(store.operation(f.order(),op)).isPresent();
            assertThat(store.legacyOperation(f.order(),op)).isEmpty();
            assertThat(store.expiredReserved(1000)).noneMatch(c->c.operationId().equals(op));
            throw new Rollback();
        })).isInstanceOf(Rollback.class);
    }
    static final class Rollback extends RuntimeException {}
}
