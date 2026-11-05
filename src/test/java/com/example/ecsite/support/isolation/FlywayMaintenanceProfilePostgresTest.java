package com.example.ecsite.support.isolation;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.w3c.dom.*;
import static org.assertj.core.api.Assertions.*;

/** Exercises the actual Maven plugin; never loads a Spring application context. */
class FlywayMaintenanceProfilePostgresTest {
    static final Path ROOT=Path.of("").toAbsolutePath();
    static final String PROFILE="f125-db-maintenance";

    @Test void profileIsExplicitAndHasNoLifecycleExecutions() throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var doc=factory.newDocumentBuilder().parse(ROOT.resolve("pom.xml").toFile());
        Element profile=null;
        var profiles=doc.getElementsByTagName("profile");
        for(int i=0;i<profiles.getLength();i++) {
            var e=(Element)profiles.item(i);
            if(PROFILE.equals(text(e,"id")))profile=e;
        }
        assertThat(profile).as("explicit maintenance profile").isNotNull();
        assertThat(profile.getElementsByTagName("activation").getLength()).isZero();
        assertThat(profile.getElementsByTagName("executions").getLength()).isZero();
        assertThat(text(profile,"url")).isEqualTo("${env.FLYWAY_URL}");
        assertThat(text(profile,"user")).isEqualTo("${env.FLYWAY_USER}");
        assertThat(text(profile,"password")).isEqualTo("${env.FLYWAY_PASSWORD}");
        assertThat(text(profile,"target")).isEqualTo("76");
        assertThat(text(profile,"cleanDisabled")).isEqualTo("true");
        for(String flag:List.of("baselineOnMigrate","outOfOrder","createSchemas"))assertThat(text(profile,flag)).isEqualTo("false");
    }

    @Test void missingConnectionFactsFailBeforeMavenStarts() throws Exception {
        for(String missing:List.of("FLYWAY_URL","FLYWAY_USER","FLYWAY_PASSWORD")) {
            var env=new HashMap<String,String>();
            env.put("FLYWAY_URL","jdbc:postgresql://127.0.0.1:1/not_a_database");
            env.put("FLYWAY_USER","not_a_user");env.put("FLYWAY_PASSWORD","unused");env.remove(missing);
            for(String empty:Arrays.asList(null,"","  ")) {
                if(empty==null)env.remove(missing);else env.put(missing,empty);
                var result=run(List.of("bash","scripts/run-f125-flyway.sh","info"),env);
                assertThat(result.code()).isEqualTo(2);
                assertThat(result.output()).contains("Required connection environment is missing").doesNotContain("Scanning for projects");
            }
        }
    }

    @Test void realPluginMigratesOnlyOwnedPostgresAndStopsAt76() throws Exception {
        String run=UUID.randomUUID().toString().replace("-","");
        try(var db=new PostgreSQLContainer("postgres:17").withDatabaseName("ecsite_it_f125_"+run)
            .withUsername("isolated_user").withPassword(UUID.randomUUID().toString())
            .withCommand("postgres","-c","track_commit_timestamp=on").withLabel("ecsite.isolated-test-run",run)) {
            db.start();
            var address=IsolatedDatabaseGuard.address(db.getJdbcUrl());
            assertThat(address.database()).isEqualTo(db.getDatabaseName());assertThat(address.port()).isNotEqualTo(5432);
            // Read identity inside the newly owned container before any host JDBC connection.
            var identity=db.execInContainer("psql","-X","-At","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d",db.getDatabaseName(),
                "-c","BEGIN READ ONLY; SELECT system_identifier::text FROM pg_control_system(); COMMIT;");
            assertThat(identity.getExitCode()).isZero();
            String cluster=identity.getStdout().lines().filter(s->s.matches("[0-9]+")).findFirst().orElseThrow();
            var environment=new MockEnvironment().withProperty("spring.datasource.url",db.getJdbcUrl()).withProperty("spring.flyway.url",db.getJdbcUrl())
                .withProperty("spring.datasource.username",db.getUsername()).withProperty("spring.flyway.user",db.getUsername())
                .withProperty("spring.datasource.password",db.getPassword()).withProperty("spring.flyway.password",db.getPassword());
            IsolatedDatabaseGuard.verify(environment);
            assertThat(query(db,"select system_identifier::text from pg_control_system()")).isEqualTo(cluster);
            var env=Map.of("FLYWAY_URL",db.getJdbcUrl(),"FLYWAY_USER",db.getUsername(),"FLYWAY_PASSWORD",db.getPassword());
            assertSuccess(run(List.of("bash","scripts/run-f125-flyway.sh","info"),env));
            var migrated=run(List.of("bash","scripts/run-f125-flyway.sh","migrate"),env);assertSuccess(migrated);
            assertThat(migrated.output()).contains("12.4.0").contains("76");
            assertSuccess(run(List.of("bash","scripts/run-f125-flyway.sh","validate"),env));
            assertThat(query(db,"select max(version::int)::text||':'||count(*)::text from flyway_schema_history where success")).isEqualTo("76:76");
            assertThat(query(db,"select state from order_edit_runtime_control where id=1")).isEqualTo("STOPPED");
            assertThat(query(db,"select f125_runtime_available()::text")).isEqualTo("false");
            // An actual V77 sentinel outside the repository proves the target ceiling.
            Path sentinel=Files.createTempDirectory("f125-flyway-target-");
            try {
                Files.writeString(sentinel.resolve("V77__must_not_run.sql"),"CREATE TABLE forbidden_target77(id integer);\n");
                var args=new ArrayList<>(List.of("./mvnw","--batch-mode","--no-transfer-progress","-P"+PROFILE,
                    "-Dflyway.locations=filesystem:"+ROOT.resolve("src/main/resources/db/migration")+",filesystem:"+sentinel,
                    "org.flywaydb:flyway-maven-plugin:12.4.0:migrate"));
                assertSuccess(run(args,env));
                assertThat(query(db,"select to_regclass('public.forbidden_target77') is null")).isEqualTo("t");
            } finally { Files.deleteIfExists(sentinel.resolve("V77__must_not_run.sql"));Files.deleteIfExists(sentinel); }
            // Ordinary lifecycle must not invoke Flyway, even with the profile selected and an unreachable endpoint.
            var dead=Map.of("FLYWAY_URL","jdbc:postgresql://127.0.0.1:1/not_a_database","FLYWAY_USER","unused","FLYWAY_PASSWORD","unused");
            for(var args:List.of(List.of("./mvnw","--batch-mode","--no-transfer-progress","compile"),
                List.of("./mvnw","--batch-mode","--no-transfer-progress","-P"+PROFILE,"compile"))) {
                var result=run(args,dead);assertSuccess(result);assertThat(result.output()).doesNotContain("flyway-maven-plugin:");
            }
            assertThat(query(db,"select count(*) from flyway_schema_history")).isEqualTo("76");
        }
    }

    static String text(Element parent,String tag){return parent.getElementsByTagName(tag).item(0).getTextContent().trim();}
    static String query(PostgreSQLContainer db,String sql)throws Exception {
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
            c.setReadOnly(true);try(var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getString(1);}
        }
    }
    record Result(int code,String output){}
    static Result run(List<String> command,Map<String,String> variables)throws Exception {
        var pb=new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k->k.startsWith("FLYWAY_")||k.startsWith("SPRING_")
            ||Set.of("MAVEN_ARGS","MAVEN_OPTS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS").contains(k));
        pb.environment().putAll(variables);
        var process=pb.start();
        var output=new StringBuilder();
        var reader=new Thread(()->{try(var in=process.inputReader()){String line;while((line=in.readLine())!=null){
            for(String value:variables.values())if(!value.isEmpty())line=line.replace(value,"[redacted]");
            synchronized(output){output.append(line).append('\n');}
        }}catch(Exception ignored){}});
        reader.start();
        if(!process.waitFor(240,TimeUnit.SECONDS)){process.destroyForcibly();reader.join(5000);throw new AssertionError("Maintenance Maven process timed out");}
        reader.join(5000);return new Result(process.exitValue(),output.toString());
    }
    static void assertSuccess(Result r){assertThat(r.code()).withFailMessage("Maintenance failed:\n%s",r.output()).isZero();}
}
