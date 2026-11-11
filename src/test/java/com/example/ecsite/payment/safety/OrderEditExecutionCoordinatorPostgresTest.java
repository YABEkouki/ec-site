package com.example.ecsite.payment.safety;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.evidence.OrderEditExecution.*;
import static org.assertj.core.api.Assertions.*;

/** Independent PG17 commit-tracking fixture, no gateway/worker/applier or development DB. */
class OrderEditExecutionCoordinatorPostgresTest {
    static PaymentSafetyExpandPostgresTest db;static JdbcTemplate jdbc;
    @BeforeAll static void database(){OrderEditAdmissionServicePostgresTest.database();db=OrderEditAdmissionServicePostgresTest.db;jdbc=db.jdbc;db.migrate("72");}
    @AfterAll static void cleanup(){OrderEditAdmissionServicePostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    OrderEditExecutionCoordinator service(){return new OrderEditExecutionCoordinator(db.source);}
    UUID receipt(){var f=OrderEditAdmissionServicePostgresTest.fixture(false);var result=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);return result.admissionId();}
    void initialize(UUID id,String lease){OrderEditAdmissionServicePostgresTest.tx(()->{jdbc.queryForObject("select f125_execution_initialize(?,interval '10 minutes',5,?::interval,interval '0')",Object.class,id,lease);return null;});}
    Claim command(UUID id,long version){return new Claim(id,UUID.randomUUID(),version);}
    Resolution resolution(Claim c,Disposition disposition){return new Resolution(c.admissionId(),UUID.randomUUID(),c.attemptId(),1,1,disposition,"TEST_RESULT");}
    void assertGate(UUID id){assertThat(jdbc.queryForObject("select closed_at is null from order_edit_admissions where id=?",Boolean.class,id)).isTrue();}
    @Test void normalClaimAndReplayNeverMintSecondToken(){
        UUID id=receipt();var c=command(id,0);var first=service().claim(c);assertThat(first.outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(first.view().state()).isEqualTo(State.OBSERVING);assertThat(first.view().token()).isEqualTo(1L);
        assertThat(first.view().attempts()).singleElement().satisfies(a->{assertThat(a.id()).isEqualTo(c.attemptId());assertThat(a.leaseUntil()).isAfter(a.claimedAt());});
        assertThat(service().claim(c).view()).isEqualTo(first.view());assertThat(service().recoverClaim(c).outcome()).isEqualTo(Outcome.OBSERVED);
        assertThat(service().claim(new Claim(id,c.attemptId(),1)).outcome()).isEqualTo(Outcome.BLOCKED);assertGate(id);
    }
    @Test void casMismatchAndSecondClaimCannotReplaceOwner(){
        UUID id=receipt();assertThat(service().claim(command(id,5)).outcome()).isEqualTo(Outcome.BLOCKED);
        var c=command(id,0);assertThat(service().claim(c).outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(service().claim(command(id,0)).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(service().recoverClaim(c).view().attempts()).hasSize(1);
    }
    @Test void exactResultReplayPreservesCountVersionAndReason(){
        UUID id=receipt();initialize(id,"1 minute");var c=command(id,0);service().claim(c);var r=resolution(c,Disposition.REQUEUE);
        var first=service().resolve(r);assertThat(first.outcome()).isEqualTo(Outcome.RECORDED);assertThat(first.view().state()).isEqualTo(State.QUEUED);
        assertThat(service().resolve(r).view()).isEqualTo(first.view());assertThat(service().recoverResult(r).outcome()).isEqualTo(Outcome.OBSERVED);
        var changed=new Resolution(id,r.resultId(),c.attemptId(),1,1,Disposition.REVIEW,"DIFFERENT_RESULT");
        assertThat(service().resolve(changed).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(service().recoverResult(changed).outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(first.view().results()).hasSize(1);assertGate(id);
    }
    @Test void retiredTokenCannotUpdateNewAttempt(){
        UUID id=receipt();initialize(id,"1 minute");var old=command(id,0);service().claim(old);service().resolve(resolution(old,Disposition.REQUEUE));
        var next=command(id,2);var claimed=service().claim(next);assertThat(claimed.view().token()).isEqualTo(3L);
        assertThat(service().resolve(new Resolution(id,UUID.randomUUID(),old.attemptId(),1,3,Disposition.REQUEUE,"OLD_WORKER")).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(service().recoverClaim(next).view().activeAttemptId()).isEqualTo(next.attemptId());
    }
    @Test void expiredLeaseOnlyParksAndFencesUnresolvedAttempt() throws Exception {
        UUID id=receipt();initialize(id,"30 milliseconds");var c=command(id,0);service().claim(c);Thread.sleep(50);
        assertThat(service().resolve(resolution(c,Disposition.REQUEUE)).outcome()).isEqualTo(Outcome.BLOCKED);
        for(String action:List.of("REQUEUE","CLOSE","FAIL","REVIEW"))db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_recover(?,1,?,'LEASE_ONLY_ACTION')",Object.class,id,action);return null;
        }));
        var recovery=new Expired(id,UUID.randomUUID(),1);var parked=service().recoverExpired(recovery);
        assertThat(parked.outcome()).isEqualTo(Outcome.RECORDED);assertThat(parked.view().state()).isEqualTo(State.RECOVERY_PENDING);
        assertThat(parked.view().activeAttemptId()).isNull();assertThat(parked.view().token()).isEqualTo(2L);
        assertThat(service().recoverExpired(recovery).view()).isEqualTo(parked.view());
        assertThat(service().recoverExpiredResult(recovery).outcome()).isEqualTo(Outcome.OBSERVED);
        assertThat(service().claim(command(id,2)).outcome()).isEqualTo(Outcome.BLOCKED);
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{jdbc.queryForObject("select f125_execution_recover(?,2,'REQUEUE','UNPROVEN_RETRY')",Object.class,id);return null;}));
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{jdbc.queryForObject("select f125_admission_close_unapplied(?,'LEASE_ONLY_CLOSE')",Object.class,id);return null;}));
        assertGate(id);
    }
    @Test void uncertainResultIsNotAnUnappliedResolution(){
        UUID id=receipt();var c=command(id,0);service().claim(c);var result=service().resolve(resolution(c,Disposition.UNKNOWN));
        assertThat(result.outcome()).isEqualTo(Outcome.RECORDED);assertThat(result.view().state()).isEqualTo(State.RECOVERY_PENDING);
        assertThat(service().claim(command(id,2)).outcome()).isEqualTo(Outcome.BLOCKED);assertGate(id);
    }
    @Test void reviewCannotAutomaticallyRecoverOrRequeue(){
        UUID id=receipt();var c=command(id,0);service().claim(c);var result=service().resolve(resolution(c,Disposition.REVIEW));
        assertThat(result.view().state()).isEqualTo(State.REVIEW_REQUIRED);
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{jdbc.queryForObject("select f125_execution_recover(?,2,'REQUEUE','AUTOMATIC_REVIEW_RETRY')",Object.class,id);return null;}));
        assertThat(service().recoverExpired(new Expired(id,UUID.randomUUID(),2)).outcome()).isEqualTo(Outcome.BLOCKED);assertGate(id);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void lostClaimCommitResponseIsReconciledWithoutGuessingRollback(boolean committed){
        UUID id=receipt();var c=command(id,0);var faulty=new OrderEditExecutionCoordinator(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(1,committed));
        assertThat(faulty.claim(c).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        var recovered=service().recoverClaim(c);assertThat(recovered.outcome()).isEqualTo(committed?Outcome.OBSERVED:Outcome.NOT_OBSERVED);
        if(committed){assertThat(service().claim(c).view().attempts()).hasSize(1);}else{assertThat(recovered.view().version()).isNull();}
        assertGate(id);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void lostResultCommitResponseUsesImmutableResultId(boolean committed){
        UUID id=receipt();var c=command(id,0);service().claim(c);var r=resolution(c,Disposition.REVIEW);
        var faulty=new OrderEditExecutionCoordinator(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(1,committed));
        assertThat(faulty.resolve(r).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        var read=service().recoverResult(r);assertThat(read.outcome()).isEqualTo(committed?Outcome.OBSERVED:Outcome.NOT_OBSERVED);
        assertThat(read.view().state()).isEqualTo(committed?State.REVIEW_REQUIRED:State.OBSERVING);assertGate(id);
    }
    @Test void stoppedRuntimeRefusesWritesButAllowsFactObservation(){
        UUID id=receipt();var c=command(id,0);service().claim(c);
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        assertThat(service().resolve(resolution(c,Disposition.UNKNOWN)).outcome()).isEqualTo(Outcome.RUNTIME_UNAVAILABLE);
        assertThat(service().recoverClaim(c).outcome()).isEqualTo(Outcome.OBSERVED);assertGate(id);
    }
    @Test void runtimeGenerationChangeCannotAdoptOldAttempt(){
        UUID id=receipt();var c=command(id,0);service().claim(c);UUID generation=UUID.randomUUID();
        jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",generation);
        jdbc.update("update order_edit_runtime_control set generation_id=?,version=version+1 where id=1",generation);
        assertThat(service().resolve(resolution(c,Disposition.REQUEUE)).outcome()).isEqualTo(Outcome.RUNTIME_UNAVAILABLE);
        assertThat(service().recoverClaim(c).view().token()).isEqualTo(1L);assertGate(id);
    }
    @Test void financialChangeCannotBecomeNewBaselineButCanBeSuspended(){
        UUID id=receipt();var c=command(id,0);service().claim(c);
        byte[] digest=jdbc.queryForObject("select financial_digest from order_edit_admissions where id=?",byte[].class,id);
        jdbc.update("update payments set status='CANCELLED' where id=(select base_payment_id from order_edit_admissions where id=?)",id);
        assertThat(service().resolve(resolution(c,Disposition.REQUEUE)).outcome()).isEqualTo(Outcome.BLOCKED);
        var result=service().resolve(resolution(c,Disposition.UNKNOWN));assertThat(result.view().state()).isEqualTo(State.RECOVERY_PENDING);
        assertThat(jdbc.queryForObject("select financial_digest from order_edit_admissions where id=?",byte[].class,id)).isEqualTo(digest);assertGate(id);
    }
    @Test void queuedReceiptMayHaveNoControlButCannotStartWithoutOwnership(){
        UUID id=receipt();
        assertThat(jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_controls where admission_id=?",Integer.class,id)).isZero();
        for(String state:List.of("OBSERVING","APPLYING","COMPLETED")) rejectState(id,state,false);
        initialize(id,"1 minute");
        rejectState(id,"OBSERVING",false);
    }
    void rejectState(UUID id,String state,boolean immediate){
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
            if(immediate)jdbc.execute("set constraints all immediate");
            jdbc.update("update order_edit_admissions set execution_state=?,reason_code='UNBACKED_STATE',version=version+1 where id=?",state,id);
            return null;
        }));
    }
    @Test void earlyConstraintCheckCannotAllowUnownedExecution(){
        UUID id=receipt();rejectState(id,"OBSERVING",true);
        var c=command(id,0);assertThat(service().claim(c).outcome()).isEqualTo(Outcome.RECORDED);
        rejectState(id,"APPLYING",true);rejectState(id,"COMPLETED",true);
    }
    @Test void orphanAttemptCannotCommitWithoutControlTokenAndAdmissionTransition(){
        UUID id=receipt();initialize(id,"1 minute");
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
            jdbc.update("insert into order_edit_execution_attempts(id,admission_id,order_id,db_generation_id,fencing_token,control_version) select ?,admission_id,order_id,db_generation_id,1,1 from order_edit_execution_controls where admission_id=?",UUID.randomUUID(),id);
            return null;
        }));
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempts where admission_id=?",Integer.class,id)).isZero();
    }
    @Test void ownershipCannotBeReplacedByMissingForeignRetiredOrWrongVersionToken(){
        UUID id=receipt(),other=receipt();var c=command(id,0);var foreign=command(other,0);
        service().claim(c);service().claim(foreign);
        for(String assignment:List.of("active_attempt_id=null,lease_until=null", "fencing_token=fencing_token+2", "version=version+2")) {
            db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
                jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
                jdbc.update("update order_edit_execution_controls set "+assignment+" where admission_id=?",id);return null;
            }));
        }
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
            jdbc.update("update order_edit_execution_controls set active_attempt_id=?,fencing_token=fencing_token+1,version=version+1,get_attempt_count=get_attempt_count+1 where admission_id=?",foreign.attemptId(),id);return null;
        }));
        assertThat(service().resolve(resolution(c,Disposition.REQUEUE)).outcome()).isEqualTo(Outcome.RECORDED);
        rejectState(id,"OBSERVING",false);
    }
    @Test void validClaimCannotHideAnInvalidFinalStateInSameTransaction(){
        UUID id=receipt();initialize(id,"1 minute");
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_claim(?,?,0)",UUID.class,id,UUID.randomUUID());
            jdbc.execute("set constraints all immediate");
            jdbc.update("update order_edit_admissions set execution_state='APPLYING',version=version+1 where id=?",id);
            return null;
        }));
        assertThat(jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempts where admission_id=?",Integer.class,id)).isZero();
    }
    @Test void admissionAcceptanceDoesNotPermitApplicationOrCompletion(){
        UUID id=receipt();var c=command(id,0);service().claim(c);
        for(String state:List.of("APPLYING","COMPLETED"))db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);jdbc.update("update order_edit_admissions set execution_state=?,version=version+1 where id=?",state,id);return null;
        }));
    }
    @Test void twoWorkersCannotOwnSameReceipt()throws Exception{
        UUID id=receipt();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var a=pool.submit(()->{gate.await();return service().claim(command(id,0));});var b=pool.submit(()->{gate.await();return service().claim(command(id,0));});gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS).outcome(),b.get(20,TimeUnit.SECONDS).outcome())).containsExactlyInAnyOrder(Outcome.RECORDED,Outcome.BLOCKED);
            assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempts where admission_id=?",Integer.class,id)).isEqualTo(1);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void identicalConcurrentClaimIsOneImmutableAttempt()throws Exception{
        UUID id=receipt();var c=command(id,0);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return service().claim(c);});var b=pool.submit(()->{gate.await();return service().claim(c);});gate.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RECORDED);assertThat(b.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RECORDED);
            assertThat(service().recoverClaim(c).view().attempts()).hasSize(1);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void recoveryPendingCannotChangeStateWithoutItsResultEvent(){
        UUID id=receipt();var c=command(id,0);service().claim(c);service().resolve(resolution(c,Disposition.UNKNOWN));
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
            jdbc.update("update order_edit_admissions set execution_state='REVIEW_REQUIRED',reason_code='UNBACKED_REVIEW',version=version+1 where id=?",id);return null;
        }));assertGate(id);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void expiredRecoveryCommitResponseIsObservedWithoutRetryingWork(boolean committed)throws Exception{
        UUID id=receipt();initialize(id,"30 milliseconds");var c=command(id,0);service().claim(c);Thread.sleep(50);
        var recovery=new Expired(id,UUID.randomUUID(),1);
        var faulty=new OrderEditExecutionCoordinator(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(1,committed));
        assertThat(faulty.recoverExpired(recovery).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        var observed=service().recoverExpiredResult(recovery);
        assertThat(observed.outcome()).isEqualTo(committed?Outcome.OBSERVED:Outcome.NOT_OBSERVED);
        assertThat(observed.view().state()).isEqualTo(committed?State.RECOVERY_PENDING:State.OBSERVING);
        assertThat(observed.view().attempts()).hasSize(1);assertGate(id);
    }

    @Test void closureTransactionRejectsBusinessMutationAfterEarlyConstraintCheck(){
        UUID id=receipt();initialize(id,"1 minute");
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_admission_close_unapplied(?,'CUSTOMER_WITHDRAWAL')",Object.class,id);
            jdbc.execute("set constraints all immediate");
            jdbc.update("update payments set updated_at=updated_at+interval '1 second' where id=(select base_payment_id from order_edit_admissions where id=?)",id);
            return null;
        }));
        assertGate(id);
    }

    OrderUnappliedClosure.Command closure(UUID id,long version){return new OrderUnappliedClosure.Command(id,UUID.randomUUID(),version,"CLOSED_UNAPPLIED","CUSTOMER_WITHDRAWAL");}
    @Test void normalClosureHasCanonicalImmutableProofAndIdempotentIdentity(){
        UUID id=receipt();var cmd=closure(id,0);var first=service().closeUnapplied(cmd);
        assertThat(first.outcome()).isEqualTo(Outcome.RECORDED);assertThat(first.view().state()).isEqualTo(State.CLOSED_UNAPPLIED);
        assertThat(service().closeUnapplied(cmd).view()).isEqualTo(first.view());assertThat(service().recoverClosure(cmd).outcome()).isEqualTo(Outcome.OBSERVED);
        var codec=new OrderUnappliedClosureCodec();
        var proof=jdbc.queryForObject("select evidence_document::text,canonical,evidence_digest from order_edit_unapplied_evidences where admission_id=?",
            (r,n)->codec.verify(r.getString(1),r.getBytes(2),r.getBytes(3)),id);
        assertThat(proof.matches(cmd)).isTrue();
        var changed=new OrderUnappliedClosure.Command(id,cmd.requestId(),0,"FAILED_UNAPPLIED","DIFFERENT");
        assertThat(service().closeUnapplied(changed).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(service().recoverClosure(changed).outcome()).isEqualTo(Outcome.CONFLICT);
        db.rejects("55000",()->jdbc.update("update order_edit_unapplied_evidences set reason_code='TAMPERED' where admission_id=?",id));
        // A later independent transaction must be allowed to change normal business state.
        assertThat(jdbc.update("update payments set updated_at=updated_at+interval '1 second' where id=(select base_payment_id from order_edit_admissions where id=?)",id)).isEqualTo(1);
        assertThat(service().recoverClosure(cmd).outcome()).isEqualTo(Outcome.OBSERVED);
    }
    @Test void dbAndJavaClosureCanonicalVectorsMatch(){
        var codec=new OrderUnappliedClosureCodec();String json="{\"z\":null,\"a\":[\"\",1.00,true],\"日本語\":\"商品\"}";
        assertThat(jdbc.queryForObject("select f125_closure_value(?::jsonb)",byte[].class,json)).isEqualTo(codec.canonicalValue(json));
        assertThat(jdbc.queryForObject("select sha256(f125_closure_value(?::jsonb))",byte[].class,json)).isEqualTo(codec.sha256(codec.canonicalValue(json)));
    }
    @Test void activeUnresolvedAndReviewAttemptsCannotClose(){
        for(Disposition d:new Disposition[]{null,Disposition.UNKNOWN,Disposition.REVIEW}){
            UUID id=receipt();var c=command(id,0);service().claim(c);if(d!=null)service().resolve(resolution(c,d));
            assertThat(service().closeUnapplied(closure(id,d==null?1:2)).outcome()).isEqualTo(Outcome.BLOCKED);assertGate(id);
        }
    }
    @Test void resolvedAttemptCanCloseWithoutReusingItsToken(){
        UUID id=receipt();initialize(id,"1 minute");var c=command(id,0);service().claim(c);service().resolve(resolution(c,Disposition.REQUEUE));
        var result=service().closeUnapplied(closure(id,2));assertThat(result.outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(result.view().token()).isEqualTo(3L);assertThat(result.view().activeAttemptId()).isNull();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void lostClosureCommitResponseIsObservedWithoutAutomaticRetry(boolean committed){
        UUID id=receipt();var cmd=closure(id,0);var faulty=new OrderEditExecutionCoordinator(db.source,OrderEditAdmissionServicePostgresTest.lostCommit(1,committed));
        assertThat(faulty.closeUnapplied(cmd).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        assertThat(service().recoverClosure(cmd).outcome()).isEqualTo(committed?Outcome.OBSERVED:Outcome.NOT_OBSERVED);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void closureRejectsSameTransactionMutationsWhetherConstraintsAreDeferredOrImmediate(boolean immediate){
        for(String change:List.of(
            "update payments set updated_at=updated_at+interval '1 second' where order_id=?",
            "update order_items set product_name=product_name||'changed' where order_id=?",
            "update order_charges set name=name||'changed' where order_id=?",
            "update orders set shipping_name=coalesce(shipping_name,'')||'changed' where id=?")){
            UUID id=receipt();initialize(id,"1 minute");long owner=jdbc.queryForObject("select order_id from order_edit_admissions where id=?",Long.class,id);
            db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
                jdbc.queryForObject("select f125_admission_close_unapplied(?,'TEST_CLOSE')",Object.class,id);
                if(immediate)jdbc.execute("set constraints all immediate");
                // Some orders have no charges; exercise each actual business row only.
                if(change.contains("order_charges"))jdbc.update("insert into order_charges(order_id,charge_type,name,amount) values (?,'SHIPPING','test',0)",owner);
                else jdbc.update(change,owner);return null;
            }));assertGate(id);
        }
    }
    @Test void financialStateChangeBeforeCloseCannotBecomeUnappliedEvidence(){
        UUID id=receipt();jdbc.update("update payments set status='CANCELLED' where id=(select base_payment_id from order_edit_admissions where id=?)",id);
        assertThat(service().closeUnapplied(closure(id,0)).outcome()).isEqualTo(Outcome.BLOCKED);assertGate(id);
    }

    @Test void runtimeChangeAfterImmediateClosureValidationIsRejected(){
        UUID id=receipt();initialize(id,"1 minute");
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_admission_close_unapplied(?,'TEST_CLOSE')",Object.class,id);
            jdbc.execute("set constraints all immediate");
            jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");return null;
        }));assertGate(id);
    }

    void mutationRejected(Runnable change){
        Throwable e=catchThrowable(change::run);assertThat(e).isNotNull();
        while(!(e instanceof java.sql.SQLException)&&e.getCause()!=null)e=e.getCause();
        assertThat(e).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)e).getSQLState()).isEqualTo("23514");
        assertThat(e.getMessage()).contains("Business mutation after closure proof");
    }
    long owner(UUID id){return jdbc.queryForObject("select order_id from order_edit_admissions where id=?",Long.class,id);}
    void closeSql(UUID id){jdbc.queryForObject("select f125_admission_close_unapplied(?,'TEST_CLOSE')",Object.class,id);}
    @Test void oldAndNewOwnersAreBothProtectedFromReparenting(){
        for(boolean moveOut:new boolean[]{true,false}){
            UUID id=receipt();var other=OrderEditAdmissionServicePostgresTest.fixture(false);
            long protectedOrder=owner(id);long otherOrder=other.order();
            initialize(id,"1 minute");
            mutationRejected(()->OrderEditAdmissionServicePostgresTest.tx(()->{
                closeSql(id);jdbc.execute("set constraints all immediate");
                jdbc.update("update order_items set order_id=? where order_id=?",moveOut?otherOrder:protectedOrder,moveOut?protectedOrder:otherOrder);return null;
            }));assertGate(id);
        }
    }
    @Test void insertsDeletesAndHistoryInjectionAfterClosureAreRejected(){
        for(String sql:List.of("delete from order_items where order_id=?",
            "insert into order_content_change_histories(order_id) values (?)",
            "insert into order_change_operations(order_id) values (?)",
            "insert into order_change_financial_requests(order_id) values (?)",
            "insert into stock_movements(order_id) values (?)")){
            UUID id=receipt();long owner=owner(id);initialize(id,"1 minute");
            mutationRejected(()->OrderEditAdmissionServicePostgresTest.tx(()->{closeSql(id);jdbc.execute("set constraints all immediate");jdbc.update(sql,owner);return null;}));assertGate(id);
        }
    }
    @Test void changedOrderLinkedStockMovementCannotBeAdoptedAsBaseline(){
        UUID id=receipt();long owner=owner(id);
        jdbc.update("""
            insert into stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,changed_by_username,changed_by_type,order_id)
            select product_id,product_name,'ORDER_RETURN',1,10,11,'test','USER',order_id from order_items where order_id=?
            """,owner);
        assertThat(service().closeUnapplied(closure(id,0)).outcome()).isEqualTo(Outcome.BLOCKED);assertGate(id);
    }
    @Test void anotherOrdersStockChangeDoesNotBlockClosure(){
        UUID id=receipt();long product=jdbc.queryForObject("select product_id from order_items where order_id=? limit 1",Long.class,owner(id));
        jdbc.update("update products set stock=stock+1 where id=?",product);
        assertThat(service().closeUnapplied(closure(id,0)).outcome()).isEqualTo(Outcome.RECORDED);
    }
    @Test void closureWithoutCompleteProofCannotCommitAndCasCannotBeSkipped(){
        UUID id=receipt();assertThat(service().closeUnapplied(closure(id,7)).outcome()).isEqualTo(Outcome.BLOCKED);assertGate(id);
        db.rejects("23514",()->OrderEditAdmissionServicePostgresTest.tx(()->{
            jdbc.queryForObject("select f125_execution_initialize(?)",Object.class,id);
            jdbc.update("update order_edit_admissions set execution_state='CLOSED_UNAPPLIED',reason_code='NO_PROOF',version=version+1 where id=?",id);
            return null;
        }));assertGate(id);
    }
    @Test void closureRuntimeGenerationMismatchKeepsGateClosed(){
        UUID id=receipt();UUID gen=UUID.randomUUID();
        jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",gen);
        jdbc.update("update order_edit_runtime_control set generation_id=?,version=version+1 where id=1",gen);
        assertThat(service().closeUnapplied(closure(id,0)).outcome()).isEqualTo(Outcome.RUNTIME_UNAVAILABLE);assertGate(id);
    }

    @Test void sameClosureCanRaceButCreatesOnlyOneProof()throws Exception{
        UUID id=receipt();var c=closure(id,0);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return service().closeUnapplied(c);});var b=pool.submit(()->{gate.await();return service().closeUnapplied(c);});gate.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RECORDED);assertThat(b.get(30,TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.RECORDED);
            assertThat(jdbc.queryForObject("select count(*) from order_edit_unapplied_evidences where admission_id=?",Integer.class,id)).isEqualTo(1);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void receiptBindingAndClosureEvidenceCannotBeReplaced(){
        UUID id=receipt();assertThat(service().closeUnapplied(closure(id,0)).outcome()).isEqualTo(Outcome.RECORDED);
        for(String sql:List.of("update order_edit_admission_evidences set financial_binding_digest=sha256(decode('ff','hex')) where admission_id=?",
            "update order_edit_unapplied_evidences set evidence_digest=sha256(decode('ff','hex')) where admission_id=?",
            "delete from order_edit_unapplied_evidences where admission_id=?"))db.rejects("55000",()->jdbc.update(sql,id));
        var codec=new OrderUnappliedClosureCodec();var row=jdbc.queryForMap("select evidence_document::text audit,canonical,evidence_digest digest from order_edit_unapplied_evidences where admission_id=?",id);
        String audit=(String)row.get("audit");byte[] bytes=(byte[])row.get("canonical"),hash=(byte[])row.get("digest");
        for(String invalid:List.of(audit.replace("unapplied/v1","unapplied/v2"),audit.replace("\"version\": 1","\"version\": 2")))
            assertThatThrownBy(()->codec.verify(invalid,bytes,hash)).isInstanceOf(IllegalArgumentException.class);
        bytes[0]^=1;assertThatThrownBy(()->codec.verify(audit,bytes,hash)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void inventoryReservationAndPaymentChildrenAreProtectedAfterClosure(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);UUID op=UUID.randomUUID();
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
              old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
              shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
              accepted_at,deadline_at,grace_until,state,created_at,updated_at)
            select ?,id,user_id,?,repeat('b',64),0,?,6000,6000,6000,0,0,'送料',0,1,'STANDARD','標準',10,10,
              ordered_at,change_deadline_at,change_deadline_at+interval '10 minutes','ABORTED',localtimestamp,localtimestamp from orders where id=?
            """,op,UUID.randomUUID(),f.payment(),f.order());
        var result=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);UUID id=result.admissionId();initialize(id,"1 minute");
        mutationRejected(()->OrderEditAdmissionServicePostgresTest.tx(()->{closeSql(id);jdbc.execute("set constraints all immediate");
            jdbc.update("insert into inventory_reservations(operation_id) values (?)",op);return null;}));
        for(String table:List.of("payment_transactions","capture_requests"))mutationRejected(()->OrderEditAdmissionServicePostgresTest.tx(()->{
            closeSql(id);jdbc.execute("set constraints all immediate");jdbc.update("insert into "+table+"(payment_id) values (?)",f.payment());return null;
        }));assertGate(id);
    }
}
