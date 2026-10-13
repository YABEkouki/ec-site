package com.example.ecsite.support.isolation;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Keeps Testcontainers/Ryuk alive while the child Maven process runs. */
public final class IsolatedTestLauncher {
    public static void main(String[] args) {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        int result=2;
        try {
            if(args.length==0) throw new IllegalArgumentException();
            String run=UUID.randomUUID().toString().replace("-","");
            try(var db=new PostgreSQLContainer("postgres:17").withDatabaseName("isolated_"+run)
                .withUsername("isolated_user").withPassword(UUID.randomUUID().toString())
                .withLabel("ecsite.isolated-test-run",run)) {
                db.start();
                Runtime.getRuntime().addShutdownHook(new Thread(db::stop,"isolated-db-cleanup"));
                var builder=new ProcessBuilder(args).redirectErrorStream(true);
                var env=builder.environment();
                env.put("EC_TEST_DB_URL",db.getJdbcUrl());env.put("EC_TEST_DB_USER",db.getUsername());env.put("EC_TEST_DB_PASSWORD",db.getPassword());
                // Clear higher-precedence stale development overrides. DynamicPropertySource still wins.
                env.remove("SPRING_APPLICATION_JSON");
                env.put("SPRING_DATASOURCE_URL",db.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",db.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",db.getPassword());
                env.put("SPRING_FLYWAY_URL",db.getJdbcUrl());env.put("SPRING_FLYWAY_USER",db.getUsername());env.put("SPRING_FLYWAY_PASSWORD",db.getPassword());
                var child=builder.start();
                Runtime.getRuntime().addShutdownHook(new Thread(()->{
                    child.destroy();try{if(!child.waitFor(5,TimeUnit.SECONDS))child.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                },"isolated-maven-cleanup"));
                try(var output=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while((line=output.readLine())!=null) {
                        // Do not expose the common DB endpoint or credentials in child Maven logs.
                        System.out.println(line.replace(db.getJdbcUrl(),"[isolated-db]").replace(db.getPassword(),"[redacted]").replace(db.getUsername(),"[isolated-user]"));
                    }
                }
                result=child.waitFor();
            }
        } catch(Exception e) { System.err.println("Isolated test launch failed; no development database fallback was attempted"); }
        System.exit(result);
    }
}
