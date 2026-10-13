package com.example.ecsite.support.isolation;

import java.net.URI;
import java.sql.DriverManager;
import java.util.*;
import org.springframework.core.env.Environment;
import org.testcontainers.DockerClientFactory;

/** Runs before application DataSource/Flyway beans. Never probes an unverified address. */
public final class IsolatedDatabaseGuard {
    private IsolatedDatabaseGuard() {}
    public record Connection(String url,String user,String password) {
        @Override public String toString() { return "Connection[redacted]"; }
    }
    public record Address(String host,int port,String database) {}
    public static Address address(String url) {
        try {
            if(url==null || !url.startsWith("jdbc:postgresql://")) throw rejected();
            URI uri=new URI(url.substring(5));
            String path=uri.getRawPath();
            if(uri.getUserInfo()!=null || uri.getRawQuery()!=null && !uri.getRawQuery().equals("loggerLevel=OFF") || uri.getFragment()!=null || path==null
                || !path.matches("/[A-Za-z0-9_]+") || uri.getHost()==null || uri.getPort()<1 || uri.getPort()==5432)
                throw rejected();
            String name=path.substring(1);
            if(Set.of("ecsite","ecsite_test","postgres","template0","template1").contains(name.toLowerCase(Locale.ROOT))) throw rejected();
            return new Address(uri.getHost(),uri.getPort(),name);
        } catch(Exception e) { throw rejected(); }
    }
    public static void same(Connection datasource,Connection flyway) {
        address(datasource.url());address(flyway.url());
        if(datasource.user()==null || datasource.user().isBlank() || datasource.password()==null || datasource.password().isBlank()
            || !datasource.equals(flyway)) throw rejected();
    }
    public static void verify(Environment env) {
        String stage="CONFIGURATION";
        try {
            var ds=new Connection(env.getProperty("spring.datasource.url"),env.getProperty("spring.datasource.username"),env.getProperty("spring.datasource.password"));
            var fw=new Connection(env.getProperty("spring.flyway.url"),env.getProperty("spring.flyway.user"),env.getProperty("spring.flyway.password"));
            stage="DATASOURCE_ADDRESS";address(ds.url());
            stage="FLYWAY_ADDRESS";address(fw.url());
            stage="DATASOURCE_FLYWAY_MATCH";same(ds,fw);
            Address target=address(ds.url());
            stage="DOCKER_HOST";
            String dockerHost=DockerClientFactory.instance().dockerHostIpAddress();
            if(!target.host().equals(dockerHost) && !(Set.of("localhost","127.0.0.1","::1").contains(target.host())
                && Set.of("localhost","127.0.0.1","::1").contains(dockerHost))) throw rejected();
            stage="CONTAINER_PROVENANCE";
            var docker=DockerClientFactory.instance().client();
            String matched=null;
            for(var c:docker.listContainersCmd().exec()) {
                if(!"true".equals(c.getLabels().get("org.testcontainers")) || !c.getImage().matches("(?:docker.io/library/)?postgres:17(?:\\..*)?")) continue;
                boolean port=Arrays.stream(c.getPorts()).anyMatch(p->Integer.valueOf(5432).equals(p.getPrivatePort()) && Integer.valueOf(target.port()).equals(p.getPublicPort()));
                if(port) { if(matched!=null) throw rejected(); matched=c.getId(); }
            }
            if(matched==null) throw rejected();
            stage="CONTAINER_ENVIRONMENT";
            var container=docker.inspectContainerCmd(matched).exec();
            var settings=Arrays.asList(container.getConfig().getEnv());
            if(!settings.contains("POSTGRES_DB="+target.database()) || !settings.contains("POSTGRES_USER="+ds.user())
                || !settings.contains("POSTGRES_PASSWORD="+ds.password())) throw rejected();
            // Docker provenance and port are validated before opening any JDBC connection.
            stage="READ_ONLY_CONNECTION";
            var props=new Properties();props.setProperty("user",ds.user());props.setProperty("password",ds.password());
            props.setProperty("connectTimeout","3");props.setProperty("socketTimeout","3");
            try(var connection=DriverManager.getConnection(ds.url(),props)) {
                connection.setReadOnly(true);
                try(var statement=connection.createStatement();var result=statement.executeQuery("select current_database(), current_user")) {
                    if(!result.next() || !target.database().equals(result.getString(1)) || !ds.user().equals(result.getString(2))) throw rejected();
                }
            }
        } catch(Exception e) { throw new IllegalStateException("Isolated test database verification failed at "+stage+"; no fallback is permitted"); }
    }
    private static IllegalStateException rejected() { return new IllegalStateException("Isolated test database verification failed; no fallback is permitted"); }
}
