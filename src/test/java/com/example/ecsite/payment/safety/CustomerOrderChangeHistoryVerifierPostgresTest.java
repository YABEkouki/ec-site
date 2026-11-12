package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import com.example.ecsite.service.order.history.*;
import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real writer -> unchanged Reader -> pure Verifier contract; isolated PG17 only. */
class CustomerOrderChangeHistoryVerifierPostgresTest extends OrderEditReauthorizationIntegrationTest {
    static org.testcontainers.postgresql.PostgreSQLContainer verifierDatabase;
    @DynamicPropertySource static void verifierDatabase(org.springframework.test.context.DynamicPropertyRegistry properties){
        verifierDatabase=OrderEditAdmissionFoundationPostgresTest.commitTrackingContainer();verifierDatabase.start();
        properties.add("spring.datasource.url",verifierDatabase::getJdbcUrl);properties.add("spring.datasource.username",verifierDatabase::getUsername);properties.add("spring.datasource.password",verifierDatabase::getPassword);
        properties.add("spring.flyway.url",verifierDatabase::getJdbcUrl);properties.add("spring.flyway.user",verifierDatabase::getUsername);properties.add("spring.flyway.password",verifierDatabase::getPassword);
    }
    @AfterAll static void stopVerifierDatabase(){if(verifierDatabase!=null)verifierDatabase.stop();}
    @Autowired javax.sql.DataSource source;
    @Test void actualRetainedThenReauthorizedReceiptsVerifyWithoutIo(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long product=helper().product(f,7000);
        UUID retained=accepted(f,List.of(new Line(f.item(),f.product(),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);mockTransport(f);
        assertThat(service().execute(retained).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        UUID reauth=accepted(f,List.of(new Line(null,product,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);reset(gateway);oldCancelled.set(false);mockTransport(f);
        assertThat(service().execute(reauth).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var bundle=tx.execute(s->new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(f.order(),f.customer()));clearInvocations(gateway);
        var result=new OrderChangeHistoryVerifier().verify(bundle);
        assertThat(result.issues()).isEmpty();assertThat(result.verifiedChanges()).hasSize(2);assertThat(result.displayability()).isEqualTo(OrderChangeHistoryVerificationResult.Displayability.ALL);verifyNoInteractions(gateway);
    }
}
