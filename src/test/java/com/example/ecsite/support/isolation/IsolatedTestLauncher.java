package com.example.ecsite.support.isolation;

import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Existing test runner: shared ecsite_test by default; explicit container mode for server tests. */
public final class IsolatedTestLauncher {
    static final String OTHER_SESSIONS="select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()";
    static final String USER_SCHEMAS="select nspname from pg_namespace where nspname !~ '^pg_' and nspname<>'information_schema' order by nspname";
    record Plan(boolean testcontainers,boolean resetTestDb,List<String> classes) {}
    static Plan plan(String[] args) {
        boolean containers=false,reset=false;var classes=new ArrayList<String>();
        for(String arg:args) {
            if(arg.equals("--testcontainers")) {if(containers)throw new IllegalArgumentException();containers=true;}
            else if(arg.equals("--reset-test-db")) {if(reset)throw new IllegalArgumentException();reset=true;}
            else {requireClass(arg);if(classes.contains(arg))throw new IllegalArgumentException();classes.add(arg);}
        }
        if(classes.isEmpty() || containers && reset)throw new IllegalArgumentException();
        return new Plan(containers,reset,List.copyOf(classes));
    }
    private static void requireClass(String value) {
        if(value==null || !value.matches("com\\.example\\.ecsite(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+Test"))throw new IllegalArgumentException("Explicit test class names are required");
    }
    static MockEnvironment environment(IsolatedDatabaseGuard.Connection db,boolean containers) {
        var env=new MockEnvironment();env.setActiveProfiles("test");env.setProperty("ecsite.test.database-mode",containers?"testcontainers":"shared");
        env.setProperty("spring.datasource.url",Objects.toString(db.url(),""));env.setProperty("spring.flyway.url",Objects.toString(db.url(),""));
        env.setProperty("spring.datasource.username",Objects.toString(db.user(),""));env.setProperty("spring.flyway.user",Objects.toString(db.user(),""));
        env.setProperty("spring.datasource.password",Objects.toString(db.password(),""));env.setProperty("spring.flyway.password",Objects.toString(db.password(),""));
        return env;
    }
    static IsolatedDatabaseGuard.Connection sharedConnection(Map<String,String> env) {
        var db=new IsolatedDatabaseGuard.Connection(env.get("EC_TEST_DB_URL"),env.get("EC_TEST_DB_USER"),env.get("EC_TEST_DB_PASSWORD"));
        return IsolatedDatabaseGuard.configuration(environment(db,false));
    }
    static void requireIndependentCopy(Path project,Path original)throws IOException {
        if(project.toRealPath().equals(original.toRealPath()) || Files.isSymbolicLink(project.resolve("target")))throw new IllegalStateException("An independent test copy is required");
    }
    static List<String> command(String testClass,boolean containers,String settings) {
        requireClass(testClass);if(settings==null || settings.isBlank())throw new IllegalArgumentException("Explicit Maven settings required");
        var args=new ArrayList<>(List.of("./mvnw","-o","--batch-mode","--no-transfer-progress","-s",settings,"-gs",settings,
            "-Dtest="+testClass,"-Dspring.profiles.active=test","-Decsite.test.database-mode="+(containers?"testcontainers":"shared"),
            "-DforkCount=1","-DreuseForks=false","-Djunit.jupiter.execution.parallel.enabled=false","-DfailIfNoTests=true",
            "-DargLine=-XX:+UseSerialGC"));
        for(String key:List.of("app.payment.reconciliation.enabled","app.payment.discrepancy-audit.enabled",
            "app.payment.discrepancy-audit.notification.enabled","app.payment.discrepancy-audit.notification.manual-resend-scheduler-enabled"))args.add("-D"+key+"=false");
        args.add("test");return List.copyOf(args);
    }
    static Map<String,String> childEnvironment(Map<String,String> parent,IsolatedDatabaseGuard.Connection db) {
        var env=new HashMap<String,String>();
        for(String key:List.of("PATH","JAVA_HOME","HOME","USER","LANG","LC_ALL","EC_TEST_ORIGINAL_PROJECT","EC_TEST_MAVEN_SETTINGS"))
            if(parent.containsKey(key))env.put(key,parent.get(key));
        env.put("MAVEN_SKIP_RC","true");env.put("MAVEN_OPTS","-XX:+UseSerialGC");env.put("SPRING_PROFILES_ACTIVE","test");
        env.put("EC_TEST_DB_URL",db.url());env.put("EC_TEST_DB_USER",db.user());env.put("EC_TEST_DB_PASSWORD",db.password());
        env.put("SPRING_DATASOURCE_URL",db.url());env.put("SPRING_DATASOURCE_USERNAME",db.user());env.put("SPRING_DATASOURCE_PASSWORD",db.password());
        env.put("SPRING_FLYWAY_URL",db.url());env.put("SPRING_FLYWAY_USER",db.user());env.put("SPRING_FLYWAY_PASSWORD",db.password());return env;
    }
    /** Opt-in destructive operation, only after configured AND actual database/user checks. No retry. */
    static void resetTestDatabase(IsolatedDatabaseGuard.Connection expected,Connection connection)throws SQLException {
        IsolatedDatabaseGuard.requireShared(expected);
        IsolatedDatabaseGuard.assertConnected(expected,connection);
        try(var statement=connection.createStatement()) {
            try(var sessions=statement.executeQuery(OTHER_SESSIONS)) {
                if(!sessions.next() || sessions.getLong(1)!=0)throw new IllegalStateException("Other test database sessions are present");
            }
            connection.setReadOnly(false);connection.setAutoCommit(false);
            try {
                var schemas=new ArrayList<String>();
                try(var rows=statement.executeQuery(USER_SCHEMAS)){while(rows.next())schemas.add(rows.getString(1));}
                for(String schema:schemas) {
                    statement.execute("SET LOCAL lock_timeout='5s'");
                    statement.execute("DROP SCHEMA \""+schema.replace("\"","\"\"")+"\" CASCADE");
                    connection.commit();
                }
                statement.execute("SET LOCAL lock_timeout='5s'");
                statement.execute("CREATE SCHEMA public AUTHORIZATION CURRENT_USER");connection.commit();
            } catch(SQLException|RuntimeException failure) {
                connection.rollback();
                try(var rows=statement.executeQuery(USER_SCHEMAS)) {
                    var remaining=new ArrayList<String>();while(rows.next())remaining.add(rows.getString(1));
                    System.err.println("Reset stopped; remaining schemas: "+remaining);
                } catch(SQLException inspectionFailure) { System.err.println("Reset stopped; remaining schemas could not be read"); }
                throw failure;
            }
        }
    }
    private static Connection open(IsolatedDatabaseGuard.Connection db)throws SQLException {
        var properties=new Properties();properties.setProperty("user",db.user());properties.setProperty("password",db.password());
        properties.setProperty("connectTimeout","3");properties.setProperty("socketTimeout","10");return DriverManager.getConnection(db.url(),properties);
    }
    private static int launch(Plan plan,IsolatedDatabaseGuard.Connection db)throws Exception {
        var parent=System.getenv();Path project=Path.of("").toAbsolutePath();
        requireIndependentCopy(project,Path.of(Objects.requireNonNull(parent.get("EC_TEST_ORIGINAL_PROJECT"))));
        for(String testClass:plan.classes()) {
            // Verify the actual database before every class; reset only on the explicit shared flag.
            try(var connection=open(db)) {
                if(plan.resetTestDb())resetTestDatabase(db,connection);else IsolatedDatabaseGuard.assertConnected(db,connection);
            }
            var builder=new ProcessBuilder(command(testClass,plan.testcontainers(),parent.get("EC_TEST_MAVEN_SETTINGS")))
                .directory(project.toFile()).redirectErrorStream(true);
            builder.environment().clear();builder.environment().putAll(childEnvironment(parent,db));
            int exit=runOwned(builder.start(),java.time.Duration.ofMinutes(30),line->{
                if(line.contains("Using generated security password:"))
                    throw new IllegalStateException("Unexpected generated authentication output; test execution stopped");
                System.out.println(line.replace(db.url(),"[test-db]").replace(db.password(),"[redacted]").replace(db.user(),"[test-user]"));
            });
            if(exit!=0)return exit;
        }
        return 0;
    }
    /** Tracks only this invocation's process tree, including descendants observed before reparenting. */
    static final class OwnedProcesses {
        final Process root;
        final Map<Long,ProcessHandle> known=new LinkedHashMap<>();
        OwnedProcesses(Process root){this.root=root;remember();}
        synchronized void remember(){
            root.descendants().forEach(h->known.putIfAbsent(h.pid(),h));
            for(var handle:List.copyOf(known.values()))
                if(handle.isAlive())handle.descendants().forEach(h->known.putIfAbsent(h.pid(),h));
        }
        synchronized List<ProcessHandle> children(){
            remember();var result=new ArrayList<>(known.values());
            result.sort(Comparator.comparingInt(OwnedProcesses::depth).reversed());return result;
        }
        private static int depth(ProcessHandle handle){
            int depth=0;var parent=handle.parent();
            while(parent.isPresent()){depth++;parent=parent.get().parent();}return depth;
        }
        private void await(List<ProcessHandle> handles,long millis) {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
            while(handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime()<deadline) {
                remember();
                try{Thread.sleep(20);}catch(InterruptedException ignored){/* cleanup restores interrupt below */}
            }
        }
        synchronized void stop() {
            boolean interrupted=Thread.interrupted();
            try {
                var children=children();
                // Give the Maven parent time to reap its children before stopping it.
                children.forEach(h->{if(h.isAlive())h.destroy();});await(children,1000);
                children=children();
                children.forEach(h->{if(h.isAlive())h.destroyForcibly();});await(children,1000);
                if(root.isAlive())root.destroy();await(List.of(root.toHandle()),1000);
                // Capture any final descendants before the root is forcibly terminated.
                children=children();
                children.forEach(h->{if(h.isAlive())h.destroyForcibly();});
                if(root.isAlive())root.destroyForcibly();
                var all=new ArrayList<>(children);all.add(root.toHandle());await(all,1000);
                var remaining=all.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
                if(!remaining.isEmpty()){
                    System.err.println("Owned processes could not be terminated: "+remaining);
                    throw new IllegalStateException("Owned process cleanup failed");
                }
            } finally {if(interrupted || Thread.interrupted())Thread.currentThread().interrupt();}
        }
    }
    static int runOwned(Process process,java.time.Duration timeout,java.util.function.Consumer<String> output)throws Exception {
        var owned=new OwnedProcesses(process);
        var lines=new java.util.concurrent.LinkedBlockingQueue<String>();
        var readerFailure=new java.util.concurrent.atomic.AtomicReference<IOException>();
        var reader=new Thread(()->{
            try(var in=process.inputReader(StandardCharsets.UTF_8)){
                String line;while((line=in.readLine())!=null)lines.add(line);
            }catch(IOException failure){readerFailure.set(failure);}
        },"test-maven-output");reader.setDaemon(true);
        var shutdown=new Thread(owned::stop,"test-maven-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);reader.start();
        long started=System.nanoTime();
        try {
            while(true) {
                if(Thread.interrupted())throw new InterruptedException();
                owned.remember();
                String line=lines.poll(20,java.util.concurrent.TimeUnit.MILLISECONDS);
                if(line!=null)output.accept(line);
                if(!process.isAlive() && !reader.isAlive() && lines.isEmpty()) {
                    if(readerFailure.get()!=null)throw readerFailure.get();
                    return process.exitValue();
                }
                if(System.nanoTime()-started>=timeout.toNanos())throw new java.util.concurrent.TimeoutException("Owned Maven process timed out");
            }
        } finally {
            try{owned.stop();}
            finally {
                try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException shuttingDown){/* hook owns the same tree */}
                reader.interrupt();
            }
        }
    }
    public static void main(String[] args) {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        int result=2;
        try {
            Plan plan=plan(args);
            // Never allocate a container or connect when called from the original workspace.
            requireIndependentCopy(Path.of("").toAbsolutePath(),Path.of(Objects.requireNonNull(System.getenv("EC_TEST_ORIGINAL_PROJECT"))));
            if(plan.testcontainers()) {
                String run=UUID.randomUUID().toString().replace("-","");
                try(var container=new PostgreSQLContainer("postgres:17").withDatabaseName("isolated_"+run)
                    .withUsername("isolated_user").withPassword(UUID.randomUUID().toString())
                    .withCommand("postgres","-c","track_commit_timestamp=on","-c","fsync=on","-c","synchronous_commit=on")
                    .withLabel("ecsite.isolated-test-run",run)) {
                    // Container failures may include its environment in library logs.
                    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                    logger.setLevel(ch.qos.logback.classic.Level.OFF);try{container.start();}finally{logger.setLevel(ch.qos.logback.classic.Level.WARN);}
                    var db=new IsolatedDatabaseGuard.Connection(container.getJdbcUrl(),container.getUsername(),container.getPassword());
                    IsolatedDatabaseGuard.verify(environment(db,true));result=launch(plan,db);
                }
            } else result=launch(plan,sharedConnection(System.getenv()));
        } catch(Exception failure) { System.err.println("Test launch failed; no retry or development database fallback was attempted"); }
        System.exit(result);
    }
}
