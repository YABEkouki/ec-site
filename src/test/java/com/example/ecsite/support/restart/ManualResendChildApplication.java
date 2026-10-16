package com.example.ecsite.support.restart;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import com.example.ecsite.EcSiteApplication;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import com.example.ecsite.service.payment.*;

/** Child does not own a container. Configuration comes exclusively from its parent. */
public final class ManualResendChildApplication {
    public static void main(String[] ignored) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.OFF);
        RestartProbe probe=new RestartProbe(Path.of(required("RESTART_PROBE")));
        var stage=new java.util.concurrent.atomic.AtomicReference<>(RestartProbe.Stage.CONFIGURATION);
        try {
            String run=required("RESTART_RUN"),id=required("RESTART_CONTAINER");UUID.fromString(run);
            boolean on=switch(required("RESTART_ON")){case "true"->true;case "false"->false;default->throw new IllegalArgumentException();};
            String mode=required("RESTART_MODE");if(!Set.of("CLAIMED","SENDING","NORMAL").contains(mode))throw new IllegalArgumentException();
            Map<String,Object> settings=new HashMap<>();
            for(String[] entry:new String[][]{{"spring.datasource.url","EC_TEST_DB_URL"},{"spring.datasource.username","EC_TEST_DB_USER"},{"spring.datasource.password","EC_TEST_DB_PASSWORD"},{"spring.flyway.url","EC_TEST_DB_URL"},{"spring.flyway.user","EC_TEST_DB_USER"},{"spring.flyway.password","EC_TEST_DB_PASSWORD"}})settings.put(entry[0],required(entry[1]));
            settings.put("spring.flyway.enabled","true");settings.put("spring.flyway.target","53");settings.put("spring.jpa.hibernate.ddl-auto","validate");settings.put("spring.jpa.show-sql","false");
            settings.put("app.payment.discrepancy-audit.notification.enabled",on);settings.put("app.payment.discrepancy-audit.notification.recipients","hidden-first@example.com");
            settings.put("app.payment.discrepancy-audit.notification.fixed-delay","10s");
            settings.put("app.payment.discrepancy-audit.enabled","false");settings.put("app.payment.reconciliation.enabled","false");
            settings.put("spring.devtools.restart.enabled","false");settings.put("server.address","127.0.0.1");settings.put("server.port","0");
            settings.put("payjp.api-base-url","http://127.0.0.1:1");settings.put("payjp.public-key","pk_test_restart");settings.put("payjp.secret-key","sk_test_restart");settings.put("payjp.webhook-token","restart_dummy");
            settings.put("spring.mail.host","127.0.0.1");settings.put("spring.mail.port","1");settings.put("spring.mail.test-connection","false");settings.put("spring.mail.username","");settings.put("spring.mail.password","");
            settings.put("PAYMENT_AUDIT_HANDLING_HMAC_KEY","");settings.put("PAYMENT_AUDIT_MANUAL_RESEND_HMAC_KEY","");
            settings.put("logging.level.root","OFF");settings.put("spring.main.banner-mode","off");
            var app=new SpringApplication(EcSiteApplication.class);app.setAdditionalProfiles("test");app.setAddCommandLineProperties(false);
            app.addInitializers(context->{
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("restart-parent",settings));
                context.addBeanFactoryPostProcessor(factory->{
                    IsolatedDatabaseGuard.verify(context.getEnvironment());
                    var inspection=DockerClientFactory.instance().client().inspectContainerCmd(id).exec();
                    if(!run.equals(inspection.getConfig().getLabels().get("ecsite.restart-run")))throw new IllegalStateException("Run/container mismatch");
                    var address=IsolatedDatabaseGuard.address((String)settings.get("spring.datasource.url"));
                    if(!Arrays.asList(inspection.getConfig().getEnv()).contains("POSTGRES_DB="+address.database())
                        || inspection.getNetworkSettings().getPorts().getBindings().entrySet().stream().noneMatch(e->e.getKey().getPort()==5432 && e.getValue()!=null && Arrays.stream(e.getValue()).anyMatch(b->b.getHostPortSpec().equals(Integer.toString(address.port())))))throw new IllegalStateException("Container endpoint mismatch");
                });
                ManualResendRestartTestConfiguration.install(context,probe,on,mode);
            });
            stage.set(RestartProbe.Stage.CONTEXT_START);
            try(var lifetime=new ObservedContext(app.run(),probe,stage)){
                var context=lifetime.context();
                stage.set(RestartProbe.Stage.CONTEXT_VERIFY);
                var jdbc=context.getBean(JdbcTemplate.class);
                if(!Integer.valueOf(53).equals(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success and version is not null",Integer.class)))throw new IllegalStateException("V53 required");
                if(!Mockito.mockingDetails(context.getBean(org.springframework.mail.javamail.JavaMailSender.class)).isMock() || !Mockito.mockingDetails(context.getBean(com.example.ecsite.payment.payjp.PayJpPaymentGateway.class)).isMock())throw new IllegalStateException("Outbound mocks required");
                if(on && !Mockito.mockingDetails(context.getBean(PaymentDiscrepancyAuditNotificationMailClient.class)).isMock())throw new IllegalStateException("Fake mail required");
                if(!on && !context.getBeansOfType(PaymentAuditNotificationSmtpExecutor.class).isEmpty())throw new IllegalStateException("OFF executor forbidden");
                var tx=context.getBean(PaymentAuditNotificationManualResendDeliveryTransaction.class);
                var wrapped=Mockito.mock(PaymentAuditNotificationManualResendDeliveryTransaction.class,invocation->{
                    Object result;
                    try{result=invocation.getMethod().invoke(tx,invocation.getArguments());}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    if(mode.equals("CLAIMED") && invocation.getMethod().getName().equals("claim") && result instanceof Optional<?> o && o.isPresent()){
                        probe.event("CLAIM_COMMITTED");probe.awaitPermission("RELEASE_CLAIM",Duration.ofMinutes(2));
                    }
                    return result;
                });
                ReflectionTestUtils.setField(context.getBean(PaymentAuditNotificationManualResendDeliveryService.class),"transactions",wrapped);
                var scheduler=context.getBean(ManualResendRestartTestConfiguration.ControlledScheduler.class);
                probe.event("READY");stage.set(RestartProbe.Stage.WAIT_START);probe.awaitPermission("START",Duration.ofMinutes(2));
                stage.set(RestartProbe.Stage.SCHEDULER_START);scheduler.authorizedStart();
                stage.set(RestartProbe.Stage.WAIT_STOP);probe.awaitPermission("STOP",Duration.ofMinutes(5));
                probe.event("STOP_RECEIVED");stage.set(RestartProbe.Stage.STOP_PROCESSING);probe.event("STOP_PROCESSING");
            }
            stage.set(RestartProbe.Stage.NORMAL_EXIT);probe.event("LAUNCHER_NORMAL_EXIT");
        }catch(Throwable failure){
            try{probe.exception(stage.get(),failure);probe.event("FAILED");}
            finally{System.exit(2);}
        }
    }
    /** Same try-with-resources close/suppression semantics; diagnostic events do not replace close. */
    private record ObservedContext(org.springframework.context.ConfigurableApplicationContext context,RestartProbe probe,
        java.util.concurrent.atomic.AtomicReference<RestartProbe.Stage> stage) implements AutoCloseable {
        @Override public void close(){
            var previous=stage.getAndSet(RestartProbe.Stage.CONTEXT_CLOSE);
            try{probe.event("CONTEXT_CLOSE_START");}
            finally{context.close();}
            probe.event("CONTEXT_CLOSE_COMPLETE");stage.set(previous);
        }
    }
    private static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalStateException("Parent setting missing");return value;}
}
