package com.example.ecsite.support.isolation;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IsolatedTestLauncherTest {
    static final String CLASS="com.example.ecsite.support.isolation.IsolatedTestSmokeTest";
    static final IsolatedDatabaseGuard.Connection DB=new IsolatedDatabaseGuard.Connection(
        "jdbc:postgresql://127.0.0.1:5432/ecsite_test","ecuser","unit-only-placeholder");
    @Test void classesAreExplicitAndResetIsOptIn() {
        var plan=IsolatedTestLauncher.plan(new String[]{CLASS});
        assertThat(plan.classes()).containsExactly(CLASS);
        assertThat(plan.resetTestDb()).isFalse();assertThat(plan.testcontainers()).isFalse();
        assertThat(IsolatedTestLauncher.plan(new String[]{"--reset-test-db",CLASS}).resetTestDb()).isTrue();
        assertThat(IsolatedTestLauncher.plan(new String[]{"--testcontainers",CLASS}).testcontainers()).isTrue();
        for(String[] args:List.of(new String[]{},new String[]{"*Test"},new String[]{"-Dtest="+CLASS},
            new String[]{CLASS,CLASS},new String[]{"--testcontainers","--reset-test-db",CLASS}))
            assertThatThrownBy(()->IsolatedTestLauncher.plan(args)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mavenCommandIsPinnedAndCannotInjectExtraGoals() {
        var args=IsolatedTestLauncher.command(CLASS,false,"/tmp/unit-settings.xml");
        assertThat(args).contains("-o","-Dtest="+CLASS,"-DforkCount=1","-DreuseForks=false",
            "-Djunit.jupiter.execution.parallel.enabled=false","-Dspring.profiles.active=test","-Decsite.test.database-mode=shared");
        assertThat(args.getLast()).isEqualTo("test");
        assertThatThrownBy(()->IsolatedTestLauncher.command(CLASS+",OtherTest",false,"x")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void childEnvironmentKeepsOnlyExplicitTestFacts() {
        var env=IsolatedTestLauncher.childEnvironment(Map.of("PATH","/bin","SPRING_APPLICATION_JSON","private",
            "JAVA_TOOL_OPTIONS","private","PAYJP_SECRET_KEY","private","EC_TEST_DB_PASSWORD","stale"),DB);
        assertThat(env).doesNotContainKeys("SPRING_APPLICATION_JSON","JAVA_TOOL_OPTIONS","PAYJP_SECRET_KEY");
        assertThat(env.get("EC_TEST_DB_PASSWORD")).isEqualTo(DB.password());
        assertThat(env.get("SPRING_DATASOURCE_URL")).isEqualTo(env.get("SPRING_FLYWAY_URL"));
        assertThat(env.get("SPRING_PROFILES_ACTIVE")).isEqualTo("test");
    }
    @Test void missingOrForeignConnectionFactsFailWithoutConnecting() {
        assertThatThrownBy(()->IsolatedTestLauncher.sharedConnection(Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->IsolatedTestLauncher.sharedConnection(Map.of("EC_TEST_DB_URL",DB.url().replace("ecsite_test","ecsite"),
            "EC_TEST_DB_USER",DB.user(),"EC_TEST_DB_PASSWORD",DB.password()))).isInstanceOf(IllegalStateException.class);
        assertThat(IsolatedTestLauncher.sharedConnection(Map.of("EC_TEST_DB_URL",DB.url(),"EC_TEST_DB_USER",DB.user(),
            "EC_TEST_DB_PASSWORD",DB.password()))).isEqualTo(DB);
    }
    @Test void resetRejectsForeignConfiguredDatabaseBeforeUsingJdbc() throws Exception {
        var connection=mock(Connection.class);
        assertThatThrownBy(()->IsolatedTestLauncher.resetTestDatabase(new IsolatedDatabaseGuard.Connection(
            DB.url().replace("ecsite_test","ecsite"),DB.user(),DB.password()),connection)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(connection);
    }
    record Jdbc(Connection connection,Statement statement){}
    Jdbc jdbc(String actualDatabase,long others)throws Exception {
        var c=mock(Connection.class);var s=mock(Statement.class);var identity=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s);when(s.executeQuery("select current_database(), current_user")).thenReturn(identity);
        when(identity.next()).thenReturn(true);when(identity.getString(1)).thenReturn(actualDatabase);when(identity.getString(2)).thenReturn(DB.user());
        var sessions=mock(ResultSet.class);when(s.executeQuery(IsolatedTestLauncher.OTHER_SESSIONS)).thenReturn(sessions);
        when(sessions.next()).thenReturn(true);when(sessions.getLong(1)).thenReturn(others);
        var schemas=mock(ResultSet.class);when(s.executeQuery(IsolatedTestLauncher.USER_SCHEMAS)).thenReturn(schemas);
        when(schemas.next()).thenReturn(true,true,false);when(schemas.getString(1)).thenReturn("public","quoted\"schema");
        return new Jdbc(c,s);
    }
    @Test void resetChecksActualDatabaseAndConcurrentSessionsBeforeDdl()throws Exception {
        var wrong=jdbc("ecsite",0);
        assertThatThrownBy(()->IsolatedTestLauncher.resetTestDatabase(DB,wrong.connection())).isInstanceOf(IllegalStateException.class);
        verify(wrong.statement(),never()).execute(anyString());
        var busy=jdbc("ecsite_test",1);
        assertThatThrownBy(()->IsolatedTestLauncher.resetTestDatabase(DB,busy.connection())).isInstanceOf(IllegalStateException.class);
        verify(busy.statement(),never()).execute(anyString());
    }
    @Test void resetQuotesSchemasAndCommitsOnlyThisTestDatabase()throws Exception {
        var db=jdbc("ecsite_test",0);IsolatedTestLauncher.resetTestDatabase(DB,db.connection());
        var order=inOrder(db.connection(),db.statement());
        order.verify(db.statement()).executeQuery("select current_database(), current_user");
        order.verify(db.statement()).execute("DROP SCHEMA \"public\" CASCADE");
        order.verify(db.connection()).commit();
        order.verify(db.statement()).execute("DROP SCHEMA \"quoted\"\"schema\" CASCADE");
        order.verify(db.connection()).commit();
        order.verify(db.statement()).execute("CREATE SCHEMA public AUTHORIZATION CURRENT_USER");
        order.verify(db.connection()).commit();verify(db.connection(),never()).rollback();
    }
    @Test void laterResetFailureKeepsPriorSchemaCommitAndDoesNotRetry()throws Exception {
        var db=jdbc("ecsite_test",0);
        doThrow(new SQLException("synthetic later failure")).when(db.statement()).execute("DROP SCHEMA \"quoted\"\"schema\" CASCADE");
        assertThatThrownBy(()->IsolatedTestLauncher.resetTestDatabase(DB,db.connection())).isInstanceOf(SQLException.class);
        verify(db.connection(),times(1)).commit();verify(db.connection()).rollback();
        verify(db.statement(),times(1)).execute("DROP SCHEMA \"quoted\"\"schema\" CASCADE");
        verify(db.statement(),never()).execute("CREATE SCHEMA public AUTHORIZATION CURRENT_USER");
    }
    @Test void resetFailureRollsBackWithoutRetry()throws Exception {
        var db=jdbc("ecsite_test",0);doThrow(new SQLException("synthetic failure")).when(db.statement()).execute("DROP SCHEMA \"public\" CASCADE");
        assertThatThrownBy(()->IsolatedTestLauncher.resetTestDatabase(DB,db.connection())).isInstanceOf(SQLException.class);
        verify(db.statement(),times(1)).execute("DROP SCHEMA \"public\" CASCADE");verify(db.connection()).rollback();verify(db.connection(),never()).commit();
    }
    @Test void originalWorkspaceAndSymlinkedTargetAreRejected(@TempDir Path temp)throws Exception {
        var original=Files.createDirectory(temp.resolve("original"));var copy=Files.createDirectory(temp.resolve("copy"));
        assertThatThrownBy(()->IsolatedTestLauncher.requireIndependentCopy(original,original)).isInstanceOf(IllegalStateException.class);
        IsolatedTestLauncher.requireIndependentCopy(copy,original);
        Files.createSymbolicLink(copy.resolve("target"),original);
        assertThatThrownBy(()->IsolatedTestLauncher.requireIndependentCopy(copy,original)).isInstanceOf(IllegalStateException.class);
    }
    @Test void testProfileDisablesSideEffectsWithoutConnectionDefaults()throws Exception {
        var props=new Properties();try(var in=Files.newInputStream(Path.of("src/test/resources/application-test.properties"))){props.load(in);}
        assertThat(props.getProperty("spring.datasource.url")).isEqualTo("${EC_TEST_DB_URL}");
        assertThat(props.getProperty("spring.flyway.url")).isEqualTo(props.getProperty("spring.datasource.url"));
        assertThat(props.getProperty("spring.flyway.user")).isEqualTo(props.getProperty("spring.datasource.username"));
        assertThat(props.getProperty("spring.flyway.password")).isEqualTo(props.getProperty("spring.datasource.password"));
        for(String flag:List.of("app.payment.reconciliation.enabled","app.payment.discrepancy-audit.enabled",
            "app.payment.discrepancy-audit.notification.enabled","app.payment.discrepancy-audit.notification.manual-resend-scheduler-enabled"))
            assertThat(props.getProperty(flag)).isEqualTo("false");
        assertThat(props.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(props.getProperty("spring.autoconfigure.exclude")).isEqualTo("org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration");
    }
    private Process shortProcess(String script)throws Exception {
        return new ProcessBuilder("python3","-c",script).redirectErrorStream(true).start();
    }
    @Test void ownedProcessNormalExitKeepsExitCode()throws Exception {
        var process=shortProcess("print('normal')");
        assertThat(IsolatedTestLauncher.runOwned(process,java.time.Duration.ofSeconds(5),line->{})).isZero();
        assertThat(process.isAlive()).isFalse();
    }
    private void abnormalProcess(boolean interrupt,boolean ignoreTerm)throws Exception {
        var unrelated=shortProcess("import time; time.sleep(20)");
        var process=shortProcess("import subprocess; p=subprocess.Popen(['python3','-c',"+
            "\"import signal,time; "+(ignoreTerm?"signal.signal(signal.SIGTERM,signal.SIG_IGN); ":"")+"print('ready',flush=True); time.sleep(20)\"]); p.wait()");
        var childPid=new java.util.concurrent.atomic.AtomicLong();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var ready=new java.util.concurrent.CountDownLatch(1);
        var worker=new Thread(()->{try {
            IsolatedTestLauncher.runOwned(process,java.time.Duration.ofMillis(1500),line->{
                process.descendants().findFirst().ifPresent(h->childPid.set(h.pid()));ready.countDown();
            });
        }catch(Throwable e){failure.set(e);}});
        try {
            worker.start();assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(childPid.get()).isPositive();
            if(interrupt)worker.interrupt();
            worker.join(12000);assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(interrupt?InterruptedException.class:java.util.concurrent.TimeoutException.class);
            assertThat(process.isAlive()).isFalse();
            assertThat(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(unrelated.isAlive()).isTrue();
        } finally {
            worker.interrupt();
            if(childPid.get()>0)ProcessHandle.of(childPid.get()).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
            process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);
            unrelated.destroyForcibly();unrelated.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);
            worker.join(12000);
        }
    }
    @Test void interruptionStopsOwnedDescendantsOnly()throws Exception {abnormalProcess(true,false);}
    @Test void timeoutStopsOwnedDescendantsOnly()throws Exception {abnormalProcess(false,false);}
    @Test void termIgnoringChildIsForcedToExit()throws Exception {abnormalProcess(false,true);}
    @Test void outputExceptionCleansUpOwnedProcess()throws Exception {
        var process=shortProcess("import time; print('ready',flush=True); time.sleep(20)");
        try {
            assertThatThrownBy(()->IsolatedTestLauncher.runOwned(process,java.time.Duration.ofSeconds(5),line->{throw new IllegalStateException("synthetic");}))
                .isInstanceOf(IllegalStateException.class);
            assertThat(process.isAlive()).isFalse();
        }finally{process.destroyForcibly();process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);}
    }
}
