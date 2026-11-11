package com.example.ecsite.payment.safety;

import org.junit.jupiter.api.*;
import java.util.UUID;
import com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport;
import com.example.ecsite.service.order.evidence.*;
import static org.assertj.core.api.Assertions.*;

/** Existing isolated PG17 harness; no real provider transport. */
class OrderEditReauthorizationPostgresTest {
    static PaymentSafetyExpandPostgresTest db;
    @BeforeAll static void database(){OrderEditExecutionCoordinatorPostgresTest.database();db=OrderEditExecutionCoordinatorPostgresTest.db;db.migrate("75");}
    @AfterAll static void cleanup(){OrderEditExecutionCoordinatorPostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    @Test void initializesExplicitOwnedOperationAndHoldsPositiveProductPlan(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        var command=OrderEditAdmissionServicePostgresTest.command(f,3,UUID.randomUUID());
        var admission=OrderEditAdmissionServicePostgresTest.service().submit(command);
        assertThat(admission.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);
        UUID id=admission.admissionId();
        String baseline=db.jdbc.queryForObject("select unapplied_baseline::text from order_edit_admissions where id=?",String.class,id);
        var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);
        var claimed=new OrderEditExecutionCoordinator(db.source).claim(claim);
        assertThat(claimed.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        var application=new OrderEditApplicationTransactions(db.source,LegacyOrderEvidenceEnrollmentPostgresTest.C);
        var prepared=application.prepare(new OrderEditApplicationPlan.Ownership(id,claim.attemptId(),claimed.view().token(),claimed.view().version()));
        UUID operation=application.initializeReauthorization(prepared,MockOrderAuthorizationTransport.observe(prepared.expected()));
        assertThat(db.jdbc.queryForObject("select f125_reauth_is_owned(?,?)",Boolean.class,f.order(),operation)).isTrue();
        assertThat(db.jdbc.queryForObject("select execution_route='REAUTHORIZE' and execution_version=1 and state='RESERVED' and application_xid is null and new_payment_id is null from order_change_operations where id=?",Boolean.class,operation)).isTrue();
        assertThat(db.jdbc.queryForObject("select reservation_state='HELD' and reserve_quantity=1 and release_quantity=0 and operation_id=? and hold_movement_id is not null from order_edit_inventory_plans where admission_id=? and product_id=?",Boolean.class,operation,id,f.product())).isTrue();
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(99);
        assertThat(db.jdbc.queryForObject("select unapplied_baseline::text from order_edit_admissions where id=?",String.class,id)).isEqualTo(baseline);
        OrderEditAdmissionServicePostgresTest.tx(()->{db.jdbc.queryForObject("select f125_reauth_assert_current(?)",Object.class,id);return null;});
        assertThat(db.jdbc.queryForObject("select f125_reauth_conclusive(?)",Boolean.class,id)).isTrue();
        assertThat(db.jdbc.queryForObject("select count(*) from order_change_financial_requests where operation_id=?",Integer.class,operation)).isZero();
        assertThat(db.jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
        // A caller cannot manufacture release proof by merely changing the operation phase.
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            db.jdbc.queryForObject("select f125_reauth_lock(?,?)",Object.class,f.order(),operation);
            db.jdbc.update("update order_change_operations set state='ABORTED',version=version+1 where id=?",operation);
            new OrderEditReauthorizationBridge(db.jdbc).release(f.order(),operation);return null;
        }));
        assertThat(db.jdbc.queryForObject("select reservation_state from order_edit_inventory_plans where admission_id=? and product_id=?",String.class,id,f.product())).isEqualTo("HELD");
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(99);
    }
    @Test void rolledBackInitializationCanResumeOnlyFromExactUnchangedBasis(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        var admission=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f,3,UUID.randomUUID()));
        assertThat(admission.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);
        UUID id=admission.admissionId();var coordinator=new OrderEditExecutionCoordinator(db.source);
        var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);var claimed=coordinator.claim(claim);
        assertThat(claimed.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        var application=new OrderEditApplicationTransactions(db.source,LegacyOrderEvidenceEnrollmentPostgresTest.C);
        var prepared=application.prepare(new OrderEditApplicationPlan.Ownership(id,claim.attemptId(),claimed.view().token(),claimed.view().version()));
        db.jdbc.update("update products set stock=0 where id=?",f.product());
        assertThatThrownBy(()->application.initializeReauthorization(prepared,MockOrderAuthorizationTransport.observe(prepared.expected()))).isInstanceOf(RuntimeException.class);
        assertThat(db.jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_inventory_plans where admission_id=? and reservation_state is not null",Integer.class,id)).isZero();
        var suspended=coordinator.resolve(new OrderEditExecution.Resolution(id,UUID.randomUUID(),claim.attemptId(),claimed.view().token(),claimed.view().version(),OrderEditExecution.Disposition.UNKNOWN,"INIT_COMMIT_UNCONFIRMED"));
        assertThat(suspended.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        assertThat(suspended.view().state()).isEqualTo(OrderEditExecution.State.RECOVERY_PENDING);
        assertThat(db.jdbc.queryForObject("select f125_reauth_conclusive(?)",Boolean.class,id)).isTrue();
        OrderEditAdmissionServicePostgresTest.tx(()->{db.jdbc.queryForObject("select f125_reauth_resume(?,?,?)",Object.class,id,UUID.randomUUID(),suspended.view().version());return null;});
        assertThat(db.jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("QUEUED");
        assertThat(db.jdbc.queryForObject("select fencing_token from order_edit_execution_controls where admission_id=?",Long.class,id)).isGreaterThan(claimed.view().token());
        assertThat(db.jdbc.queryForObject("select count(*) from order_change_operations where order_id=?",Integer.class,f.order())).isZero();
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isZero();
        db.jdbc.update("update order_items set product_name='unowned mutation' where id=?",f.item());
        assertThat(db.jdbc.queryForObject("select f125_reauth_conclusive(?)",Boolean.class,id)).isFalse();
    }
    @Test void reauthorizationEntryRequiresDedicatedOwnedOperation(){
        assertThat(db.jdbc.queryForObject("select count(*) from pg_proc where pronamespace=current_schema()::regnamespace and proname='f125_reauth_initialize'",Integer.class)).isEqualTo(1);
    }
}
