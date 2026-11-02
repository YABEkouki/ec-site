package com.example.ecsite.payment.safety;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.sql.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL transactions; uses the existing separately guarded commit-tracking fixture. */
class OrderEditExecutionFoundationPostgresTest {
    static PaymentSafetyExpandPostgresTest db;
    static JdbcTemplate jdbc;
    static UUID legacyQueued,legacyObserving,legacyGeneration;
    @BeforeAll static void database() {
        OrderEditAdmissionServicePostgresTest.database();
        db=OrderEditAdmissionServicePostgresTest.db;jdbc=db.jdbc;
        legacyGeneration=UUID.randomUUID();OrderEditAdmissionFoundationPostgresTest.generation=legacyGeneration;
        jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",legacyGeneration);
        jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',generation_id=?,version=version+1 where id=1",legacyGeneration);
        legacyQueued=OrderEditAdmissionFoundationPostgresTest.record(OrderEditAdmissionFoundationPostgresTest.fixture(false));
        legacyObserving=OrderEditAdmissionFoundationPostgresTest.record(OrderEditAdmissionFoundationPostgresTest.fixture(false));
        OrderEditAdmissionFoundationPostgresTest.verify(legacyQueued);OrderEditAdmissionFoundationPostgresTest.verify(legacyObserving);
        tx(()->{jdbc.update("update order_edit_admissions set execution_state='OBSERVING',version=version+1 where id=?",legacyObserving);return null;});
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        db.migrate("72");
        assertThat(jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");
    }
    @AfterAll static void cleanup(){OrderEditAdmissionServicePostgresTest.cleanup();}
    @BeforeEach void runtime(TestInfo info){new OrderEditAdmissionFoundationPostgresTest().runtime(info);}
    static <T>T tx(Supplier<T> body){return new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->body.get());}
    static UUID receipt(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        var result=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));
        assertThat(result.outcome()).isEqualTo(com.example.ecsite.service.order.evidence.OrderEditAdmissionService.Outcome.ACCEPTED);
        return result.admissionId();
    }
    static void initialize(UUID id){tx(()->{jdbc.queryForObject("select f125_execution_initialize(?)",Object.class,id);return null;});}
    static UUID claim(UUID id,long version){return tx(()->jdbc.queryForObject("select f125_execution_claim(?,?,?)",UUID.class,id,UUID.randomUUID(),version));}
    static void retry(UUID id,UUID attempt,long token,long version){tx(()->{jdbc.queryForObject("select f125_execution_resolve(?,?,?,?, 'REQUEUE','TEST_RETRY')",Object.class,id,attempt,token,version);return null;});}
    static void close(UUID id){tx(()->{jdbc.queryForObject("select f125_admission_close_unapplied(?,'TEST_CLOSE')",Object.class,id);return null;});}
    @Test void rowTriggersDoNotAcquireUpstreamLocks() {
        for(String name:List.of("f125_execution_control_guard","f125_execution_attempt_guard",
                "f125_execution_result_guard","f125_execution_close_guard","f125_admission_row_guard","f125_admission_evidence_guard")) {
            String definition=jdbc.queryForObject("select pg_get_functiondef(oid) from pg_proc where pronamespace=current_schema()::regnamespace and proname=?",String.class,name);
            assertThat(definition).as(name).doesNotContain("PERFORM f125_execution_lock(","PERFORM f125_admission_require_runtime(","FOR UPDATE","FOR SHARE");
        }
    }
    @Test void migrationExistsAndStartsStoppedWithoutProductionActivation(){
        assertThat(jdbc.queryForObject("select to_regclass('order_edit_execution_controls') is not null",Boolean.class)).isTrue();
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        assertThat(jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");
    }
    @Test void claimRetryAndClosurePreserveBusinessRows(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);String before=OrderEditAdmissionServicePostgresTest.business(f);
        UUID id=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f)).admissionId();
        initialize(id);UUID a=claim(id,0);
        assertThat(jdbc.queryForObject("select fencing_token from order_edit_execution_controls where admission_id=?",Long.class,id)).isEqualTo(1L);
        retry(id,a,1,1);
        assertThat(jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("QUEUED");
        close(id);
        assertThat(jdbc.queryForObject("select closed_at is not null from order_edit_admissions where id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_edit_unapplied_evidences where admission_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(OrderEditAdmissionServicePostgresTest.business(f)).isEqualTo(before);
    }
    @Test void staleCasAndUnresolvedAttemptCannotClose(){
        UUID id=receipt();initialize(id);UUID a=claim(id,0);
        db.rejects("23514",()->claim(id,0));
        db.rejects("23514",()->close(id));
        db.rejects("23514",()->retry(id,a,1,0));
        assertThat(jdbc.queryForObject("select closed_at is null from order_edit_admissions where id=?",Boolean.class,id)).isTrue();
    }
    @Test void expiredLeaseNeedsExplicitRecoveryAndOldTokenCannotSave() throws Exception {
        UUID id=receipt();tx(()->{jdbc.queryForObject("select f125_execution_initialize(?, interval '10 minutes',5,interval '20 milliseconds',interval '1 millisecond')",Object.class,id);return null;});
        UUID a=claim(id,0);Thread.sleep(40);
        db.rejects("23514",()->retry(id,a,1,1));
        db.rejects("23514",()->tx(()->{jdbc.queryForObject("select f125_execution_recover(?,?, 'REQUEUE','LEASE_ONLY_RETRY')",Object.class,id,1);return null;}));
        tx(()->{jdbc.queryForObject("select f125_execution_recover(?,?, 'UNKNOWN','LEASE_RECOVERY')",Object.class,id,1);return null;});
        assertThat(jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,id)).isEqualTo("RECOVERY_PENDING");
        assertThat(jdbc.queryForObject("select fencing_token from order_edit_execution_controls where admission_id=?",Long.class,id)).isEqualTo(2L);
        db.rejects("23514",()->claim(id,2));
        db.rejects("23514",()->retry(id,a,1,2));
    }
    @ParameterizedTest @ValueSource(strings={"order_edit_execution_attempts","order_edit_execution_attempt_results","order_edit_unapplied_evidences"})
    void evidenceTablesRejectAllMutation(String table){
        for(String sql:List.of("update "+table+" set admission_id=admission_id","delete from "+table))
            db.rejects("55000",()->jdbc.execute(sql));
        if(table.equals("order_edit_execution_attempts")) {
            // PG rejects a lone TRUNCATE of this FK parent before firing our trigger.
            SQLException rejected=rootSql(catchThrowable(()->jdbc.execute("truncate "+table)));
            assertThat(rejected.getSQLState()).isEqualTo("0A000");
            assertThat(rejected.getMessage()).contains("referenced in a foreign key constraint");
            // Include the exact FK-related tables, without CASCADE: now the immutable trigger rejects it.
            db.rejects("55000",()->jdbc.execute("truncate order_edit_execution_attempts,order_edit_execution_attempt_results,order_edit_unapplied_evidences,order_edit_execution_controls"));
        } else db.rejects("55000",()->jdbc.execute("truncate "+table));
    }
    @Test void directStateAndGateReleaseWithoutProofAreRejected(){
        UUID id=receipt();initialize(id);
        db.rejects("23514",()->tx(()->{jdbc.update("update order_edit_admissions set execution_state='OBSERVING',version=version+1 where id=?",id);return null;}));
        db.rejects("23514",()->tx(()->{jdbc.update("update order_edit_admissions set execution_state='CLOSED_UNAPPLIED',version=version+1 where id=?",id);return null;}));
        claim(id,0);
        db.rejects("23514",()->tx(()->{jdbc.update("update order_edit_admissions set execution_state='APPLYING',version=version+1 where id=?",id);return null;}));
    }
    @Test void legacyObservingCannotBeAssumedResolved(){
        jdbc.update("update order_edit_runtime_control set generation_id=?,version=version+1 where id=1",legacyGeneration);
        for(UUID id:List.of(legacyQueued,legacyObserving)) {
            assertThat(jdbc.queryForObject("select unapplied_baseline is null and financial_snapshot is null from order_edit_admissions where id=?",Boolean.class,id)).isTrue();
            db.rejects("23514",()->initialize(id));db.rejects("23514",()->close(id));
            for(String state:List.of("OBSERVING","APPLYING","COMPLETED"))db.rejects("23514",()->tx(()->{
                jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
                jdbc.update("update order_edit_admissions set execution_state=?,version=version+1 where id=?",state,id);return null;
            }));
        }
        assertThat(jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,legacyQueued)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select execution_state from order_edit_admissions where id=?",String.class,legacyObserving)).isEqualTo("OBSERVING");
    }
    @Test void runtimeStoppedAndGenerationMismatchDoNotChangeControl(){
        UUID id=receipt();initialize(id);String before=jdbc.queryForObject("select to_jsonb(c)::text from order_edit_execution_controls c where admission_id=?",String.class,id);
        jdbc.update("update order_edit_runtime_control set state='STOPPED',generation_id=null,version=version+1 where id=1");
        db.rejects("55000",()->claim(id,0));
        UUID gen=UUID.randomUUID();jdbc.update("insert into order_edit_runtime_generations(id,identity) values (?,f125_admission_identity())",gen);
        jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',generation_id=?,version=version+1 where id=1",gen);
        db.rejects("55000",()->claim(id,0));
        assertThat(jdbc.queryForObject("select to_jsonb(c)::text from order_edit_execution_controls c where admission_id=?",String.class,id)).isEqualTo(before);
    }
    @Test void concurrentClaimsHaveOneOwner() throws Exception {
        UUID id=receipt();initialize(id);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<Boolean> work=()->{gate.await();try{claim(id,0);return true;}catch(RuntimeException conflict){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("select count(*) from order_edit_execution_attempts where admission_id=?",Integer.class,id)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    static SQLException rootSql(Throwable failure) {
        assertThat(failure).isNotNull();
        while(!(failure instanceof SQLException)&&failure.getCause()!=null)failure=failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class);return (SQLException)failure;
    }
    static void awaitBlocked(AtomicInteger waiter,int blocker) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            if(waiter.get()>0&&Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,blocker,waiter.get())))return;
            try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        }
        throw new AssertionError("Expected database lock wait was not observed");
    }
    static void probeUnlocked(String sql) {
        // Independent connection from the same DataSource; never the holder's thread-bound connection.
        try(Connection connection=db.source.getConnection()) {
            connection.setAutoCommit(false);
            try(Statement statement=connection.createStatement()){statement.executeQuery(sql).close();}
            finally{connection.rollback();}
        }catch(SQLException locked){throw new AssertionError("Downstream lock was acquired out of order",locked);}
    }
    @ParameterizedTest @ValueSource(strings={"runtime","order","operation","payment","transaction","admission"})
    void canonicalLocksWaitBeforeAcquiringDownstreamRows(String stage) throws Exception {
        UUID operation=null;
        UUID id;
        if(stage.equals("operation")) {
            var f=OrderEditAdmissionServicePostgresTest.fixture(false);
            // Historical aborted work exists before admission; no operation is introduced by this test's worker.
            operation=OrderPaymentEvidenceFoundationPostgresTest.legacyOperation(
                new OrderPaymentEvidenceFoundationPostgresTest.Fixture(f.order(),f.customer(),f.payment(),f.auth(),f.epoch(),null,null));
            jdbc.update("update order_change_operations set state='ABORTED',version=version+1 where id=?",operation);
            var result=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));
            assertThat(result.outcome().name()).isEqualTo("ACCEPTED");id=result.admissionId();
        }else id=receipt();
        initialize(id);
        long order=jdbc.queryForObject("select order_id from order_edit_admissions where id=?",Long.class,id);
        long payment=jdbc.queryForObject("select base_payment_id from order_edit_admissions where id=?",Long.class,id);
        long authorization=jdbc.queryForObject("select base_authorization_id from order_edit_admissions where id=?",Long.class,id);
        String holder=switch(stage){
            case "runtime"->"select id from order_edit_runtime_control where id=1 for update";
            case "order"->"select id from orders where id="+order+" for update";
            case "operation"->"select id from order_change_operations where id='"+operation+"' for update";
            case "payment"->"select id from payments where id="+payment+" for update";
            case "transaction"->"select id from payment_transactions where id="+authorization+" for update";
            default->"select id from order_edit_admissions where id='"+id+"' for update";
        };
        String downstream=switch(stage){
            case "runtime"->"select id from orders where id="+order+" for update nowait";
            case "order"->"select id from payments where id="+payment+" for update nowait";
            case "operation"->"select id from payments where id="+payment+" for update nowait";
            case "payment"->"select id from payment_transactions where id="+authorization+" for update nowait";
            case "transaction"->"select id from order_edit_admissions where id='"+id+"' for update nowait";
            default->"select admission_id from order_edit_execution_controls where admission_id='"+id+"' for update nowait";
        };
        var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var running=new AtomicReference<Future<UUID>>();
        try {
            tx(()->{
                jdbc.queryForList(holder);int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                running.set(pool.submit(()->tx(()->{
                    pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    return jdbc.queryForObject("select f125_execution_claim(?,?,0)",UUID.class,id,UUID.randomUUID());
                })));
                awaitBlocked(pid,blocker);probeUnlocked(downstream);return null;
            });
            assertThat(running.get().get(15,TimeUnit.SECONDS)).isNotNull();
        }finally{pool.shutdownNow();}
    }
    @Test void directControlUpdateCannotDeadlockCanonicalClaimHoldingOrder() throws Exception {
        UUID id=receipt();initialize(id);var pid=new AtomicInteger();var running=new AtomicReference<Future<UUID>>();var pool=Executors.newSingleThreadExecutor();
        try {
            db.rejects("23514",()->tx(()->{
                jdbc.queryForList("select admission_id from order_edit_execution_controls where admission_id=? for update",id);
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                running.set(pool.submit(()->tx(()->{
                    pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    return jdbc.queryForObject("select f125_execution_claim(?,?,0)",UUID.class,id,UUID.randomUUID());
                })));
                awaitBlocked(pid,blocker);
                // Claim holds Order and waits for our control row. This invalid UPDATE must
                // reject its CAS immediately, without returning upstream to Order.
                jdbc.execute("set local lock_timeout='1s'");
                jdbc.update("update order_edit_execution_controls set version=version where admission_id=?",id);return null;
            }));
            assertThat(running.get().get(15,TimeUnit.SECONDS)).isNotNull();
        }finally{pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void invalidRowUpdatesDoNotReacquireRuntime(boolean admission) throws Exception {
        UUID id=receipt();initialize(id);var pool=Executors.newSingleThreadExecutor();
        try {
            tx(()->{
                jdbc.queryForList("select id from order_edit_runtime_control where id=1 for update");
                var result=pool.submit(()->rootSql(catchThrowable(()->tx(()->{
                    jdbc.execute("set local lock_timeout='500ms'");
                    jdbc.update(admission?"update order_edit_admissions set version=version where id=?":
                        "update order_edit_execution_controls set version=version where admission_id=?",id);return null;
                }))).getSQLState());
                try{assertThat(result.get(5,TimeUnit.SECONDS)).isEqualTo(admission?"55000":"23514");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                catch(ExecutionException|TimeoutException failed){throw new AssertionError(failed);}
                return null;
            });
        }finally{pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void v71CommitEvidenceRemainsValidUnderV72(boolean past) {
        var f=OrderEditAdmissionServicePostgresTest.fixture(past);
        var result=OrderEditAdmissionServicePostgresTest.service().submit(OrderEditAdmissionServicePostgresTest.command(f));
        assertThat(result.outcome().name()).isEqualTo(past?"REJECTED_LATE":"ACCEPTED");
        assertThat(jdbc.queryForObject("select original_xid<>verification_xid and ((original_commit_at<deadline_at)=(decision='ACCEPTED')) from order_edit_admission_evidences where admission_id=?",Boolean.class,result.admissionId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_edit_admission_events where admission_id=?",Integer.class,result.admissionId())).isEqualTo(2);
    }

}
