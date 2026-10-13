package com.example.ecsite.support.isolation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IndependentContainerSmokeTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17").withDatabaseName("independent_isolation_smoke");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
        r.add("spring.flyway.url",DB::getJdbcUrl);r.add("spring.flyway.user",DB::getUsername);r.add("spring.flyway.password",DB::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Test void dynamicPropertiesOverrideCommonContainer(){assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("independent_isolation_smoke");}
}
