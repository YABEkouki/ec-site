package com.example.ecsite.payment.safety;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import com.example.ecsite.service.order.evidence.*;
import static org.assertj.core.api.Assertions.*;

/** Guarded ecsite_test normally; only the boot-identity case owns a disposable PostgreSQL. */
class OrderEditOperationalRuntimePostgresTest {
    static PaymentSafetyExpandPostgresTest db;static JdbcTemplate jdbc;
    org.testcontainers.postgresql.PostgreSQLContainer restartContainer;
    @BeforeAll static void database(){OrderEditAdmissionFoundationPostgresTest.database();}
    @AfterAll static void cleanup(){OrderEditAdmissionFoundationPostgresTest.cleanup();}
    @BeforeEach void schema(TestInfo info){
        if(info.getTestMethod().orElseThrow().getName().equals("actualDatabaseRestartRequiresExplicitSameGenerationApproval")
                && OrderEditAdmissionFoundationPostgresTest.container==null) {
            restartContainer=OrderEditAdmissionFoundationPostgresTest.commitTrackingContainer();
            OrderEditAdmissionFoundationPostgresTest.container=restartContainer;
            restartContainer.start();
        }
        db=OrderEditAdmissionFoundationPostgresTest.open(OrderEditAdmissionFoundationPostgresTest.container);db.migrate("76");jdbc=db.jdbc;
        OrderEditAdmissionServicePostgresTest.db=db;OrderEditAdmissionServicePostgresTest.jdbc=jdbc;
        LegacyOrderEvidenceEnrollmentPostgresTest.db=db;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=jdbc;
        Flyway.configure().dataSource(db.source).defaultSchema(db.schema).target("76").load().validate();
    }
    @AfterEach void stopRestartContainer(){
        if(restartContainer!=null) {
            try{restartContainer.stop();}
            finally{OrderEditAdmissionFoundationPostgresTest.container=null;restartContainer=null;}
        }
    }
    static <T>T tx(Supplier<T> body){return new TransactionTemplate(new DataSourceTransactionManager(db.source)).execute(s->body.get());}
    long version(){return jdbc.queryForObject("select version from order_edit_runtime_control where id=1",Long.class);}
    UUID generation(){return jdbc.queryForObject("select f125_runtime_new_generation(?,'OPERATIONAL')",UUID.class,version());}
    void probe(){jdbc.queryForObject("select f125_runtime_probe(?)",String.class,version());}
    void enable(UUID g){jdbc.queryForObject("select f125_runtime_enable(?,?,?,?)",Object.class,version(),g,jdbc.queryForObject("select encode(sha256(convert_to(f125_operational_identity()::text,'UTF8')),'hex')",String.class),"operator verified single writer; isolated fixture");}
    void stop(){jdbc.queryForObject("select f125_runtime_stop(?,?)",Object.class,version(),"isolated test stop");}
    boolean available(){return jdbc.queryForObject("select f125_runtime_available()",Boolean.class);}
    UUID running(){UUID g=generation();probe();enable(g);return g;}
    OrderEditAdmissionServicePostgresTest.Fixture fixture(){return OrderEditAdmissionServicePostgresTest.fixture(false);}
    UUID receipt(){var f=fixture();var r=new OrderEditAdmissionService(db.source).submit(OrderEditAdmissionServicePostgresTest.command(f));assertThat(r.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);return r.admissionId();}
    @Test void migrationStartsStoppedAndPreservesNoActivation(){
        assertThat(jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("STOPPED");assertThat(available()).isFalse();
        assertThat(jdbc.queryForObject("select f125_runtime_status()->>'storedState'",String.class)).isEqualTo("STOPPED");
        db.rejects("55000",()->jdbc.queryForObject("select f125_admission_require_runtime()",UUID.class));
    }
    @Test void explicitProbeAndEnableUseTwoRealCommittedTransactions(){
        UUID g=generation();probe();var p=jdbc.queryForMap("select probe_xid::text as xid,probe_identity,version from order_edit_runtime_control where id=1");
        enable(g);assertThat(available()).isTrue();
        assertThat(jdbc.queryForObject("select probe_commit_at=pg_xact_commit_timestamp(probe_xid::xid) and probe_xid<>pg_current_xact_id() from order_edit_runtime_control where id=1",Boolean.class)).isTrue();
        UUID r=receipt();assertThat(jdbc.queryForObject("select scope from order_edit_admissions where id=?",String.class,r)).isEqualTo("OPERATIONAL");
        assertThat(jdbc.queryForObject("select scope from order_edit_admission_evidences where admission_id=?",String.class,r)).isEqualTo("OPERATIONAL");
        assertThat(p.get("xid")).isNotNull();
    }
    @Test void uncommittedOrMissingProbeCannotApprove(){
        UUID g=generation();db.rejects("55000",()->enable(g));
        db.rejects("23514",()->tx(()->{probe();enable(g);return null;}));assertThat(available()).isFalse();
    }
    @Test void casAndDirectModeSwitchAreRejected(){
        UUID g=running();db.rejects("23514",()->jdbc.queryForObject("select f125_runtime_stop(?,?)",Object.class,version()-1,"stale"));
        db.rejects("23514",()->jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',version=version+1 where id=1"));assertThat(available()).isTrue();
        stop();probe();enable(g);assertThat(available()).isTrue();
    }
    @Test void stoppedReceiptIsReadableButCannotClaimOrClose(){
        UUID g=running(),id=receipt();var before=jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,id);stop();
        assertThat(jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,id)).isEqualTo(before);
        db.rejects("55000",()->jdbc.queryForObject("select f125_execution_initialize(?)",Object.class,id));
        db.rejects("55000",()->jdbc.queryForObject("select f125_execution_close_request(?,?,?,?,?)",Object.class,id,UUID.randomUUID(),0,"CLOSED_UNAPPLIED","TEST"));
        probe();enable(g);assertThat(available()).isTrue();
    }
    @Test void unresolvedWorkPreventsNewGenerationAndScopeChange(){
        running();receipt();stop();db.rejects("23514",()->generation());
        db.rejects("23514",()->jdbc.queryForObject("select f125_runtime_new_generation(?,'ISOLATED_TEST')",UUID.class,version()));
    }
    @Test void activeLeasePreventsResumeWithoutResettingToken(){
        UUID g=running(),id=receipt();var coordinator=new OrderEditExecutionCoordinator(db.source);var c=coordinator.claim(new OrderEditExecution.Claim(id,UUID.randomUUID(),0));assertThat(c.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);
        var before=jdbc.queryForObject("select to_jsonb(c)::text from order_edit_execution_controls c where admission_id=?",String.class,id);stop();probe();db.rejects("23514",()->enable(g));
        assertThat(jdbc.queryForObject("select to_jsonb(c)::text from order_edit_execution_controls c where admission_id=?",String.class,id)).isEqualTo(before);
    }
    @Test void isolatedModeRetainsExactOldEnvironmentChecks(){
        UUID g=jdbc.queryForObject("select f125_runtime_new_generation(?,'ISOLATED_TEST')",UUID.class,version());
        jdbc.update("update order_edit_runtime_control set state='ISOLATED_TEST',generation_id=?,version=version+1 where id=1",g);assertThat(available()).isTrue();
        db.rejects("55000",()->tx(()->{jdbc.execute("set local synchronous_commit=off");jdbc.queryForObject("select f125_admission_require_runtime()",UUID.class);return null;}));
    }
    @Test void stopWaitsForCanonicalSharedRuntimeLock()throws Exception{
        running();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->tx(()->{jdbc.queryForObject("select f125_admission_require_runtime()",UUID.class);locked.countDown();try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();long v=version();var b=pool.submit(()->jdbc.queryForObject("select f125_runtime_stop(?,?)",Object.class,v,"concurrent stop"));
            Thread.sleep(100);assertThat(b.isDone()).isFalse();release.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);assertThat(available()).isFalse();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void actualDatabaseRestartRequiresExplicitSameGenerationApproval()throws Exception{
        UUID g=running(),id=receipt();var before=jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,id);
        var epoch=jdbc.queryForObject("select identity::text from order_edit_runtime_generations where id=?",String.class,g);
        var proof=jdbc.queryForObject("select to_jsonb(e)::text from order_edit_admission_evidences e where admission_id=?",String.class,id);
        var oldBoot=jdbc.queryForObject("select f125_operational_identity()->>'boot'",String.class);
        var container=OrderEditAdmissionFoundationPostgresTest.container;
        container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
        // Docker may assign a new mapped host port at restart. Reinspect it through the
        // unchanged Docker-provenance/database guard before opening the rebound datasource.
        var rebound=new java.util.concurrent.atomic.AtomicReference<org.postgresql.ds.PGSimpleDataSource>();
        var phase=new java.util.concurrent.atomic.AtomicReference<>("inspect");
        try{
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(90)).ignoreExceptions().untilAsserted(()->{
                phase.set("inspect Docker mapping");var inspected=container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec();
                var port=inspected.getNetworkSettings().getPorts().getBindings().entrySet().stream().filter(e->e.getKey().getPort()==5432).findFirst().orElseThrow().getValue()[0].getHostPortSpec();
                String url="jdbc:postgresql://"+container.getHost()+":"+port+"/"+container.getDatabaseName();
                var env=new org.springframework.mock.env.MockEnvironment();
                env.setActiveProfiles("test");env.setProperty("ecsite.test.database-mode","testcontainers");
                for(String prefix:List.of("spring.datasource","spring.flyway")){
                    env.setProperty(prefix+".url",url);env.setProperty(prefix+(prefix.endsWith("flyway")?".user":".username"),container.getUsername());env.setProperty(prefix+".password",container.getPassword());
                }
                phase.set("existing isolation guard");com.example.ecsite.support.isolation.IsolatedDatabaseGuard.verify(env);
                var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser(container.getUsername());source.setPassword(container.getPassword());source.setCurrentSchema(db.schema);source.setConnectTimeout(3);source.setSocketTimeout(5);
                phase.set("guarded reconnect");assertThat(new JdbcTemplate(source).queryForObject("select 1",Integer.class)).isEqualTo(1);rebound.set(source);
            });
        }catch(org.awaitility.core.ConditionTimeoutException failure){throw new AssertionError("Isolated restart reconnect failed at "+phase.get(),failure);}
        db.source=rebound.get();db.jdbc=new JdbcTemplate(db.source);jdbc=db.jdbc;
        OrderEditAdmissionServicePostgresTest.jdbc=jdbc;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=jdbc;
        assertThat(jdbc.queryForObject("select f125_operational_identity()->>'boot'",String.class)).isNotEqualTo(oldBoot);
        assertThat(jdbc.queryForObject("select state from order_edit_runtime_control where id=1",String.class)).isEqualTo("RUNNING");assertThat(available()).isFalse();
        db.rejects("55000",()->jdbc.queryForObject("select f125_admission_require_runtime(?)",UUID.class,g));
        stop();probe();enable(g);assertThat(available()).isTrue();
        assertThat(jdbc.queryForObject("select to_jsonb(a)::text from order_edit_admissions a where id=?",String.class,id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select identity::text from order_edit_runtime_generations where id=?",String.class,g)).isEqualTo(epoch);
        assertThat(jdbc.queryForObject("select to_jsonb(e)::text from order_edit_admission_evidences e where admission_id=?",String.class,id)).isEqualTo(proof);
        var gateway=org.mockito.Mockito.mock(com.example.ecsite.payment.PaymentGateway.class);
        org.mockito.Mockito.when(gateway.observeOrderAuthorization(org.mockito.ArgumentMatchers.any())).thenAnswer(a->com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport.observe(a.getArgument(0)));
        assertThat(new OrderEditExecutionService(db.source,gateway,LegacyOrderEvidenceEnrollmentPostgresTest.C).execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
    }
    @Test void rollbackAndInvalidSettingsNeverPersistAProbe(){
        UUID g=generation();assertThatThrownBy(()->tx(()->{probe();throw new IllegalStateException("mock rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select probe_xid is null from order_edit_runtime_control where id=1",Boolean.class)).isTrue();
        db.rejects("55000",()->tx(()->{jdbc.execute("set local synchronous_commit=off");probe();return null;}));db.rejects("55000",()->enable(g));
    }
    @Test void expiredOwnerIsFencedAfterResumeRatherThanReused(){
        UUID g=running(),id=receipt();jdbc.queryForObject("select f125_execution_initialize(?,interval '10 minutes',5,interval '1 second',interval '0 seconds')",Object.class,id);
        var coordinator=new OrderEditExecutionCoordinator(db.source);var claim=new OrderEditExecution.Claim(id,UUID.randomUUID(),0);var owned=coordinator.claim(claim).view();stop();probe();
        jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from lease_until-clock_timestamp()))+0.02) from order_edit_execution_controls where admission_id=?",Object.class,id);
        enable(g);var expired=coordinator.recoverExpired(new OrderEditExecution.Expired(id,UUID.randomUUID(),owned.version()));
        assertThat(expired.outcome()).isEqualTo(OrderEditExecution.Outcome.RECORDED);assertThat(expired.view().token()).isGreaterThan(owned.token());
        var stale=coordinator.resolve(new OrderEditExecution.Resolution(id,UUID.randomUUID(),claim.attemptId(),owned.token(),owned.version(),OrderEditExecution.Disposition.REQUEUE,"STALE"));
        assertThat(stale.outcome()).isNotEqualTo(OrderEditExecution.Outcome.RECORDED);
    }
    @Test void lateReceiptAndStrictEqualityStillUseCommitEvidence(){
        running();var f=OrderEditAdmissionServicePostgresTest.fixture(true);var r=new OrderEditAdmissionService(db.source).submit(OrderEditAdmissionServicePostgresTest.command(f));
        assertThat(r.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.REJECTED_LATE);
        assertThat(jdbc.queryForObject("select f125_admission_before_deadline(t,t) from (select clock_timestamp() t) s",Boolean.class)).isFalse();
    }
    @Test void browserAvailabilityIsReadonlyAndChecksTheActualOperationalInstance(){
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var browser=new OrderEditBrowserService(org.mockito.Mockito.mock(OrderEditPreviewService.class),factory.getBeanProvider(LegacyOrderEvidenceEnrollment.class),jdbc,
            factory.getBeanProvider(OrderEditAdmissionService.class),factory.getBeanProvider(OrderEditExecutionService.class),factory.getBeanProvider(OrderEditCardService.class));
        var user=new com.example.ecsite.security.CustomUserDetails(1L,"test","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,"unused",user.getAuthorities()));
        try{
            assertThat(browser.submissionAvailable()).isFalse();running();long v=version();assertThat(browser.submissionAvailable()).isTrue();assertThat(version()).isEqualTo(v);
            stop();assertThat(browser.submissionAvailable()).isFalse();
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

    @Test void stopDuringRealAdmissionCommitKeepsOriginalReceiptForResume()throws Exception{
        UUID g=running();var f=fixture();var command=OrderEditAdmissionServicePostgresTest.command(f);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();var manager=new DataSourceTransactionManager(db.source){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                if(calls.incrementAndGet()==1){locked.countDown();try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}}
                super.doCommit(status);
            }
        };
        var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->new OrderEditAdmissionService(db.source,manager).submit(command));assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->{stop();return true;});Thread.sleep(100);assertThat(b.isDone()).isFalse();release.countDown();
            var first=a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertThat(first.admissionId()).isNotNull();assertThat(available()).isFalse();
            probe();enable(g);var resumed=new OrderEditAdmissionService(db.source).recover(f.customer(),f.order(),command.submissionKey());
            assertThat(resumed.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);assertThat(resumed.admissionId()).isEqualTo(first.admissionId());
            assertThat(jdbc.queryForObject("select count(*) from order_edit_admissions where order_id=?",Integer.class,f.order())).isOne();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void callerCannotForgeProbeOrApprovedIdentity(){
        generation();db.rejects("23514",()->jdbc.update("update order_edit_runtime_control set probe_xid='3'::xid8,probe_identity=f125_operational_identity(),version=version+1 where id=1"));
        assertThat(available()).isFalse();
    }

}
