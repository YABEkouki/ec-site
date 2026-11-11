package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.payment.orderchange.OrderChangeRequestSender;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Failed 3DS closes only the unapplied candidate; no provider cancellation is claimed. */
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@com.example.ecsite.support.CaptureConnectionTest
@org.springframework.test.annotation.DirtiesContext
class OrderEditFailureClosurePostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AutowireCapableBeanFactory beans;
    @MockitoBean PaymentGateway gateway;
    OrderEditFailureClosureFoundationPostgresTest basis;
    String originalOrder,originalPayment,originalAuthorization,originalResults;
    int heldStock;
    @BeforeEach void fixture(TestInfo info) {
        basis=new OrderEditFailureClosureFoundationPostgresTest();beans.autowireBean(basis);
        basis.gateway=gateway;basis.basis(info);
        originalOrder=row("orders",basis.fixture.f.order());
        originalPayment=row("payments",basis.fixture.f.payment());
        originalAuthorization=row("payment_transactions",basis.fixture.f.auth());
        originalResults=results();
        heldStock=jdbc.queryForObject("select stock from products where id=?",Integer.class,basis.fixture.f.product());
        clearInvocations(gateway);
    }
    @AfterEach void clearActor(){basis.fixture.clearActor();}
    String row(String table,long id){return jdbc.queryForObject("select to_jsonb(t)::text from "+table+" t where id=?",String.class,id);}
    String results(){return jdbc.queryForObject("select jsonb_agg(to_jsonb(r) order by r.id)::text from order_edit_execution_attempt_results r where admission_id=?",String.class,basis.fixture.receipt);}
    void failure(){basis.intent();basis.observe("THREE_DS_FAILURE",basis.document("THREE_DS_FAILURE"));basis.advance(0,"FAILURE_OBSERVED");}
    UUID close(){return jdbc.queryForObject("select f125_failure_close_unapplied(?,?)",UUID.class,basis.control,1L);}
    int count(String table){return jdbc.queryForObject("select count(*) from "+table+" where control_id=?",Integer.class,basis.control);}
    void unchangedOriginal(){
        assertThat(row("orders",basis.fixture.f.order())).isEqualTo(originalOrder);
        assertThat(row("payments",basis.fixture.f.payment())).isEqualTo(originalPayment);
        assertThat(row("payment_transactions",basis.fixture.f.auth())).isEqualTo(originalAuthorization);
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,basis.fixture.f.order())).isZero();
        assertThat(results()).isEqualTo(originalResults);
    }
    void closed(){
        unchangedOriginal();
        assertThat(jdbc.queryForObject("select state from order_change_operations where id=?",String.class,basis.fixture.operation)).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,basis.fixture.receipt)).isEqualTo("FAILED_UNAPPLIED");
        assertThat(jdbc.queryForObject("select status from payments where id=(select new_payment_id from order_change_operations where id=?)",String.class,basis.fixture.operation)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=(select authorization_id from order_edit_failure_closure_controls where id=?)",String.class,basis.control)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,basis.fixture.f.product())).isEqualTo(heldStock+1);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_inventory_plans where admission_id=? and reservation_state='RELEASED' and release_movement_id is not null",Integer.class,basis.fixture.receipt)).isOne();
        assertThat(jdbc.queryForObject("select bool_and(released_at>=held_at) from order_edit_inventory_plans where admission_id=? and reservation_state='RELEASED'",Boolean.class,basis.fixture.receipt)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where order_id=? and movement_type='ORDER_CHANGE_RELEASE'",Integer.class,basis.fixture.f.order())).isOne();
        assertThat(jdbc.queryForObject("select abort_flow_closed_at is null and abort_request_id is null from order_change_operations where id=?",Boolean.class,basis.fixture.operation)).isTrue();
        assertThat(count("order_edit_failure_business_closures")).isOne();
        assertThat(count("order_edit_failure_waiting_resolutions")).isOne();
        assertThat(jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=? and request_kind<>'CREATE'",Integer.class,basis.fixture.operation)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where payment_id in (select id from payments where order_id=?)",Integer.class,basis.fixture.f.order())).isZero();
        assertThat(jdbc.queryForObject("select cancel_request_id is null and state='FAILURE_OBSERVED' and version=1 from order_edit_failure_closure_controls where id=?",Boolean.class,basis.control)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_edit_failure_closure_observations where control_id=? and kind='FLOW_TERMINAL'",Integer.class,basis.control)).isZero();
        verifyNoInteractions(gateway);
    }
    @Test void failedAuthenticationClosesCandidateAndRestoresStockWithoutCancelOrCapture(){
        failure();assertThat(close()).isEqualTo(basis.control);closed();
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition='UNKNOWN' and reason_code='REAUTHORIZATION_WAITING'",Integer.class,basis.fixture.receipt)).isOne();
    }
    @Test void repeatedCloseHasNoAdditionalMovementOrCertificate(){
        failure();close();String saved=basis.business();assertThat(close()).isEqualTo(basis.control);
        assertThat(basis.business()).isEqualTo(saved);closed();
        basis.tx.execute(s->{
            jdbc.execute("set local timezone='Asia/Tokyo'");
            assertThat(jdbc.queryForObject("select bool_and(f125_failure_waiting_resolved(id)) from order_edit_execution_attempts where admission_id=?",Boolean.class,basis.fixture.receipt)).isTrue();
            return null;
        });
        basis.rejected(()->jdbc.update("delete from order_edit_failure_waiting_resolutions where control_id=?",basis.control));
        basis.rejected(()->jdbc.update("delete from order_edit_failure_business_closures where control_id=?",basis.control));
    }
    @Test void existingDiscrepancyAuditTracksResidualAuthorizationWithoutRevivingCandidate(){
        failure();close();
        long payment=jdbc.queryForObject("select new_payment_id from order_edit_failure_closure_controls where id=?",Long.class,basis.control);
        var audit=beans.getBean(com.example.ecsite.service.payment.PaymentDiscrepancyAuditItemService.class);
        when(gateway.retrievePaymentFlow(basis.fixture.flow)).thenReturn(new com.example.ecsite.payment.PaymentFlowState(
            basis.fixture.flow,com.example.ecsite.payment.PaymentFlowStatus.REQUIRES_CAPTURE,null,null));
        var residual=audit.auditWithResult(payment);
        assertThat(residual.status()).isEqualTo(com.example.ecsite.service.payment.PaymentDiscrepancyAuditResult.Status.INCONSISTENT);
        assertThat(residual.discrepancyIds()).hasSize(1);
        when(gateway.retrievePaymentFlow(basis.fixture.flow)).thenReturn(new com.example.ecsite.payment.PaymentFlowState(
            basis.fixture.flow,com.example.ecsite.payment.PaymentFlowStatus.CANCELED,null,null));
        var released=audit.auditWithResult(payment);
        assertThat(released.status()).isEqualTo(com.example.ecsite.service.payment.PaymentDiscrepancyAuditResult.Status.CONSISTENT);
        assertThat(released.discrepancyIds()).containsExactlyElementsOf(residual.discrepancyIds());
        verify(gateway,times(2)).retrievePaymentFlow(basis.fixture.flow);clearInvocations(gateway);closed();
    }
    @Test void lateAuthorizationCannotReviveClosedCandidate(){
        failure();close();String before=basis.business();
        // Exercise the real transaction service, including its late-event short circuit.
        try {basis.fixture.transactions.authorization(basis.fixture.f.order(),basis.fixture.operation,
            basis.fixture.observation(basis.fixture.flow,"requires_capture",basis.fixture.amount,
                Map.of("order_id",Long.toString(basis.fixture.f.order()),"order_change_operation_id",basis.fixture.operation.toString())));}
        catch(org.springframework.dao.DataAccessException fenced) { /* A rejected stale event is also safe. */ }
        assertThat(basis.business()).isEqualTo(before);closed();
    }
    @Test void directCaptureAndAdoptionCannotUseFailedCandidate(){
        failure();close();String before=basis.business();
        basis.rejected(()->basis.locked(()->jdbc.update("update orders set active_payment_id=(select new_payment_id from order_change_operations where id=?) where id=?",basis.fixture.operation,basis.fixture.f.order())));
        basis.rejected(()->basis.locked(()->jdbc.update("update payments set status='CAPTURED' where id=(select new_payment_id from order_change_operations where id=?)",basis.fixture.operation)));
        assertThat(basis.business()).isEqualTo(before);closed();
    }
    @Test void missingFailureEvidenceAndStaleVersionRemainStopped(){
        basis.intent();String before=basis.business();basis.rejected(this::close);
        assertThat(basis.business()).isEqualTo(before);
        basis.observe("THREE_DS_FAILURE",basis.document("THREE_DS_FAILURE"));basis.advance(0,"FAILURE_OBSERVED");
        basis.rejected(()->jdbc.queryForObject("select f125_failure_close_unapplied(?,?)",UUID.class,basis.control,0L));
        assertThat(count("order_edit_failure_business_closures")).isZero();unchangedOriginal();
    }
    com.example.ecsite.security.CustomUserDetails correctCustomer(){
        String name=jdbc.queryForObject("select actor_username from order_edit_admissions where id=?",String.class,basis.fixture.receipt);
        return new com.example.ecsite.security.CustomUserDetails(basis.fixture.f.customer(),name,"unused",true,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
    }
    void authenticateCustomer(){
        var principal=correctCustomer();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,"unused",principal.getAuthorities()));
    }
    OrderEditFailureClosureService closureService(){return new OrderEditFailureClosureService(jdbc.getDataSource(),gateway);}
    void mockVerifiedFailure(){
        when(gateway.observeOrderEditFailure(any(),any())).thenAnswer(call->{
            basis.fixture.outside();
            com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.Context context=call.getArgument(0);
            com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.LocalFacts facts=call.getArgument(1);
            var own=context.ownership();var fields=new LinkedHashMap<String,Object>();
            fields.put("id",own.flowId());fields.put("amount",own.amount());fields.put("currency","jpy");
            fields.put("capture_method","manual");fields.put("livemode",own.connection().mode().equals("LIVE"));
            fields.put("status","requires_payment_method");fields.put("last_payment_error",Map.of("code","three_d_secure_failed"));
            fields.put("amount_received",null);fields.put("amount_capturable",null);fields.put("next_action",Map.of());fields.put("canceled_at",null);
            fields.put("metadata",Map.of("order_id",Long.toString(own.orderId()),"order_change_operation_id",own.operationId().toString()));
            var now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            return new com.example.ecsite.payment.orderchange.ThreeDsFailureEvidenceVerifier().failure(context,facts,own.connection(),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(fields),
                new com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.Window(now,now),now);
        });
    }
    void savedControl(){basis.control=jdbc.queryForObject("select id from order_edit_failure_closure_controls where admission_id=?",UUID.class,basis.fixture.receipt);}
    @Test void actualClosureServiceVerifiesOnceAndReplaysSavedClosure(){
        authenticateCustomer();mockVerifiedFailure();var service=closureService();
        assertThat(service.terminate(basis.fixture.receipt)).isEqualTo(OrderEditFailureClosureService.Outcome.CLOSED_UNAPPLIED);
        assertThat(service.terminate(basis.fixture.receipt)).isEqualTo(OrderEditFailureClosureService.Outcome.CLOSED_UNAPPLIED);
        verify(gateway,times(1)).observeOrderEditFailure(any(),any());verifyNoMoreInteractions(gateway);
        savedControl();clearInvocations(gateway);closed();
    }
    @Test void explicitDiscardPostRequiresCsrfAndUsesActualClosureService() throws Exception {
        mockVerifiedFailure();String url="/orders/"+basis.fixture.f.order()+"/edit/"+basis.fixture.receipt+"/payment/discard-failed";
        basis.fixture.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(correctCustomer())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(gateway);
        basis.fixture.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(correctCustomer()))
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        verify(gateway,times(1)).observeOrderEditFailure(any(),any());verifyNoMoreInteractions(gateway);
        savedControl();clearInvocations(gateway);closed();
    }
    @Test void transportFailureHoldsWithoutIntentAndWrongOwnerIsDeniedBeforeProviderAccess(){
        authenticateCustomer();when(gateway.observeOrderEditFailure(any(),any())).thenThrow(new IllegalStateException("synthetic GET failure"));
        String before=basis.business();
        assertThat(closureService().terminate(basis.fixture.receipt)).isEqualTo(OrderEditFailureClosureService.Outcome.HELD);
        assertThat(basis.business()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_edit_failure_closure_controls where admission_id=?",Integer.class,basis.fixture.receipt)).isZero();
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,basis.fixture.f.product())).isEqualTo(heldStock);
        verify(gateway,times(1)).observeOrderEditFailure(any(),any());clearInvocations(gateway);
        var foreign=new com.example.ecsite.security.CustomUserDetails(basis.fixture.f.customer()+100000,"foreign","unused",true,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(foreign,"unused",foreign.getAuthorities()));
        assertThatThrownBy(()->closureService().terminate(basis.fixture.receipt)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(gateway);assertThat(basis.business()).isEqualTo(before);
    }
    record Fresh(OrderEditExecutionCoordinator coordinator,OrderEditApplicationPlan.Ownership owner,long createRequest) {}
    Fresh freshCandidate() {
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        UUID receipt=new OrderEditAdmissionService(jdbc.getDataSource()).submit(
            OrderEditAdmissionServicePostgresTest.command(f,3,UUID.randomUUID())).admissionId();
        var coordinator=new OrderEditExecutionCoordinator(jdbc.getDataSource());
        var claim=new OrderEditExecution.Claim(receipt,UUID.randomUUID(),0);
        var claimed=coordinator.claim(claim);
        assertThat(claimed.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        var owner=new OrderEditApplicationPlan.Ownership(receipt,claim.attemptId(),claimed.view().token(),claimed.view().version());
        var application=new OrderEditApplicationTransactions(jdbc.getDataSource(),LegacyOrderEvidenceEnrollmentPostgresTest.C);
        var prepared=application.prepare(owner);
        UUID operation=application.initializeReauthorization(prepared,MockOrderAuthorizationTransport.observe(prepared.expected()));
        var ready=basis.fixture.transactions.prepareForOrderEdit(f.order(),operation,owner);
        basis.fixture.f=f;basis.fixture.receipt=receipt;basis.fixture.operation=operation;
        basis.fixture.amount=jdbc.queryForObject("select after_amount from order_edit_admissions where id=?",Integer.class,receipt);
        basis.fixture.flow="pfw_failure_stop_"+operation.toString().replace("-","");
        basis.control=UUID.randomUUID();
        return new Fresh(coordinator,owner,ready.createRequest());
    }
    void suspend(Fresh fresh,String reason) {
        var owner=fresh.owner();
        assertThat(fresh.coordinator().resolve(new OrderEditExecution.Resolution(owner.admissionId(),UUID.randomUUID(),
            owner.attemptId(),owner.token(),owner.controlVersion(),OrderEditExecution.Disposition.UNKNOWN,reason)).outcome())
            .isEqualTo(OrderEditExecution.Outcome.RECORDED);
    }
    @Test void genuineUnknownCannotBeResolvedByFailureClosure(){
        var fresh=freshCandidate();
        var store=beans.getBean(com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore.class);
        var manager=beans.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        doReturn(basis.fixture.observation(basis.fixture.flow,
            "requires_payment_method",basis.fixture.amount,Map.of())).when(gateway).sendOrderChangeRequest(any());
        var observed=new OrderChangeRequestSender(store,gateway,manager).send(fresh.createRequest(),fresh.owner());
        basis.fixture.transactions.attachFlow(basis.fixture.f.order(),basis.fixture.operation,observed.id());
        basis.fixture.transactions.authorization(basis.fixture.f.order(),basis.fixture.operation,
            basis.fixture.observation(basis.fixture.flow,"requires_payment_method",basis.fixture.amount,
                Map.of("order_id",Long.toString(basis.fixture.f.order()),"order_change_operation_id",basis.fixture.operation.toString())));
        suspend(fresh,"TRANSPORT_COMMIT_UNKNOWN");failure();String before=basis.business();
        basis.rejected(this::close);assertThat(basis.business()).isEqualTo(before);
        assertThat(count("order_edit_failure_business_closures")).isZero();
        assertThat(count("order_edit_failure_waiting_resolutions")).isZero();
    }
    @Test void unobservedDispatchStopsFailureIntentAndClosure(){
        var fresh=freshCandidate();
        basis.fixture.transactions.attachFlow(basis.fixture.f.order(),basis.fixture.operation,basis.fixture.flow);
        suspend(fresh,"CREATE_DISPATCH_UNCONFIRMED");String before=basis.business();
        assertThat(jdbc.queryForObject("select state from order_change_request_dispatches where request_id=?",String.class,fresh.createRequest())).isEqualTo("READY");
        basis.rejected(basis::intent);basis.rejected(this::close);
        assertThat(basis.business()).isEqualTo(before);
        assertThat(count("order_edit_failure_business_closures")).isZero();
    }
    @Test void activeLeaseStopsFailureIntentAndClosure(){
        freshCandidate();String before=basis.business();
        assertThat(jdbc.queryForObject("select active_attempt_id is not null and lease_until>clock_timestamp() from order_edit_execution_controls where admission_id=?",Boolean.class,basis.fixture.receipt)).isTrue();
        basis.rejected(basis::intent);basis.rejected(this::close);
        assertThat(basis.business()).isEqualTo(before);
        assertThat(count("order_edit_failure_business_closures")).isZero();
    }
    @Test void stoppedRuntimeCannotCloseOrRestoreStock(){
        failure();basis.fixture.stopRuntime();String before=basis.business();
        try {
            basis.rejected(this::close);
            assertThat(basis.business()).isEqualTo(before);
            assertThat(count("order_edit_failure_business_closures")).isZero();
            assertThat(count("order_edit_failure_waiting_resolutions")).isZero();
        } finally {basis.fixture.resumeTestRuntime();}
    }
    @Test void rollbackAtomicallyRestoresCandidateReservationAndLeavesNoClosureCertificate(){
        failure();String before=basis.business();
        assertThatThrownBy(()->basis.tx.execute(s->{close();throw new IllegalStateException("synthetic rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(basis.business()).isEqualTo(before);
        assertThat(count("order_edit_failure_business_closures")).isZero();
        assertThat(count("order_edit_failure_waiting_resolutions")).isZero();
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,basis.fixture.f.product())).isEqualTo(heldStock);
        assertThat(close()).isEqualTo(basis.control);closed();
    }
}
