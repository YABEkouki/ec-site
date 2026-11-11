package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport;
import com.example.ecsite.service.order.evidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Existing guarded isolated PG17 runtime only. Provider calls are mocked, never HTTP. */
class OrderEditBrowserFlowPostgresTest {
    static PaymentSafetyExpandPostgresTest db;
    @BeforeAll static void database(){OrderEditApplicationPostgresTest.database();db=OrderEditApplicationPostgresTest.db;db.migrate("76");}
    @AfterAll static void cleanup(){OrderEditApplicationPostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    @AfterEach void actor(){SecurityContextHolder.clearContext();}
    void customer(long id){var p=new CustomUserDetails(id,"buyer","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,"unused",p.getAuthorities()));}
    PaymentGateway gateway(){var g=mock(PaymentGateway.class);when(g.observeOrderAuthorization(any())).thenAnswer(a->{
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return MockOrderAuthorizationTransport.observe(a.getArgument(0));});return g;}
    OrderEditBrowserService browser(PaymentGateway gateway){
        var factory=new StaticListableBeanFactory(Map.of("admissions",new OrderEditAdmissionService(db.source),
            "executions",new OrderEditExecutionService(db.source,gateway,LegacyOrderEvidenceEnrollmentPostgresTest.C)));
        return new OrderEditBrowserService(mock(OrderEditPreviewService.class),factory.getBeanProvider(LegacyOrderEvidenceEnrollment.class),db.jdbc,
            factory.getBeanProvider(OrderEditAdmissionService.class),factory.getBeanProvider(OrderEditExecutionService.class),factory.getBeanProvider(OrderEditCardService.class));
    }
    @Test void receiptExecutionReloadAndDuplicateSubmissionReuseOneApplyAndOneGet(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);customer(f.customer());
        var g=gateway();var browser=browser(g);var result=browser.submit(f.order(),c.submissionKey(),c.request(),c.confirmed());
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);
        assertThat(browser.result(f.order(),result.admissionId()).label()).isEqualTo("変更完了");
        assertThat(browser.findAdmission(f.order(),c.submissionKey())).isEqualTo(result.admissionId());
        var after=OrderEditAdmissionServicePostgresTest.business(f);
        assertThat(browser.submit(f.order(),c.submissionKey(),c.request(),c.confirmed()).admissionId()).isEqualTo(result.admissionId());
        browser.result(f.order(),result.admissionId());browser.continueAdmission(f.order(),result.admissionId());
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(after);
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isOne();
        verify(g,times(1)).observeOrderAuthorization(any());verifyNoMoreInteractions(g);
    }
    @Test void acceptedReceiptContinuationUsesOriginalCommitProofWithoutFreshPreview(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        var admitted=new OrderEditAdmissionService(db.source).submit(c);
        assertThat(admitted.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);
        var proof=db.jdbc.queryForMap("select e.original_commit_at,a.deadline_at from order_edit_admissions a join order_edit_admission_evidences e on e.admission_id=a.id where a.id=?",admitted.admissionId());
        assertThat(db.jdbc.queryForObject("select e.original_commit_at<a.deadline_at from order_edit_admissions a join order_edit_admission_evidences e on e.admission_id=a.id where a.id=?",Boolean.class,admitted.admissionId())).isTrue();
        customer(f.customer());var g=gateway();var browser=browser(g);browser.continueAdmission(f.order(),admitted.admissionId());
        assertThat(browser.result(f.order(),admitted.admissionId()).label()).isEqualTo("変更完了");
        assertThat(db.jdbc.queryForMap("select e.original_commit_at,a.deadline_at from order_edit_admissions a join order_edit_admission_evidences e on e.admission_id=a.id where a.id=?",admitted.admissionId())).isEqualTo(proof);
        verify(g,times(1)).observeOrderAuthorization(any());verifyNoMoreInteractions(g);
    }
    @Test void stoppedRuntimeNeverAdmitsOrCallsProvider(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);customer(f.customer());var before=OrderEditAdmissionServicePostgresTest.business(f);
        db.jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        var g=gateway();var browser=browser(g);assertThat(browser.submissionAvailable()).isFalse();
        assertThat(browser.submit(f.order(),c.submissionKey(),c.request(),c.confirmed()).outcome()).isEqualTo(OrderEditAdmissionService.Outcome.RUNTIME_UNAVAILABLE);
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);verifyNoInteractions(g);
    }
    @Test void foreignOrderAndReceiptAreDeniedBeforeExecution(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);var r=new OrderEditAdmissionService(db.source).submit(c);
        customer(f.customer()+100000);var g=gateway();var browser=browser(g);
        assertThatThrownBy(()->browser.result(f.order(),r.admissionId())).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(()->browser.continueAdmission(f.order(),r.admissionId())).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(()->browser.submit(f.order(),c.submissionKey(),c.request(),c.confirmed())).isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(g);
    }
    @Test void lateNewSubmissionIsRejectedWithoutProviderCalls(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(true);var c=OrderEditAdmissionServicePostgresTest.command(f);customer(f.customer());var g=gateway();var browser=browser(g);
        var r=browser.submit(f.order(),c.submissionKey(),c.request(),c.confirmed());
        assertThat(r.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.REJECTED_LATE);
        assertThat(browser.result(f.order(),r.admissionId()).label()).isEqualTo("未適用終了");verifyNoInteractions(g);
    }

    @Test void initialQueuedReceiptAutoContinuationIsOwnerBoundReadOnlyAndIdempotent(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        var r=new OrderEditAdmissionService(db.source).submit(c);customer(f.customer());var g=gateway();var browser=browser(g);
        var before=OrderEditAdmissionServicePostgresTest.business(f);
        assertThat(browser.result(f.order(),r.admissionId()).nextStep().action()).isEqualTo(OrderEditBrowserService.NextAction.AUTO_CONTINUE_ALLOWED);
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);verifyNoInteractions(g);
        browser.continueAutomatically(f.order(),r.admissionId());
        assertThat(browser.result(f.order(),r.admissionId()).label()).isEqualTo("変更完了");
        assertThat(browser.result(f.order(),r.admissionId()).autoContinueAllowed()).isFalse();
        browser.continueAutomatically(f.order(),r.admissionId());verify(g,times(1)).observeOrderAuthorization(any());verifyNoMoreInteractions(g);
        assertThat(db.jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isOne();
    }
    @Test void initializedControlAndRuntimeStopDisableAutomaticExecution(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        var r=new OrderEditAdmissionService(db.source).submit(c);customer(f.customer());var g=gateway();var browser=browser(g);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.source)).execute(s->{
            db.jdbc.queryForObject("select f125_execution_initialize(?)",Object.class,r.admissionId());return null;
        });
        assertThat(browser.result(f.order(),r.admissionId()).autoContinueAllowed()).isFalse();
        var claim=new OrderEditExecutionCoordinator(db.source).claim(new OrderEditExecution.Claim(r.admissionId(),UUID.randomUUID(),0));
        assertThat(claim.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        assertThat(claim.view().activeAttemptId()).isNotNull();
        assertThat(browser.result(f.order(),r.admissionId()).nextStep().action()).isEqualTo(OrderEditBrowserService.NextAction.OBSERVE_ONLY);
        browser.continueAutomatically(f.order(),r.admissionId());verifyNoInteractions(g);
        var resolved=new OrderEditExecutionCoordinator(db.source).resolve(new OrderEditExecution.Resolution(r.admissionId(),UUID.randomUUID(),
            claim.view().activeAttemptId(),claim.view().token(),claim.view().version(),OrderEditExecution.Disposition.REQUEUE,"AUTO_GUARD_REQUEUE"));
        assertThat(resolved.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        assertThat(new OrderEditExecutionService(db.source,g,LegacyOrderEvidenceEnrollmentPostgresTest.C).executeInitial(r.admissionId()).status())
            .isEqualTo(OrderEditExecutionService.Status.BLOCKED);verifyNoInteractions(g);
        db.jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        assertThat(browser.result(f.order(),r.admissionId()).autoContinueAllowed()).isFalse();
    }

    @Test void financialSnapshotChangeDisablesAutomaticExecutionWithoutRebaselining(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);var c=OrderEditAdmissionServicePostgresTest.command(f);
        var r=new OrderEditAdmissionService(db.source).submit(c);customer(f.customer());var g=gateway();var browser=browser(g);
        var original=db.jdbc.queryForObject("select encode(financial_digest,'hex') from order_edit_admissions where id=?",String.class,r.admissionId());
        db.jdbc.update("update payments set status='CANCELLED' where id=?",f.payment());
        assertThat(browser.result(f.order(),r.admissionId()).autoContinueAllowed()).isFalse();
        browser.continueAutomatically(f.order(),r.admissionId());verifyNoInteractions(g);
        assertThat(db.jdbc.queryForObject("select encode(financial_digest,'hex') from order_edit_admissions where id=?",String.class,r.admissionId())).isEqualTo(original);
    }
}
