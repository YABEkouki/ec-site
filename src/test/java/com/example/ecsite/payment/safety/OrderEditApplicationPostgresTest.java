package com.example.ecsite.payment.safety;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.payjp.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** Atomic RETAIN_AUTH tests, exclusively on the existing isolated PostgreSQL 17 harness. */
class OrderEditApplicationPostgresTest {
    static PaymentSafetyExpandPostgresTest db;
    @BeforeAll static void database(){
        OrderEditExecutionCoordinatorPostgresTest.database();db=OrderEditExecutionCoordinatorPostgresTest.db;
        db.migrate("74");OrderEditApplicationPlanPostgresTest.db=db;
    }
    @AfterAll static void cleanup(){OrderEditExecutionCoordinatorPostgresTest.cleanup();}
    @Test void atomicApplicationWriterIsAvailable(){
        assertThat(db.jdbc.queryForObject("select count(*) from pg_proc where pronamespace=current_schema()::regnamespace and proname='f125_edit_application_begin'",Integer.class)).isEqualTo(1);
    }

    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    OrderEditApplicationPlanPostgresTest helper(){return new OrderEditApplicationPlanPostgresTest();}
    PaymentGateway gateway(java.util.function.Function<OrderAuthorizationObservation.Expected,OrderAuthorizationObservation> action){
        var g=mock(PaymentGateway.class);when(g.observeOrderAuthorization(any())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return action.apply(call.getArgument(0));});return g;
    }
    OrderEditExecutionService service(PaymentGateway g){return new OrderEditExecutionService(db.source,g,LegacyOrderEvidenceEnrollmentPostgresTest.C);}
    UUID accepted(OrderEditAdmissionServicePostgresTest.Fixture f,List<Line> lines){return helper().accepted(f,lines);}
    void assertDone(UUID id,OrderEditAdmissionServicePostgresTest.Fixture f,int amount){
        assertThat(db.jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("COMPLETED");
        assertThat(db.jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,f.order())).isEqualTo(amount);
        assertThat(db.jdbc.queryForObject("select amount from payments where id=?",Integer.class,f.payment())).isEqualTo(6000);
        assertThat(db.jdbc.queryForObject("select count(*) from payment_transactions where payment_id=?",Integer.class,f.payment())).isEqualTo(1);
        var read=OrderEditAdmissionServicePostgresTest.tx(()->new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(f.order()));
        assertThat(read.state()).isEqualTo(JdbcOrderPaymentEvidenceReader.State.READ);assertThat(read.decision().locallyVerified()).isTrue();
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition='APPLIED'",Integer.class,id)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select active_attempt_id is null from order_edit_execution_controls where admission_id=?",Boolean.class,id)).isTrue();
    }
    @Test void additionDecreaseAndShippingBoundaryApplyAtomicallyAndReplayWithoutGet(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=helper().product(f,1000);
        UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1),new Line(null,p,1)));
        var g=gateway(MockOrderAuthorizationTransport::observe);var s=service(g);var result=s.execute(id);
        assertThat(result.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);assertDone(id,f,4550);
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(101);
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,p)).isEqualTo(99);
        long formal=db.jdbc.queryForObject("select id from order_items where order_id=? and product_id=?",Long.class,f.order(),p);
        assertThat(formal).isNotEqualTo(f.item());
        assertThat(s.execute(id)).isEqualTo(result);verify(g,times(1)).observeOrderAuthorization(any());
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void deletionOrMixedExistingQuantities(boolean increase){
        var f=helper().twoLines();UUID id=accepted(f,List.of(new Line(f.item(),f.product(),increase?2:1)));
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertDone(id,f,increase?6000:3550);
        assertThat(db.jdbc.queryForObject("select count(*) from order_items where order_id=?",Integer.class,f.order())).isEqualTo(1);
    }
    @Test void replacementAtEqualAuthorizationUsesRealNewId(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=helper().product(f,6000);UUID id=accepted(f,List.of(new Line(f.item(),p,1)));
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);assertDone(id,f,6000);
        assertThat(db.jdbc.queryForObject("select count(*) from order_items where id=?",Integer.class,f.item())).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from order_content_change_items where history_id=(select application_history_id from order_edit_execution_attempt_results where admission_id=? and disposition='APPLIED')",Integer.class,id)).isEqualTo(2);
    }
    @Test void insufficientStockRollsBackEveryBusinessWrite(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=helper().product(f,1000);UUID id=accepted(f,List.of(new Line(null,p,1)));
        db.jdbc.update("update products set stock=0 where id=?",p);String before=OrderEditAdmissionServicePostgresTest.business(f);
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_execution_attempt_results where admission_id=? and disposition='APPLIED'",Integer.class,id)).isZero();
    }
    @Test void getUnknownParksWithoutApplyingOrSendingPosts(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));String before=OrderEditAdmissionServicePostgresTest.business(f);
        var g=gateway(e->{throw new IllegalStateException("timeout");});
        assertThat(service(g).execute(id).status()).isEqualTo(OrderEditExecutionService.Status.HELD);
        assertThat(db.jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("RECOVERY_PENDING");
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);verify(g).observeOrderAuthorization(any());verifyNoMoreInteractions(g);
    }
    @ParameterizedTest @ValueSource(strings={"payment","preparing","cancelled","revision"})
    void changesDuringGetNeverApply(String kind){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));
        var g=gateway(e->{var obs=MockOrderAuthorizationTransport.observe(e);
            switch(kind){case "payment"->db.jdbc.update("update payments set status='CANCELLED' where id=?",f.payment());
              case "preparing"->db.jdbc.update("update orders set shipping_preparation_started_at=localtimestamp where id=?",f.order());
              case "cancelled"->db.jdbc.update("update orders set status='CANCELLED' where id=?",f.order());
              default->db.jdbc.update("update order_items set product_name='changed' where id=?",f.item());}
            return obs;});
        assertThat(service(g).execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
        assertThat(db.jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
    }
    @Test void oldTokenAndUnauthorizedTransitionsRemainRejected(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{db.jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);db.jdbc.update("update order_edit_admissions set execution_state='APPLYING',version=version+1 where id=?",id);return null;}));
        var control=new OrderEditExecutionCoordinator(db.source);var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);var c=control.claim(claim);
        var apply=new OrderEditApplicationTransactions(db.source,LegacyOrderEvidenceEnrollmentPostgresTest.C);
        assertThatThrownBy(()->apply.prepare(new OrderEditApplicationPlan.Ownership(id,claim.attemptId(),999,c.view().version()))).isInstanceOf(RuntimeException.class);
    }
    @Test void twoExecutionsNeverApplyTwice() throws Exception {
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var g=gateway(e->{entered.countDown();try{assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException x){throw new RuntimeException(x);}return MockOrderAuthorizationTransport.observe(e);});
        var pool=Executors.newSingleThreadExecutor();try{var first=pool.submit(()->service(g).execute(id));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service(g).execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);assertDone(id,f,3550);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void directApplicationCommitsVerifiableEvidence(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));
        var control=new OrderEditExecutionCoordinator(db.source);var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);var c=control.claim(claim);
        assertThat(c.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        var app=new OrderEditApplicationTransactions(db.source,LegacyOrderEvidenceEnrollmentPostgresTest.C);
        var p=app.prepare(new OrderEditApplicationPlan.Ownership(id,claim.attemptId(),c.view().token(),c.view().version()));
        var applied=app.apply(p,MockOrderAuthorizationTransport.observe(p.expected()));
        assertThat(applied.historyId()).isPositive();assertDone(id,f,3550);
    }

    @Test void mixedTaxAndFreeShippingUseSavedCandidateAmounts(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=helper().product(f,2000);
        db.jdbc.update("update products set tax_category_id=(select id from tax_categories where code='REDUCED') where id=?",p);
        UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1),new Line(null,p,1)));
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);assertDone(id,f,5000);
        assertThat(db.jdbc.queryForObject("select tax_amount from orders where id=?",Integer.class,f.order())).isEqualTo(420);
        assertThat(db.jdbc.queryForObject("select tax_rate from order_items where order_id=? and product_id=?",java.math.BigDecimal.class,f.order(),p)).isEqualByComparingTo("8.00");
    }
    @Test void sameProductDifferentSnapshotHasZeroNetStockAndTwoDistinctRows(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);db.jdbc.update("update products set price=3000 where id=?",f.product());
        UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1),new Line(null,f.product(),1)));
        assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);assertDone(id,f,6000);
        assertThat(db.jdbc.queryForObject("select count(*) from order_items where order_id=?",Integer.class,f.order())).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("select count(*) from stock_movements where order_id=?",Integer.class,f.order())).isZero();
        assertThat(db.jdbc.queryForObject("select stock from products where id=?",Integer.class,f.product())).isEqualTo(100);
    }
    @Test void noOpIsRejectedBeforeAdmissionAndDoesNotCreateWork(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);String before=OrderEditAdmissionServicePostgresTest.business(f);
        var request=new Request(f.order(),0,List.of(new Line(f.item(),f.product(),2)));
        var candidate=OrderEditAdmissionServicePostgresTest.tx(()->{
            var r=new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(f.order());
            var now=db.jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class);
            var result=new OrderEditCandidateCalculator().calculateForAdmission(OrderEditCandidateSources.context(r,f.customer(),now),request,List.of());
            assertThat(result.route()).isEqualTo(OrderChangeRoutePolicy.Route.NO_CHANGE);return result.candidate();
        });
        assertThat(OrderEditAdmissionServicePostgresTest.service().submit(new OrderEditAdmissionService.Command(f.customer(),UUID.randomUUID(),request,candidate)).outcome()).isNotEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);
        assertThat(db.jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isZero();
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
    }
    @Test void expiredGetReceiptNeverApplies(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));var ticks=new AtomicLong();String before=OrderEditAdmissionServicePostgresTest.business(f);
        var g=gateway(e->{var o=MockOrderAuthorizationTransport.observe(e,java.time.Clock.systemUTC(),ticks::get);ticks.set(6_000_000_000L);return o;});
        assertThat(service(g).execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
    }
    @Test void changedNewOfferDuringGetNeverRepricesOrApplies(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=helper().product(f,1000);UUID id=accepted(f,List.of(new Line(null,p,1)));String before=OrderEditAdmissionServicePostgresTest.business(f);
        var g=gateway(e->{var o=MockOrderAuthorizationTransport.observe(e);db.jdbc.update("update products set price=1100 where id=?",p);return o;});
        assertThat(service(g).execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
    }
    @Test void committedApplyWithLostResponseIsRecoveredWithoutAnotherGet(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));var commits=new AtomicInteger();
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.source){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(commits.incrementAndGet()==2)throw new org.springframework.transaction.TransactionSystemException("lost apply commit response");}
        };
        var g=gateway(MockOrderAuthorizationTransport::observe);var s=new OrderEditExecutionService(db.source,g,LegacyOrderEvidenceEnrollmentPostgresTest.C,manager);
        var result=s.execute(id);assertThat(result.status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);assertDone(id,f,3550);
        assertThat(s.recover(id)).isEqualTo(result);assertThat(s.execute(id)).isEqualTo(result);verify(g,times(1)).observeOrderAuthorization(any());
    }
    @Test void postCompletionMutationEvenAfterImmediateConstraintsRollsBack(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));var commits=new AtomicInteger();String before=OrderEditAdmissionServicePostgresTest.business(f);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.source){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                if(commits.incrementAndGet()==2){db.jdbc.execute("set constraints all immediate");db.jdbc.update("update order_items set quantity=quantity+1,subtotal=subtotal+price where order_id=?",f.order());}
                super.doCommit(status);
            }
        };
        var s=new OrderEditExecutionService(db.source,gateway(MockOrderAuthorizationTransport::observe),LegacyOrderEvidenceEnrollmentPostgresTest.C,manager);
        assertThat(s.execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
    }
    @Test void migrationValidateAndRuntimeStopped(){
        org.flywaydb.core.Flyway.configure().dataSource(db.source).defaultSchema(db.schema).target("74").load().validate();
        db.jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        var f=OrderEditAdmissionServicePostgresTest.fixture(false); // Fixture is ordinary order creation, no runtime activation.
        assertThat(db.jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");
    }

    @Test void invalidHistoryDigestCannotCompleteEvenWithValidSnapshotHash(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID id=accepted(f,List.of(new Line(f.item(),f.product(),1)));String before=OrderEditAdmissionServicePostgresTest.business(f);
        // Simulates a faulty writer, without disabling any production constraint or guard.
        db.jdbc.execute("create function test_corrupt_f2_digest() returns trigger language plpgsql as $$ begin NEW.evidence_digest=decode(repeat('00',32),'hex');return NEW;end;$$");
        db.jdbc.execute("create trigger zz_test_corrupt_f2_digest before insert on order_content_change_histories for each row execute function test_corrupt_f2_digest()");
        try{assertThat(service(gateway(MockOrderAuthorizationTransport::observe)).execute(id).status()).isNotEqualTo(OrderEditExecutionService.Status.COMPLETED);
            assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
        }finally{db.jdbc.execute("drop trigger zz_test_corrupt_f2_digest on order_content_change_histories");db.jdbc.execute("drop function test_corrupt_f2_digest()");}
    }
}