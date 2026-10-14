package com.example.ecsite.support.browser;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.security.SecureRandom;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.EcSiteApplication;
import com.example.ecsite.support.isolation.IsolationContextCustomizerFactory;

/** Test-classpath-only launcher. Its lifetime owns both the app and the disposable DB. */
public final class IsolatedBrowserLauncher {
    @SpringBootTest private static class GuardMarker {}
    public static void main(String[] args) {
        boolean check=args.length==1 && args[0].equals("--check");
        if(!check && !(args.length==1 && args[0].equals("--serve"))) throw new IllegalArgumentException("Use --check or --serve");
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        System.setProperty("spring.devtools.restart.enabled","false");
        int result=0;
        Path privateDir=null;
        try(var db=new PostgreSQLContainer("postgres:17").withDatabaseName("browser117_"+UUID.randomUUID().toString().replace("-",""))
            .withUsername("browser117_user").withPassword(randomSecret()).withLabel("ecsite.browser117","true")) {
            db.start();
            Runtime.getRuntime().addShutdownHook(new Thread(db::stop,"browser117-db-cleanup"));
            var settings=new HashMap<String,Object>();
            settings.put("spring.datasource.url",db.getJdbcUrl());settings.put("spring.datasource.username",db.getUsername());settings.put("spring.datasource.password",db.getPassword());
            settings.put("spring.flyway.url",db.getJdbcUrl());settings.put("spring.flyway.user",db.getUsername());settings.put("spring.flyway.password",db.getPassword());
            settings.put("spring.flyway.enabled","true");settings.put("spring.jpa.hibernate.ddl-auto","validate");
            settings.put("server.address","127.0.0.1");settings.put("server.port","0");
            settings.put("app.payment.discrepancy-audit.notification.enabled","false");settings.put("app.payment.discrepancy-audit.enabled","false");settings.put("app.payment.reconciliation.enabled","false");
            settings.put("PAYMENT_AUDIT_HANDLING_HMAC_KEY",randomSecret());settings.put("payjp.api-base-url","http://127.0.0.1:1");
            settings.put("spring.mail.host","127.0.0.1");settings.put("spring.mail.port","1");
            settings.put("spring.mail.username","");settings.put("spring.mail.password","");settings.put("spring.mail.test-connection","false");
            settings.put("payjp.public-key","pk_test_browser_dummy");settings.put("payjp.secret-key","sk_test_browser_dummy");settings.put("payjp.webhook-token","browser_dummy");
            settings.put("debug","false");settings.put("trace","false");
            for(String category:List.of("root","org.springframework","org.hibernate","org.flywaydb","org.testcontainers","com.zaxxer.hikari")) settings.put("logging.level."+category,"WARN");
            var application=new SpringApplication(EcSiteApplication.class);
            var environment=new org.springframework.web.context.support.StandardServletEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("browser117-isolated",settings));
            application.setEnvironment(environment);
            application.setAdditionalProfiles("test");application.setAddCommandLineProperties(false);
            application.addInitializers(context->{
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("browser117-isolated",settings));
                new IsolationContextCustomizerFactory().createContextCustomizer(GuardMarker.class,List.of()).customizeContext(context,null);
            });
            try(var context=application.run()) {
                int port=((WebServerApplicationContext)context).getWebServer().getPort();
                String base="http://127.0.0.1:"+port;
                var jdbc=context.getBean(JdbcTemplate.class);
                if(!jdbc.queryForObject("select current_database()",String.class).equals(db.getDatabaseName())) throw new IllegalStateException();
                if(jdbc.queryForObject("select max(version::int) from flyway_schema_history where version is not null and success",Integer.class)!=52) throw new IllegalStateException();
                if(!context.getBeansOfType(com.example.ecsite.service.payment.PaymentAuditNotificationScheduler.class).isEmpty()
                    || !context.getBeansOfType(com.example.ecsite.service.payment.PaymentDiscrepancyAuditScheduler.class).isEmpty()
                    || !context.getBeansOfType(com.example.ecsite.service.payment.PaymentReconciliationScheduler.class).isEmpty()) throw new IllegalStateException();
                if(!org.mockito.Mockito.mockingDetails(context.getBean(org.springframework.mail.javamail.JavaMailSender.class)).isMock()
                    || !org.mockito.Mockito.mockingDetails(context.getBean(com.example.ecsite.payment.payjp.PayJpPaymentGateway.class)).isMock()) throw new IllegalStateException();
                String username="browser_admin_"+UUID.randomUUID().toString().replace("-","");
                String password=randomSecret();
                BrowserNotificationFixtures.seed(jdbc,new TransactionTemplate(context.getBean(PlatformTransactionManager.class)),context.getBean(PasswordEncoder.class),username,password,base);
                if(jdbc.queryForObject("select count(*) from payment_audit_notifications",Long.class)!=24) throw new IllegalStateException();
                var admin=context.getBean(com.example.ecsite.service.AdminUserDetailsService.class).loadUserByUsername(username);
                if(!admin.isEnabled() || admin.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN"))
                    || !context.getBean(PasswordEncoder.class).matches(password,admin.getPassword())) throw new IllegalStateException();
                privateDir=Files.createTempDirectory(Path.of("/tmp"),"feature117-browser-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Path access=privateDir.resolve("access.txt");
                Files.writeString(access,"URL="+base+"/admin/login\nUSERNAME="+username+"\nPASSWORD="+password+"\n",StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(access,PosixFilePermissions.fromString("rw-------"));
                Path cleanupDir=privateDir;
                Runtime.getRuntime().addShutdownHook(new Thread(()->removePrivateFiles(cleanupDir),"browser117-credentials-cleanup"));
                System.out.println("Browser117 isolated environment ready: 24 notifications; schema V52 validated; schedulers OFF.");
                if(check) System.out.println("Limited startup check complete; app and database will now be removed.");
                else {
                    System.out.println("Login information is in a private file: "+access+" (not printed here). Stop with Ctrl-C.");
                    new java.util.concurrent.CountDownLatch(1).await();
                }
            }
        } catch(Exception failure) {
            System.err.println("Browser117 preparation failed; no development database fallback was attempted.");
            result=2;
        } finally {
            if(privateDir!=null) removePrivateFiles(privateDir);
        }
        if(result!=0) System.exit(result);
    }
    private static void removePrivateFiles(Path directory) {
        try {Files.deleteIfExists(directory.resolve("access.txt"));Files.deleteIfExists(directory);} catch(Exception ignored) {}
    }
    private static String randomSecret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
}
