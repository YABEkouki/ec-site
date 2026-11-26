package com.example.ecsite.payment.safety;

import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.OrderChangeRequestSender;
import com.example.ecsite.payment.payjp.*;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureService;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureService.Outcome;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.orderchange.OrderReauthorizationService;
import com.example.ecsite.support.CaptureConnectionTest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feature143-A2: an admission accepted before V81 keeps a baseline without the order columns V81 added (orders 8 and
 * 17 of the development database). The admission is written on a V80 database, the database is then upgraded, and the
 * re-authorization is closed like any other.
 */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@CaptureConnectionTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyBaselineChangeClosureUpgradePostgresTest {
    static final CaptureConnectionIdentity CONNECTION=new CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp");
    static final String[] LOCATIONS={"classpath:db/migration","classpath:db/test-migration"};
    /** Long enough for the upgrade and the execution; the closure waits until it has passed. */
    static final String WINDOW="3 minutes";
    static PostgreSQLContainer database;
    static PaymentSafetyExpandPostgresTest db;
    static OrderEditAdmissionServicePostgresTest.Fixture f;
    static UUID admission;
    static long added;

    @DynamicPropertySource static void legacyDatabase(DynamicPropertyRegistry properties){
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        database=OrderEditAdmissionFoundationPostgresTest.commitTrackingContainer();database.start();
        db=OrderEditAdmissionFoundationPostgresTest.open(database);
        // The application uses the default schema of the container; the guard accepts only its plain JDBC URL.
        db.schema="public";db.source.setCurrentSchema(db.schema);
        org.flywaydb.core.Flyway.configure().dataSource(db.source).defaultSchema(db.schema).schemas(db.schema).locations(LOCATIONS)
            .target("80").load().migrate();
        share();
        new OrderEditAdmissionFoundationPostgresTest().runtime(new Info("legacyAdmission"));
        f=OrderEditAdmissionServicePostgresTest.fixture(false);
        added=new OrderEditApplicationPlanPostgresTest().product(f,7000);
        admission=accepted(f,List.of(new Line(null,added,1)));
        OrderEditAdmissionServicePostgresTest.tx(()->db.jdbc.queryForObject("select f125_execution_initialize(?,?::interval)",Object.class,admission,WINDOW));
        assertThat(db.jdbc.queryForObject("select jsonb_exists(unapplied_baseline->'order','acceptance_mode') from order_edit_admissions where id=?",Boolean.class,admission)).isFalse();
        properties.add("spring.datasource.url",database::getJdbcUrl);
        properties.add("spring.datasource.username",database::getUsername);
        properties.add("spring.datasource.password",database::getPassword);
        properties.add("spring.flyway.url",database::getJdbcUrl);
        properties.add("spring.flyway.user",database::getUsername);
        properties.add("spring.flyway.password",database::getPassword);
    }
    @AfterAll static void stopDatabase(){if(database!=null)database.stop();}
    static void share(){
        OrderEditAdmissionFoundationPostgresTest.db=db;OrderEditAdmissionFoundationPostgresTest.jdbc=db.jdbc;
        OrderEditAdmissionServicePostgresTest.db=db;OrderEditAdmissionServicePostgresTest.jdbc=db.jdbc;
        LegacyOrderEvidenceEnrollmentPostgresTest.db=db;LegacyOrderEvidenceEnrollmentPostgresTest.jdbc=db.jdbc;
        OrderPaymentEvidenceFoundationPostgresTest.db=db;OrderPaymentEvidenceFoundationPostgresTest.jdbc=db.jdbc;
        OrderEditApplicationPlanPostgresTest.db=db;
    }
    record Info(String name) implements TestInfo {
        public String getDisplayName(){return name;}
        public Set<String> getTags(){return Set.of();}
        public Optional<Class<?>> getTestClass(){return Optional.of(LegacyBaselineChangeClosureUpgradePostgresTest.class);}
        public Optional<java.lang.reflect.Method> getTestMethod(){
            try{return Optional.of(LegacyBaselineChangeClosureUpgradePostgresTest.class.getDeclaredMethod(name));}catch(NoSuchMethodException e){throw new IllegalStateException(e);}
        }
    }
    static UUID accepted(OrderEditAdmissionServicePostgresTest.Fixture f,List<Line> lines){
        var command=OrderEditAdmissionServicePostgresTest.tx(()->{
            var read=new JdbcOrderPaymentEvidenceReader(db.jdbc).readLocked(f.order());
            var request=new Request(f.order(),read.facts().current().contentRevision(),lines);
            var now=db.jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class);
            var offers=db.jdbc.query("select p.id,p.name,c.id,c.name,p.price,t.id,t.code,t.name,t.tax_rate,(p.active and c.active and t.active) from products p join categories c on c.id=p.category_id join tax_categories t on t.id=p.tax_category_id order by p.id",
                (r,n)->new Offer(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getInt(5),r.getLong(6),r.getString(7),r.getString(8),OrderContentSnapshotCodec.taxRateBasisPoints(r.getBigDecimal(9)),r.getBoolean(10)));
            var calculated=new OrderEditCandidateCalculator().calculateForAdmission(OrderEditCandidateSources.context(read,f.customer(),now),request,offers);
            assertThat(calculated.route()).isEqualTo(OrderChangeRoutePolicy.Route.REAUTHORIZE);
            return new OrderEditAdmissionService.Command(f.customer(),UUID.randomUUID(),request,calculated.candidate());
        });
        var result=new OrderEditAdmissionService(db.source).submit(command);
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.ACCEPTED);return result.admissionId();
    }

    @Autowired JdbcTemplate applicationJdbc;
    @Autowired OrderReauthorizationService finance;
    @Autowired ChangeFlowClosureService closures;
    @Autowired ChangeFlowClosureStore store;
    @MockitoBean PaymentGateway gateway;
    String oldFlow,newFlow,newStatus="requires_payment_method";
    UUID operation;
    int afterAmount;

    void mockTransport(){
        oldFlow=db.jdbc.queryForObject("select provider_payment_id from payments where id=?",String.class,f.payment());
        when(gateway.observeOrderAuthorization(any())).thenAnswer(call->MockOrderAuthorizationTransport.observe(call.getArgument(0)));
        when(gateway.sendOrderChangeRequest(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var request=((OrderChangeRequestSender.Dispatch)call.getArgument(0)).request().httpRequest();
            assertThat(request.kind()).isEqualTo("CREATE");
            operation=request.operationId();newFlow="pfw_f125_"+operation;afterAmount=request.amount();
            return observation(newFlow,"requires_payment_method",afterAmount,Map.of());
        });
        when(gateway.observeOrderChangeFlow(anyString())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();String flow=call.getArgument(0);
            if(flow.equals(oldFlow))return observation(flow,"requires_capture",6000,Map.of());
            assertThat(flow).isEqualTo(newFlow);
            return observation(flow,newStatus,afterAmount,Map.of("order_id",Long.toString(f.order()),"order_change_operation_id",operation.toString()));
        });
        when(gateway.cancelUnpaidCheckout(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((CancellationRequest)call.getArgument(0)).providerPaymentId()).isEqualTo(newFlow);
            newStatus="canceled";return new CancellationResult(CancellationResultStatus.CANCELLED,newFlow);
        });
    }
    static PayJpPaymentFlowObservation observation(String flow,String status,int amount,Map<String,Object> metadata){
        var fields=new LinkedHashMap<String,Object>();fields.put("id",flow);fields.put("status",status);fields.put("amount",amount);
        fields.put("currency","jpy");fields.put("capture_method","manual");fields.put("livemode",false);fields.put("metadata",metadata);
        fields.put("amount_received",status.equals("requires_capture")?0:null);fields.put("amount_capturable",status.equals("requires_capture")?amount:null);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var raw=mapper.readTree(mapper.writeValueAsString(fields));
        return mapper.treeToValue(raw,PayJpPaymentFlowObservation.class).withOldCancellationEvidence(OldAuthorizationCancellationEvidence.fromJson(raw,CONNECTION));
    }
    String one(String sql,Object id){return db.jdbc.queryForObject(sql,String.class,id);}
    int stock(){return db.jdbc.queryForObject("select stock from products where id=?",Integer.class,added);}

    @Test void legacyAdmission(){
        assertThat(applicationJdbc.queryForObject("select max(version::int) from flyway_schema_history where success",Integer.class)).isEqualTo(112);
        assertThat(applicationJdbc.queryForObject("select current_schema()",String.class)).isEqualTo(db.schema);
        new OrderEditAdmissionFoundationPostgresTest().runtime(new Info("legacyAdmission"));
        // The upgrade added the two columns with their default values to the order the admission was taken for.
        assertThat(one("select acceptance_mode||':'||coalesce(accepted_at::text,'null') from orders where id=?",f.order())).isEqualTo("LEGACY:null");
        assertThat(db.jdbc.queryForObject("select f143_legacy_order_local(unapplied_baseline,f125_execution_local(order_id))=unapplied_baseline from order_edit_admissions where id=?",Boolean.class,admission)).isTrue();

        int stockBefore=stock();mockTransport();
        assertThat(new OrderEditExecutionService(db.source,gateway,CONNECTION,finance).execute(admission).status()).isEqualTo(OrderEditExecutionService.Status.HELD);
        assertThat(db.jdbc.queryForObject("select automatic_until>clock_timestamp() from order_edit_execution_controls where admission_id=?",Boolean.class,admission)).isTrue();
        assertThat(operation).as(db.jdbc.queryForObject("select jsonb_build_object('admission',(select to_jsonb(r)-'unapplied_baseline'-'request_payload'-'candidate_payload'-'financial_snapshot'-'financial_canonical' from order_edit_admissions r where id=?),'results',(select jsonb_agg(to_jsonb(x)) from order_edit_execution_attempt_results x where admission_id=?),'control',(select to_jsonb(c)-'baseline' from order_edit_execution_controls c where admission_id=?))::text",String.class,admission,admission,admission)).isNotNull();
        assertThat(one("select state from order_change_operations where id=?",operation)).isEqualTo("AUTHORIZING");
        assertThat(one("select execution_state from order_edit_admissions where id=?",admission)).isEqualTo("RECOVERY_PENDING");
        assertThat(stock()).isEqualTo(stockBefore-1);
        db.jdbc.queryForObject("select pg_sleep(greatest(0,extract(epoch from automatic_until-clock_timestamp()))+0.05) from order_edit_execution_controls where admission_id=?",Object.class,admission);
        assertThat(store.closableOperation(f.order())).contains(operation);

        assertThat(closures.close(f.order(),new ChangeFlowClosureStore.Actor(1L,"closer",null))).isEqualTo(Outcome.CLOSED);
        verify(gateway,times(1)).cancelUnpaidCheckout(any());
        assertThat(one("select state from order_change_operations where id=?",operation)).isEqualTo("ABORTED");
        assertThat(one("select execution_state||':'||reason_code from order_edit_admissions where id=?",admission)).isEqualTo("CLOSED_UNAPPLIED:REAUTH_UNFINISHED");
        assertThat(stock()).isEqualTo(stockBefore);
        assertThat(one("select status from payments where id=?",f.payment())).isEqualTo("AUTHORIZED");
        assertThat(db.jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isZero();
    }

    /** Only the two V81 columns, only with the values V81 gave existing orders, and only when the baseline lacks them. */
    @Test void onlyTheUpgradeDefaultsAreLeftOut(){
        String base="{\"order\":{\"id\":1,\"status\":\"ORDERED\"},\"items\":[]}";
        String legacy="{\"order\":{\"id\":1,\"status\":\"ORDERED\",\"acceptance_mode\":\"LEGACY\",\"accepted_at\":null},\"items\":[]}";
        assertThat(same(base,legacy,base)).isTrue();
        for(String other:List.of(
                legacy.replace("\"LEGACY\"","\"FORMAL\""),
                legacy.replace("\"accepted_at\":null","\"accepted_at\":\"2026-11-26T10:00:00\""),
                legacy.replace("\"status\":\"ORDERED\",","\"status\":\"CANCELLED\","),
                legacy.replace(",\"accepted_at\":null",""),
                legacy.replace("\"items\":[]","\"items\":[1]")))
            assertThat(same(base,other,base)).as(other).isFalse();
        // A baseline taken after V81 is compared in full.
        assertThat(same(legacy,legacy,legacy)).isTrue();
        assertThat(same(legacy,base,base)).isTrue();
    }
    boolean same(String baseline,String actual,String expected){
        return Boolean.TRUE.equals(db.jdbc.queryForObject("select f143_legacy_order_local(?::jsonb,?::jsonb)=?::jsonb",Boolean.class,baseline,actual,expected));
    }
}
