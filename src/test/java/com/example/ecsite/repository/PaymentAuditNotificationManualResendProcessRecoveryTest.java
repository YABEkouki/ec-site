package com.example.ecsite.repository;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import com.example.ecsite.support.restart.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in only, via run-isolated-tests.sh -Decsite.restart.enabled=true -Dtest=... . */
@EnabledIfSystemProperty(named="ecsite.restart.enabled",matches="true")
@Timeout(value=8,unit=TimeUnit.MINUTES)
class PaymentAuditNotificationManualResendProcessRecoveryTest {
    private final List<Process> children=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Path> probes=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<Long,RestartProbe> childDiagnostics=new java.util.concurrent.ConcurrentHashMap<>();
    private final String run=UUID.randomUUID().toString();
    private PostgreSQLContainer db;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private Thread emergencyCleanup;
    record Child(Process process,RestartProbe probe) {}
    @BeforeEach void prepare() {
        try {
            db=new PostgreSQLContainer("postgres:17").withDatabaseName("restart118_"+run.replace("-",""))
                .withUsername("restart_user").withPassword(UUID.randomUUID().toString()).withLabel("ecsite.restart-run",run);
            emergencyCleanup=new Thread(()->{for(Process child:children)if(child.isAlive()){child.destroyForcibly();try{child.waitFor(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(db!=null)db.stop();},"restart118-parent-cleanup");
            Runtime.getRuntime().addShutdownHook(emergencyCleanup);
            db.start();
            var properties=new HashMap<String,Object>();
            properties.put("spring.datasource.url",db.getJdbcUrl());properties.put("spring.datasource.username",db.getUsername());properties.put("spring.datasource.password",db.getPassword());
            properties.put("spring.flyway.url",db.getJdbcUrl());properties.put("spring.flyway.user",db.getUsername());properties.put("spring.flyway.password",db.getPassword());
            var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("restart-parent",properties));IsolatedDatabaseGuard.verify(env);
            Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("52").load().migrate();
            var source=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        } catch(RuntimeException e){cleanup();throw e;}
    }
    private long seed(){return transaction.execute(s->ManualResendRestartFixtures.seed(jdbc,db.getDatabaseName()));}
    private Child start(boolean on,String mode) throws Exception {
        assertThat(children.stream().noneMatch(Process::isAlive)).as("previous child must exit before restart").isTrue();
        Path directory=Files.createTempDirectory(Path.of("/tmp"),"restart118-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));probes.add(directory);
        var probe=new RestartProbe(directory);
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String agent=Path.of(org.mockito.Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-XX:+UseSerialGC","-Dspring.devtools.restart.enabled=false","-javaagent:"+agent,"-cp",classpath,ManualResendChildApplication.class.getName());
        // No inherited development Spring settings, Java injection options or credentials.
        var inherited=new HashMap<>(builder.environment());builder.environment().clear();
        for(String name:List.of("PATH","HOME","DOCKER_HOST","DOCKER_TLS_VERIFY","DOCKER_CERT_PATH","DOCKER_CONFIG","TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE","TESTCONTAINERS_HOST_OVERRIDE"))if(inherited.containsKey(name))builder.environment().put(name,inherited.get(name));
        builder.environment().putAll(Map.of("EC_TEST_DB_URL",db.getJdbcUrl(),"EC_TEST_DB_USER",db.getUsername(),"EC_TEST_DB_PASSWORD",db.getPassword(),"RESTART_RUN",run,"RESTART_CONTAINER",db.getContainerId(),"RESTART_PROBE",directory.toString(),"RESTART_ON",Boolean.toString(on),"RESTART_MODE",mode));
        // Child exception/framework logs can contain connection data; never forward them.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();children.add(process);childDiagnostics.put(process.pid(),probe);probe.awaitEvent("READY",1,process,Duration.ofSeconds(90));return new Child(process,probe);
    }
    private void crash(Child child) throws Exception {child.process().destroyForcibly();assertThat(child.process().waitFor(15,TimeUnit.SECONDS)).isTrue();unlocked();}
    private void unlocked(){transaction.executeWithoutResult(s->{jdbc.execute("set local lock_timeout='1s'");jdbc.queryForList("select id from payment_audit_notifications for update");jdbc.queryForList("select id from payment_audit_notification_manual_resends for update");jdbc.queryForList("select id from payment_audit_notification_manual_resend_attempts for update");});}
    private void expire(long id){jdbc.update("update payment_audit_notification_manual_resends set lease_until=clock_timestamp()-interval '1 second' where id=? and status in ('CLAIMED','SENDING')",id);}
    private String status(long id){return jdbc.queryForObject("select status from payment_audit_notification_manual_resends where id=?",String.class,id);}
    private void awaitStatus(long id,String expected,Child child) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(!expected.equals(status(id))){if(!child.process().isAlive() || child.probe().count("FAILED")>0 || System.nanoTime()>=end)throw new IllegalStateException("Recovery state timeout");Thread.sleep(25);}
    }
    @Test void claimedCrashRecoversAndSendsExactlyOnce() throws Exception {
        long id=seed();var fingerprints=ManualResendRestartFixtures.fingerprints(jdbc);var old=start(true,"CLAIMED");old.probe().permit("DELIVER");old.probe().permit("START");
        old.probe().awaitEvent("CLAIM_COMMITTED",1,old.process(),Duration.ofSeconds(40));assertThat(status(id)).isEqualTo("CLAIMED");assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_manual_resend_attempts",Long.class)).isZero();
        UUID oldToken=jdbc.queryForObject("select claim_token from payment_audit_notification_manual_resends where id=?",UUID.class,id);assertThat(oldToken).isNotNull();assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_manual_resends where id=? and claimed_by is not null and lease_until>clock_timestamp()",Long.class,id)).isEqualTo(1);assertThat(old.probe().count("SMTP_ENTERED")).isZero();unlocked();crash(old);expire(id);
        var next=start(true,"NORMAL");next.probe().permit("START");awaitStatus(id,"PENDING",next);
        assertThat(jdbc.queryForObject("select claim_token from payment_audit_notification_manual_resends where id=?",UUID.class,id)).isNull();
        next.probe().permit("DELIVER");awaitStatus(id,"SENT",next);next.probe().awaitEvent("TICK",3,next.process(),Duration.ofSeconds(40));
        assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_manual_resend_attempts where manual_resend_id=? and result='SUCCESS' and claim_token<>?",Long.class,id,oldToken)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_manual_resend_attempts",Long.class)).isEqualTo(1);assertThat(next.probe().count("SMTP_ENTERED")).isEqualTo(1);assertThat(ManualResendRestartFixtures.fingerprints(jdbc)).isEqualTo(fingerprints);
    }
    @Test void sendingCrashRecoversWhileOffAndNeverResends() throws Exception {
        long id=seed();var fingerprints=ManualResendRestartFixtures.fingerprints(jdbc);var old=start(true,"SENDING");old.probe().permit("DELIVER");old.probe().permit("START");old.probe().awaitEvent("SMTP_ENTERED",1,old.process(),Duration.ofSeconds(40));
        assertThat(status(id)).isEqualTo("SENDING");assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_manual_resend_attempts a join payment_audit_notification_manual_resends r on r.id=a.manual_resend_id where r.id=? and a.result='IN_PROGRESS' and a.claim_token=r.claim_token",Long.class,id)).isEqualTo(1);unlocked();crash(old);expire(id);
        var off=start(false,"NORMAL");off.probe().permit("START");awaitStatus(id,"UNKNOWN",off);
        assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_manual_resend_attempts where manual_resend_id=? and result='UNKNOWN' and result_reason_code='CLAIM_LOST'",Long.class,id)).isEqualTo(1);assertThat(off.probe().count("SMTP_ENTERED")).isZero();
        off.probe().permit("STOP");assertThat(off.process().waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(off.process().exitValue()).isZero();unlocked();
        var on=start(true,"NORMAL");on.probe().permit("DELIVER");on.probe().permit("START");on.probe().awaitEvent("TICK",3,on.process(),Duration.ofSeconds(45));
        assertThat(status(id)).isEqualTo("UNKNOWN");assertThat(on.probe().count("SMTP_ENTERED")).isZero();assertThat(old.probe().count("SMTP_ENTERED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_manual_resend_attempts",Long.class)).isEqualTo(1);assertThat(ManualResendRestartFixtures.fingerprints(jdbc)).isEqualTo(fingerprints);
    }
    @AfterEach void cleanup(){
        boolean remaining=false;
        for(Process child:children){if(child.isAlive())child.destroyForcibly();try{if(!child.waitFor(15,TimeUnit.SECONDS))remaining=true;}catch(InterruptedException e){Thread.currentThread().interrupt();remaining=true;}}
        // Capture diagnostics before probe directories are removed, including assertion failures.
        for(var entry:childDiagnostics.entrySet())try{
            System.out.println("Restart child PID="+entry.getKey()+" run="+run+" diagnostics="+entry.getValue().diagnostics());
        }catch(Exception failure){System.out.println("Restart child diagnostics unavailable; exceptionType="+failure.getClass().getName());}
        try{if(db!=null)db.stop();}finally{for(Path directory:probes)try(var files=Files.walk(directory)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(Exception e){remaining=true;}}
        if(emergencyCleanup!=null){try{Runtime.getRuntime().removeShutdownHook(emergencyCleanup);}catch(IllegalStateException ignored){}emergencyCleanup=null;}
        if(remaining)throw new IllegalStateException("Restart harness cleanup incomplete");
    }
}
