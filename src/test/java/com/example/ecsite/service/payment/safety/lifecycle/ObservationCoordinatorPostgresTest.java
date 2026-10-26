package com.example.ecsite.service.payment.safety.lifecycle;

import com.example.ecsite.service.payment.safety.payload.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.sql.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

class ObservationCoordinatorPostgresTest {
    static PGSimpleDataSource source;static JdbcTemplate db;
    @BeforeAll static void isolated(){ObservationRunLifecyclePostgresTest.isolated();source=ObservationRunLifecyclePostgresTest.source;db=new JdbcTemplate(source);db.setQueryTimeout(10);ObservationAssessmentPostgresTest.source=source;ObservationAssessmentPostgresTest.db=db;}
    @Test void normalReviewAndMissingOrdersFinishSuccessfullyWithLedgerCounts() {
        var fixture=new ObservationAssessmentPostgresTest();fixture.fixture("SELECTED");long first=fixture.order;
        fixture.fixture("REVIEW");long review=fixture.order;
        fixture.fixture("NORMAL_NULL");long normalNull=fixture.order;
        long missing=normalNull+100;
        var old=fixture.request;var m=old.manifest();
        var request=new ObservationRunRequest(UUID.randomUUID(),old.executionType(),new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(first-1,missing),List.of(first,review,normalNull,missing),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),m.verificationProfiles(),m.timeOrigins(),m.policy()),old.requester());
        UUID owner=UUID.randomUUID();var lifecycle=new ObservationRunLifecycle(source);
        assertThat(lifecycle.execute(lifecycle.prepareCreate(request,owner)).status()).isEqualTo(APPLIED);
        var businessBefore=businessRows(List.of(first,review,normalNull));
        var result=new ObservationCoordinator(source).run(request.requestId(),owner,request.requester());
        assertThat(result.status()).isEqualTo(OBSERVED);assertThat(result.current().status()).isEqualTo("SUCCESS");
        assertThat(result.ledger().counters().classifiedOrderCount()).isEqualTo(3);
        assertThat(result.ledger().counters().reviewOrderCount()).isEqualTo(1);
        assertThat(result.ledger().counters().notFoundOrderCount()).isEqualTo(1);
        assertThat(result.current().cursor()).isEqualTo(missing);
        assertThat(businessRows(List.of(first,review,normalNull))).isEqualTo(businessBefore);
        assertThat(db.queryForObject("select count(*) from payment_safety_assessments where run_id=?",Long.class,request.requestId())).isEqualTo(3);
        assertThat(new ObservationCoordinator(source).run(request.requestId(),owner,request.requester()).current().status()).isEqualTo("SUCCESS");
    }
    ObservationAssessmentPostgresTest fixture(){var f=new ObservationAssessmentPostgresTest();f.fixture("SELECTED");return f;}
    @ParameterizedTest @ValueSource(strings={"ACQUISITION","PERSISTENCE","PERSISTENCE_TIMEOUT"})
    void persistentStageFailureRollsBackRetriesThreeTimesAndEndsPartial(String stage) {
        var f=fixture();
        DataSource broken=f.wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement")) {
                String sql=a[0].toString();
                if(stage.equals("ACQUISITION") && sql.contains(" from orders o "))throw new SQLException("injected read failure","22000");
                if(stage.startsWith("PERSISTENCE") && sql.startsWith("insert into payment_safety_assessments"))throw new SQLException("injected storage failure",stage.equals("PERSISTENCE_TIMEOUT")?"55P03":"P0001");
            }return ObservationAssessmentPostgresTest.invoke(c,m,a);
        });
        var result=new ObservationCoordinator(broken).run(f.request.requestId(),f.owner,f.request.requester());
        assertThat(result.status()).isEqualTo(OBSERVED);assertThat(result.current().status()).isEqualTo("PARTIAL_FAILURE");
        var counts=result.ledger().counters();assertThat(counts.processedOrderCount()).isEqualTo(1);assertThat(counts.unresolvedFailureOrderCount()).isEqualTo(1);assertThat(counts.retryAttemptCount()).isEqualTo(2);
        assertThat(stage.equals("ACQUISITION")?counts.acquisitionFailureAttemptCount():counts.persistenceFailureAttemptCount()).isEqualTo(3);
        assertThat(db.queryForObject("select count(*) from payment_safety_assessments where run_id=?",Long.class,f.request.requestId())).isZero();
        assertThat(db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type='ORDER_FINISHED'",Long.class,f.request.requestId())).isEqualTo(3);
    }
    @Test void unrepresentableSnapshotIsAssemblyFailureAndDoesNotBecomeReview() {
        var f=fixture();db.execute("alter table orders alter column total_amount drop not null");
        try {
            db.update("update orders set total_amount=null where id=?",f.order);
            var result=new ObservationCoordinator(source).run(f.request.requestId(),f.owner,f.request.requester());
            assertThat(result.current().status()).isEqualTo("PARTIAL_FAILURE");assertThat(result.ledger().counters().assemblyFailureAttemptCount()).isEqualTo(3);assertThat(result.ledger().counters().reviewOrderCount()).isZero();
        }finally{db.update("update orders set total_amount=5500 where id=?",f.order);db.execute("alter table orders alter column total_amount set not null");}
    }
    @Test void restartedWorkerDoesNotRepeatLiveOpenTrialButExpiredTakeoverInterruptsAndRetries() {
        var f=fixture();var start=f.started();
        var coordinator=new ObservationCoordinator(source);
        assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(UNKNOWN);
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",f.request.requestId());
        var result=new ObservationCoordinator(source).run(f.request.requestId(),UUID.randomUUID(),f.request.requester());
        assertThat(result.status()).isEqualTo(OBSERVED);assertThat(result.current().status()).isEqualTo("SUCCESS");
        assertThat(result.ledger().counters().interruptedAttemptCount()).isEqualTo(1);assertThat(result.ledger().counters().retryAttemptCount()).isEqualTo(1);
        assertThat(f.api.execute(start).status()).isEqualTo(REPLAYED); // historical replay grants no old authority
    }
    @Test void restartAfterCommittedTakeoverUsesStoredTakeoverReference() {
        var f=fixture();f.started();
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",f.request.requestId());
        UUID next=UUID.randomUUID();var taken=f.lifecycle.prepare(f.request,ObservationLifecycleCommands.Action.TAKEOVER,UUID.randomUUID(),f.owner,next,1,2,f.request.requester());
        assertThat(f.lifecycle.execute(taken).status()).isEqualTo(APPLIED);
        var result=new ObservationCoordinator(source).run(f.request.requestId(),next,f.request.requester());
        assertThat(result.current().status()).isEqualTo("SUCCESS");assertThat(result.ledger().counters().interruptedAttemptCount()).isEqualTo(1);
    }
    @Test void unknownCommitStopsUntilSameEnvelopeReconciledAndRestartUsesFinishedLedger() {
        var f=fixture();AtomicBoolean lost=new AtomicBoolean(),outage=new AtomicBoolean();AtomicInteger commits=new AtomicInteger();
        DataSource faulty=f.wrapped((c,m,a)->{
            if(outage.get() && m.getName().equals("prepareStatement"))throw new SQLException("injected resolver outage","08006");
            if(m.getName().equals("commit") && commits.incrementAndGet()==2 && lost.compareAndSet(false,true)){c.commit();outage.set(true);throw new SQLException("injected response loss","08006");}
            return ObservationAssessmentPostgresTest.invoke(c,m,a);
        });
        var coordinator=new ObservationCoordinator(faulty);
        assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(UNKNOWN);
        assertThat(db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type='ORDER_STARTED'",Long.class,f.request.requestId())).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from payment_safety_assessments where run_id=?",Long.class,f.request.requestId())).isZero();
        outage.set(false);var resumed=coordinator.run(f.request.requestId(),f.owner,f.request.requester());
        assertThat(resumed.current().status()).isEqualTo("SUCCESS");assertThat(resumed.ledger().counters().retryAttemptCount()).isZero();
        assertThat(new ObservationCoordinator(source).run(f.request.requestId(),f.owner,f.request.requester()).current().status()).isEqualTo("SUCCESS");
    }

    @Test void unknownUncommittedAssessmentDoesNotPermanentlyPinExpiredPreparedCommand() {
        var f=fixture();AtomicBoolean injected=new AtomicBoolean(),outage=new AtomicBoolean();
        DataSource faulty=f.wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement")) {
                if(outage.get())throw new SQLException("resolver unavailable","08006");
                if(a[0].toString().startsWith("insert into payment_safety_assessments") && injected.compareAndSet(false,true)) {
                    outage.set(true);throw new SQLException("injected lock timeout before insert","55P03");
                }
            }return ObservationAssessmentPostgresTest.invoke(c,m,a);
        });
        var coordinator=new ObservationCoordinator(faulty);
        assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(UNKNOWN);
        assertThat(db.queryForObject("select count(*) from payment_safety_assessments where run_id=?",Long.class,f.request.requestId())).isZero();
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",f.request.requestId());
        outage.set(false);
        assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(LEASE_LOST);
        var recovered=coordinator.run(f.request.requestId(),UUID.randomUUID(),f.request.requester());
        assertThat(recovered.status()).isEqualTo(OBSERVED);assertThat(recovered.current().status()).isEqualTo("SUCCESS");assertThat(recovered.ledger().counters().interruptedAttemptCount()).isEqualTo(1);
    }

    @Test void storageFailureDuringUnknownReplayIsRecordedBeforeRetry() {
        var f=fixture();AtomicInteger inserts=new AtomicInteger();AtomicBoolean outage=new AtomicBoolean();
        DataSource faulty=f.wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement")) {
                if(outage.get())throw new SQLException("resolver unavailable","08006");
                if(a[0].toString().startsWith("insert into payment_safety_assessments")) {
                    int n=inserts.incrementAndGet();if(n==1){outage.set(true);throw new SQLException("injected timeout","55P03");}
                    if(n==2)throw new SQLException("injected retry storage failure","P0001");
                }
            }return ObservationAssessmentPostgresTest.invoke(c,m,a);
        });
        var coordinator=new ObservationCoordinator(faulty);
        assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(UNKNOWN);
        outage.set(false);var result=coordinator.run(f.request.requestId(),f.owner,f.request.requester());
        assertThat(result.status()).isEqualTo(OBSERVED);assertThat(result.current().status()).isEqualTo("SUCCESS");
        assertThat(result.ledger().counters().persistenceFailureAttemptCount()).isEqualTo(1);assertThat(result.ledger().counters().retryAttemptCount()).isEqualTo(1);
    }
    @Test void terminalEventInsertFailureRollsBackRunFinishWithoutReclassifying() {
        var f=fixture();AtomicBoolean finishing=new AtomicBoolean();
        DataSource faulty=f.wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement")) {
                String sql=a[0].toString();if(sql.startsWith("update payment_safety_classification_runs") && sql.contains("status='SUCCESS'"))finishing.set(true);
                if(finishing.get() && sql.startsWith("insert into payment_safety_audit_events"))throw new SQLException("injected finish event failure","P0001");
            }return ObservationAssessmentPostgresTest.invoke(c,m,a);
        });
        var stopped=new ObservationCoordinator(faulty).run(f.request.requestId(),f.owner,f.request.requester());
        assertThat(stopped.status()).isEqualTo(STORAGE_FAILED);
        assertThat(db.queryForObject("select status from payment_safety_classification_runs where id=?",String.class,f.request.requestId())).isEqualTo("RUNNING");
        assertThat(db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type='FINISHED'",Long.class,f.request.requestId())).isZero();
        var finished=new ObservationCoordinator(source).run(f.request.requestId(),f.owner,f.request.requester());assertThat(finished.current().status()).isEqualTo("SUCCESS");
        assertThat(db.queryForObject("select count(*) from payment_safety_assessments where run_id=?",Long.class,f.request.requestId())).isEqualTo(1);
    }
    @Test void foreignLiveWorkerAndPrematureFinishAreRejected() {
        var f=fixture();var before=f.lifecycle.prepare(f.request,ObservationLifecycleCommands.Action.OBSERVATION_SUCCESS,UUID.randomUUID(),f.owner,null,1,0,f.request.requester());
        assertThat(f.lifecycle.execute(before).status()).isEqualTo(UNSUPPORTED_PHASE);
        assertThat(new ObservationCoordinator(source).run(f.request.requestId(),UUID.randomUUID(),f.request.requester()).status()).isEqualTo(LEASE_LOST);
        assertThat(db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type='ORDER_STARTED'",Long.class,f.request.requestId())).isZero();
    }

    List<String> businessRows(List<Long> ids) {
        String targets=String.join(",",ids.stream().map(Object::toString).toList());var result=new ArrayList<String>();
        for(String query:List.of("select * from orders where id in ("+targets+")","select * from payments where order_id in ("+targets+")","select t.* from payment_transactions t join payments p on t.payment_id=p.id where p.order_id in ("+targets+")","select * from order_status_histories where order_id in ("+targets+")"))
            result.addAll(db.queryForList("select to_jsonb(x)::text from ("+query+") x order by x.id",String.class));
        return result;
    }
    @Test void unknownRolledBackLifecycleFinishCanRecoverAfterItsPreparedLeaseExpires() {
        var f=fixture();AtomicBoolean finishing=new AtomicBoolean(),lost=new AtomicBoolean(),outage=new AtomicBoolean();
        DataSource faulty=f.wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement")) {
                if(outage.get())throw new SQLException("resolver unavailable","08006");
                if(a[0].toString().contains("status='SUCCESS'"))finishing.set(true);
            }
            if(m.getName().equals("commit") && finishing.get() && lost.compareAndSet(false,true)){c.rollback();outage.set(true);throw new SQLException("injected finish connection loss","08006");}
            return ObservationAssessmentPostgresTest.invoke(c,m,a);
        });
        var coordinator=new ObservationCoordinator(faulty);assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(UNKNOWN);
        assertThat(db.queryForObject("select status from payment_safety_classification_runs where id=?",String.class,f.request.requestId())).isEqualTo("RUNNING");
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",f.request.requestId());
        outage.set(false);assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(LEASE_LOST);
        var recovered=coordinator.run(f.request.requestId(),UUID.randomUUID(),f.request.requester());
        assertThat(recovered.status()).isEqualTo(OBSERVED);assertThat(recovered.current().status()).isEqualTo("SUCCESS");assertThat(recovered.ledger().counters().classifiedOrderCount()).isEqualTo(1);assertThat(recovered.ledger().counters().retryAttemptCount()).isZero();
    }
    @Test void emptyManifestFinishesAtUpperBoundaryAndMissingLifecycleContextIsRejected() {
        var f=fixture();var m=f.request.manifest();var request=new ObservationRunRequest(UUID.randomUUID(),f.request.executionType(),new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(0,100),List.of(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),m.verificationProfiles(),m.timeOrigins(),m.policy()),f.request.requester());
        assertThat(f.lifecycle.execute(f.lifecycle.prepareCreate(request,f.owner)).status()).isEqualTo(APPLIED);
        var result=new ObservationCoordinator(source).run(request.requestId(),f.owner,request.requester());
        assertThat(result.current().status()).isEqualTo("SUCCESS");assertThat(result.current().cursor()).isEqualTo(100);assertThat(result.ledger().counters().processedOrderCount()).isZero();
        var incomplete=new ObservationEventFactory(request).finished(ObservationEventFactory.TerminalStatus.SUCCESS,1,request.requester(),java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThatThrownBy(()->ObservationLifecycleContextV1.fromEvent(incomplete.record())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failureEventStorageErrorRetainsOriginalFailureEnvelopeAcrossCalls() {
        var f=fixture();AtomicBoolean readFailed=new AtomicBoolean(),eventFailed=new AtomicBoolean();
        DataSource faulty=f.wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement")) {
                String sql=a[0].toString();
                if(sql.contains(" from orders o ") && readFailed.compareAndSet(false,true))throw new SQLException("injected read failure","22000");
                if(readFailed.get() && sql.startsWith("insert into payment_safety_audit_events") && eventFailed.compareAndSet(false,true))throw new SQLException("injected failure event storage failure","P0001");
            }return ObservationAssessmentPostgresTest.invoke(c,m,a);
        });
        var coordinator=new ObservationCoordinator(faulty);
        assertThat(coordinator.run(f.request.requestId(),f.owner,f.request.requester()).status()).isEqualTo(STORAGE_FAILED);
        var result=coordinator.run(f.request.requestId(),f.owner,f.request.requester());
        assertThat(result.current().status()).isEqualTo("SUCCESS");assertThat(result.ledger().counters().acquisitionFailureAttemptCount()).isEqualTo(1);assertThat(result.ledger().counters().retryAttemptCount()).isEqualTo(1);
    }

}
