package com.example.ecsite.payment.safety;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import com.example.ecsite.service.order.history.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real old writers -> unchanged owned Reader -> new pure legacy verifier. Isolated PG17 only. */
class CustomerLegacyOrderChangeHistoryVerifierPostgresTest extends OrderEditReauthorizationIntegrationTest {
    static org.testcontainers.postgresql.PostgreSQLContainer legacyHistoryDatabase;
    @DynamicPropertySource static void legacyHistoryDatabase(org.springframework.test.context.DynamicPropertyRegistry properties){
        legacyHistoryDatabase=OrderEditAdmissionFoundationPostgresTest.commitTrackingContainer();legacyHistoryDatabase.start();
        properties.add("spring.datasource.url",legacyHistoryDatabase::getJdbcUrl);properties.add("spring.datasource.username",legacyHistoryDatabase::getUsername);properties.add("spring.datasource.password",legacyHistoryDatabase::getPassword);
        properties.add("spring.flyway.url",legacyHistoryDatabase::getJdbcUrl);properties.add("spring.flyway.user",legacyHistoryDatabase::getUsername);properties.add("spring.flyway.password",legacyHistoryDatabase::getPassword);
    }
    @AfterAll static void stopLegacyHistoryDatabase(){if(legacyHistoryDatabase!=null)legacyHistoryDatabase.stop();}
    @Autowired javax.sql.DataSource source;
    @Autowired com.example.ecsite.service.OrderService orderService;
    @Autowired org.springframework.context.ApplicationContext context;
    OrderChangeEvidenceBundle read(long order,long owner){
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(s->new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(order,owner));
    }
    @Test void oldDecreasesAndRemovalAcrossTwoCommitsVerify(){
        var f=new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();long owner=db.jdbc.queryForObject("select user_id from orders where id=?",Long.class,f.order());
        db.jdbc.update("update order_items set quantity=3,subtotal=18000 where order_id=?",f.order());
        db.jdbc.update("insert into order_items(order_id,product_id,product_name,category_id,category_name,price,quantity,subtotal,tax_category_id,tax_category_code,tax_category_name,tax_rate) select order_id,product_id,product_name,category_id,category_name,price,1,6000,tax_category_id,tax_category_code,tax_category_name,tax_rate from order_items where order_id=?",f.order());
        db.jdbc.update("update orders set total_amount=24000,item_subtotal=24000,tax_amount=2181 where id=?",f.order());db.jdbc.update("update payments set amount=24000 where id=?",f.payment());db.jdbc.update("update payment_transactions set amount=24000 where id=?",f.auth());
        long first=db.jdbc.queryForObject("select min(id) from order_items where order_id=?",Long.class,f.order());long removed=db.jdbc.queryForObject("select max(id) from order_items where order_id=?",Long.class,f.order());
        var form=new com.example.ecsite.form.OrderItemChangeForm();form.setContentRevision(0);form.getItems().add(line(first,2));form.getItems().add(line(removed,0));
        assertThat(orderService.changeItemsForUser(f.order(),owner,"fixture",form)).isTrue();
        var second=new com.example.ecsite.form.OrderItemChangeForm();second.setContentRevision(1);second.getItems().add(line(first,1));assertThat(orderService.changeItemsForUser(f.order(),owner,"fixture",second)).isTrue();
        clearInvocations(gateway);var result=new OrderChangeHistoryVerifier().verify(read(f.order(),owner));
        assertThat(result.issues()).isEmpty();assertThat(result.displayability()).isEqualTo(Displayability.ALL);assertThat(result.verifiedChanges()).hasSize(2);assertThat(result.histories()).allSatisfy(h->assertThat(h.classification()).isEqualTo(Classification.LEGACY_CONFIRMED));
        assertThat(result.verifiedChanges()).anySatisfy(h->assertThat(h.legacy().items()).anySatisfy(i->assertThat(i.type()).isEqualTo("REMOVED")));verifyNoInteractions(gateway);
    }
    @Test void oldReauthorizationSavedSuccessVerifies(){
        var f=com.example.ecsite.service.orderchange.LegacyRecoveredOrderChangeHistoryFixture.completeNormally(context,gateway);clearInvocations(gateway);confirmed(read(f.orderId(),f.customerId()));verifyNoInteractions(gateway);
    }
    @Test void oldRecoveredReauthorizationSavedAuditVerifies(){
        var f=com.example.ecsite.service.orderchange.LegacyRecoveredOrderChangeHistoryFixture.complete(context,gateway);clearInvocations(gateway);var bundle=read(f.orderId(),f.customerId());
        assertThat(bundle.rows(OrderChangeEvidenceBundle.EvidenceSet.RECOVERY_AUDITS)).hasSize(1);confirmed(bundle);verifyNoInteractions(gateway);
    }
    @Test void actualLegacyThenFeature125HistoryOnSameOrderBothVerify(){
        var old=new LegacyOrderEvidenceEnrollmentPostgresTest().fixture();long owner=db.jdbc.queryForObject("select user_id from orders where id=?",Long.class,old.order());
        db.jdbc.update("update order_items set price=3000,quantity=3,subtotal=9000 where order_id=?",old.order());
        db.jdbc.update("update orders set total_amount=9000,item_subtotal=9000,tax_amount=818,ordered_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+interval '2 days 10 hours',change_deadline_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp()))+interval '2 days 14 hours' where id=?",old.order());
        db.jdbc.update("update payments set amount=9000 where id=?",old.payment());db.jdbc.update("update payment_transactions set amount=9000 where id=?",old.auth());
        long item=db.jdbc.queryForObject("select id from order_items where order_id=?",Long.class,old.order());long product=db.jdbc.queryForObject("select product_id from order_items where order_id=?",Long.class,old.order());
        var form=new com.example.ecsite.form.OrderItemChangeForm();form.setContentRevision(0);form.getItems().add(line(item,2));assertThat(orderService.changeItemsForUser(old.order(),owner,"fixture",form)).isTrue();
        when(gateway.observeOrderAuthorization(any())).thenAnswer(call->com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport.observe(call.getArgument(0)));
        var enrolled=new com.example.ecsite.service.order.evidence.LegacyOrderEvidenceEnrollment(source,gateway,CONNECTION).enrollForCustomer(old.order(),owner);
        assertThat(enrolled.status()).isEqualTo(com.example.ecsite.service.order.evidence.LegacyOrderEvidenceEnrollment.Status.ENROLLED);
        var f=new OrderEditAdmissionServicePostgresTest.Fixture(old.order(),owner,old.payment(),old.auth(),item,product,enrolled.epochId());
        UUID admission=accepted(f,List.of(new com.example.ecsite.service.order.evidence.OrderEditCandidate.Line(item,product,1)),com.example.ecsite.service.order.evidence.OrderChangeRoutePolicy.Route.RETAIN_AUTH);reset(gateway);mockTransport(f);
        assertThat(service().execute(admission).status()).isEqualTo(com.example.ecsite.service.order.evidence.OrderEditExecutionService.Status.COMPLETED);
        clearInvocations(gateway);var result=new OrderChangeHistoryVerifier().verify(read(old.order(),owner));assertThat(result.issues()).isEmpty();assertThat(result.displayability()).isEqualTo(Displayability.ALL);assertThat(result.histories()).extracting(HistoryDecision::classification).containsExactly(Classification.LEGACY_CONFIRMED,Classification.FEATURE125_CONFIRMED);verifyNoInteractions(gateway);
    }
    private void confirmed(OrderChangeEvidenceBundle bundle){var result=new OrderChangeHistoryVerifier().verify(bundle);assertThat(result.issues()).isEmpty();assertThat(result.displayability()).isEqualTo(Displayability.ALL);assertThat(result.histories()).singleElement().satisfies(h->assertThat(h.classification()).isEqualTo(Classification.LEGACY_CONFIRMED));assertThat(result.verifiedChanges().getFirst().legacy().kind()).isEqualTo(LegacyOrderChangeEvidence.Kind.LEGACY_REAUTHORIZE);}
    private static com.example.ecsite.form.OrderItemChangeForm.Item line(long id,int quantity){var line=new com.example.ecsite.form.OrderItemChangeForm.Item();line.setOrderItemId(id);line.setQuantity(quantity);return line;}
}
