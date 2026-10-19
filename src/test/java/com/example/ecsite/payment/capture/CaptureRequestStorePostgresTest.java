package com.example.ecsite.payment.capture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import jakarta.persistence.EntityManager;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.payment.PaymentGateway;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;

@SpringBootTest(properties={"payjp.api-base-url=https://api.pay.jp","payjp.capture-connection.connection-id=primary",
    "payjp.capture-connection.provider-account-ref=merchant","payjp.capture-connection.mode=TEST",
    "payjp.capture-connection.api-origin=https://api.pay.jp"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CaptureRequestStorePostgresTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17")
        .withDatabaseName("capture_store_"+UUID.randomUUID().toString().replace("-",""))
        .withUsername("capture_store_user").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
        r.add("spring.flyway.url",DB::getJdbcUrl);r.add("spring.flyway.user",DB::getUsername);r.add("spring.flyway.password",DB::getPassword);
    }
    @Autowired CaptureRequestStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource source;
    @MockitoBean PaymentGateway gateway;
    @AfterEach void gatewayNeverCalled() { verifyNoInteractions(gateway); }
    record Fixture(Payment payment,PaymentTransaction capture,CaptureHttpRequest request,Instant created) {}
    Fixture fixture() {
        var now=orders.findCurrentTimeInTokyo();String suffix=UUID.randomUUID().toString();
        var u=new User();u.setUsername("capture-store-"+suffix);u.setPassword("unused");u.setEnabled(true);u.setCreatedAt(now);u.setUpdatedAt(now);users.saveAndFlush(u);
        var order=new Order(u.getId(),5500,now,now.plusHours(1));orders.saveAndFlush(order);
        var payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,6500,now);
        payment.setProviderPaymentId("pfw_"+suffix,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
        var capture=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,5500,0,"capture-"+suffix,now);
        transactions.saveAndFlush(capture);
        var request=CaptureHttpRequest.create(new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp"),payment.getProviderPaymentId(),5500,capture.getIdempotencyKey());
        return new Fixture(payment,capture,request,now.atZone(ZoneId.of("Asia/Tokyo")).toInstant());
    }
    StoredCaptureRequest insert(Fixture f) { return store.insert(f.payment(),f.capture(),f.request(),f.created(),f.created().plus(23,ChronoUnit.HOURS)); }
    void rejectsSql(Runnable work,String state) {
        jdbc.execute((ConnectionCallback<Void>) c->{var point=c.setSavepoint();try {
            Throwable failure=catchThrowable(work::run);assertThat(failure).isNotNull();
            while(!(failure instanceof SQLException)&&failure.getCause()!=null)failure=failure.getCause();
            assertThat(failure).isInstanceOf(SQLException.class);assertThat(((SQLException)failure).getSQLState()).isEqualTo(state);
        } finally {c.rollback(point);c.releaseSavepoint(point);}return null;});
    }
    @Test void insertsAllColumnsAndReadsExactTargetOnJpaConnection() {
        var f=fixture();
        int hibernatePid=em.unwrap(Session.class).doReturningWork(c->{try(var s=c.createStatement();var rs=s.executeQuery("select pg_backend_pid()")){rs.next();return rs.getInt(1);}});
        assertThat(jdbc.queryForObject("select pg_backend_pid()",Integer.class)).isEqualTo(hibernatePid);
        var saved=insert(f);assertThat(saved.id()).isPositive();
        var read=store.findByCaptureTransactionId(f.capture().getId()).orElseThrow();
        read.validateAgainst(f.payment(),f.capture(),f.request().connection());
        assertThat(read).usingRecursiveComparison().isEqualTo(saved);
        assertThat(store.findByIdAndCaptureTransactionId(saved.id(),f.capture().getId())).isPresent();
        assertThat(store.findByIdAndCaptureTransactionId(saved.id()+1,f.capture().getId())).isEmpty();
        assertThat(store.findByIdAndCaptureTransactionId(saved.id(),f.capture().getId()+1)).isEmpty();
        assertThat(store.findByCaptureTransactionId(Long.MAX_VALUE)).isEmpty();
        assertThat(((Number)em.createNativeQuery("select count(*) from capture_requests where id=:id").setParameter("id",saved.id()).getSingleResult()).intValue()).isEqualTo(1);
        var row=jdbc.queryForMap("select * from capture_requests where id=?",saved.id());assertThat(row).hasSize(23);
        assertThat(row.get("request_body")).isEqualTo(f.request().body());assertThat(row.get("request_fingerprint")).isEqualTo(f.request().fingerprint());
        assertThat(read.createdAt()).isEqualTo(f.created());assertThat(read.replayNotAfter()).isEqualTo(f.created().plus(23,ChronoUnit.HOURS));
    }
    @Test void rejectsDuplicateCaptureAndCompositeForeignKeyMutation() {
        var f=fixture();var saved=insert(f);
        rejectsSql(()->insert(f),"23505");
        rejectsSql(()->jdbc.update("update payments set provider_payment_id='pfw_other' where id=?",f.payment().getId()),"23503");
        rejectsSql(()->jdbc.update("update payment_transactions set idempotency_key='changed' where id=?",f.capture().getId()),"23503");
        assertThat(store.findByCaptureTransactionId(f.capture().getId()).orElseThrow().id()).isEqualTo(saved.id());
    }
    @ParameterizedTest @ValueSource(strings={"update","delete","truncate"})
    void mutationIsNotExposedAndDatabaseAlsoRejectsIt(String kind) {
        var f=fixture();var saved=insert(f);
        String sql=switch(kind){case "update"->"update capture_requests set mode=mode";case "delete"->"delete from capture_requests";default->"truncate capture_requests";};
        rejectsSql(()->jdbc.execute(sql),"55000");
        assertThat(store.findByIdAndCaptureTransactionId(saved.id(),f.capture().getId())).isPresent();
    }
    @ParameterizedTest @ValueSource(strings={"body","fingerprint","flow","amount","key","body-version","hash-version","method","path","content-type","connection","deadline","type"})
    void rejectsCorruptValuesFromActualPostgresResultSet(String field) {
        var f=fixture();var saved=insert(f);
        String columns="id,payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after";
        String column=switch(field){case "body"->"request_body";case "fingerprint"->"request_fingerprint";case "flow"->"provider_flow_id";case "amount"->"amount_to_capture";
            case "key"->"idempotency_key";case "body-version"->"body_format_version";case "hash-version"->"fingerprint_version";case "method"->"http_method";
            case "path"->"api_path";case "content-type"->"content_type";case "connection"->"connection_id";case "deadline"->"replay_not_after";default->"transaction_type";};
        String replacement=switch(field){case "body"->"decode('7b7d','hex')";case "fingerprint"->"decode(repeat('00',32),'hex')";case "amount"->"5501";
            case "body-version","hash-version"->"2";case "deadline"->"created_at";default->"'invalid'";};
        var projection=Arrays.stream(columns.split(",")).map(c->c.equals(column)?replacement+" as "+column:c).toList();
        // Projection changes only a result value, never disables V54 or mutates immutable stored data.
        assertThatIllegalArgumentException().isThrownBy(()->jdbc.queryForObject("select "+String.join(",",projection)+" from capture_requests where id=?",
                (rs,n)->CaptureRequestStore.map(rs,f.request().connection()),saved.id()));
        assertThat(store.findByCaptureTransactionId(f.capture().getId())).isPresent();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void commitVisibilityAndRollbackShareTheOuterJpaTransaction() throws Exception {
        var tx=new TransactionTemplate(manager);long[] ids=new long[2];
        tx.executeWithoutResult(s->{var f=fixture();var saved=insert(f);ids[0]=saved.id();ids[1]=f.capture().getId();
            assertThat(externalCount(ids[0])).isZero();});
        assertThat(externalCount(ids[0])).isEqualTo(1);
        long[] rolled=new long[2];
        tx.executeWithoutResult(s->{var f=fixture();var saved=insert(f);rolled[0]=saved.id();rolled[1]=f.capture().getId();s.setRollbackOnly();});
        assertThat(externalCount(rolled[0])).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where id=?",Long.class,rolled[1])).isZero();
    }
    long externalCount(long id) {
        try(var c=source.getConnection();var s=c.prepareStatement("select count(*) from capture_requests where id=?")) {
            s.setLong(1,id);try(var rs=s.executeQuery()){rs.next();return rs.getLong(1);}
        } catch(SQLException e){throw new IllegalStateException(e);}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void refusesCallsWithoutTransaction() {
        assertThatThrownBy(()->store.findByCaptureTransactionId(1L)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->store.insert(null,null,null,null,null)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void rejectedTargetDoesNotInsert() {
        var f=fixture();var wrong=CaptureHttpRequest.create(f.request().connection(),"pfw_other",5500,f.request().idempotencyKey());
        assertThatThrownBy(()->store.insert(f.payment(),f.capture(),wrong,f.created(),f.created().plusSeconds(1)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where capture_transaction_id=?",Long.class,f.capture().getId())).isZero();
    }

    @Test void publicReadRejectsPersistedBadFingerprintWithoutBypassingConstraints() {
        var good=fixture();var saved=insert(good);var bad=fixture();
        jdbc.update("""
            insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,
                provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,
                amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,
                fingerprint_version,request_fingerprint,created_at,replay_not_after)
            select ?,?,transaction_type,provider,payment_method,?,connection_id,provider_account_ref,mode,api_origin,
                http_method,?,content_type,amount_to_capture,order_content_revision,?,body_format_version,request_body,
                fingerprint_version,decode(repeat('00',32),'hex'),created_at,replay_not_after
            from capture_requests where id=?
            """,bad.payment().getId(),bad.capture().getId(),bad.request().flowId(),bad.request().path(),bad.request().idempotencyKey(),saved.id());
        assertThatThrownBy(()->store.findByCaptureTransactionId(bad.capture().getId()))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void rejectsReadOnlyInsertionWithoutChangingDatabase() {
        var tx=new TransactionTemplate(manager);var f=tx.execute(s->fixture());
        var readOnly=new TransactionTemplate(manager);readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status->{
            assertThatThrownBy(()->insert(f)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(status.isRollbackOnly()).isTrue();status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where capture_transaction_id=?",Long.class,f.capture().getId())).isZero();
    }
}
