package com.example.ecsite.payment.safety;

import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.CaptureHttpRequest;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderShippingPreparationService;
import com.example.ecsite.service.payment.PaymentCaptureService;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.support.CaptureConnectionTest;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real editing, shipping and immutable Capture request transactions; provider transport is mocked. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false","logging.level.org.springframework=WARN","logging.level.org.hibernate=WARN"})
@CaptureConnectionTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEditShipmentIntegrationTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties){
        OrderEditReauthorizationIntegrationTest.dedicatedCommitTrackingDatabase(properties);
    }
    @AfterAll static void stopDatabase(){OrderEditReauthorizationIntegrationTest.stopDedicatedDatabase();}
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired com.example.ecsite.service.orderchange.OrderReauthorizationService finance;
    @Autowired com.example.ecsite.service.orderchange.OrderReauthorizationTransactions financeTransactions;
    @Autowired com.example.ecsite.payment.orderchange.OrderChangeFinancialRequestStore requestStore;
    @Autowired OrderShippingPreparationService shipping;
    @Autowired PaymentCaptureService capture;
    @Autowired com.example.ecsite.service.payment.PaymentService payments;
    @Autowired com.example.ecsite.controller.OrderPaymentViewReadService views;
    @Autowired com.example.ecsite.service.payment.PaymentCancellationStartService cancellation;
    @Autowired com.example.ecsite.service.payment.PaymentCancellationResultService cancellationResults;
    @MockitoBean PaymentGateway gateway;
    @MockitoSpyBean OrderRepository orders;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean com.example.ecsite.payment.OrderPaymentEvidenceGuard evidenceGuard;
    @Autowired com.example.ecsite.service.payment.PaymentCaptureStartService captureStart;
    @Autowired com.example.ecsite.service.payment.CaptureInitialPostPreparationService capturePreparation;
    @Autowired com.example.ecsite.service.payment.PaymentCaptureResultService captureResults;
    OrderEditReauthorizationIntegrationTest h;
    @BeforeEach void fixture(TestInfo info){
        h=new OrderEditReauthorizationIntegrationTest();h.environment=environment;h.applicationJdbc=jdbc;
        h.manager=manager;h.finance=finance;h.financeTransactions=financeTransactions;h.requestStore=requestStore;h.gateway=gateway;
        h.fixtureDatabase(info);
    }
    @AfterEach void cleanup(){if(h!=null)h.stopRuntime();reset(orders,clock,evidenceGuard);}
    void admin(){var actor=new com.example.ecsite.security.AdminUserDetails(9L,"shipper","unused",true,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));}
    void preparation(long order){
        admin();var deadline=jdbc.queryForObject("select change_deadline_at from orders where id=?",LocalDateTime.class,order);
        doReturn(deadline).when(orders).findCurrentTimeInTokyo();
        doReturn(deadline.atZone(ZoneId.of("Asia/Tokyo")).toInstant()).when(clock).instant();
        shipping.start(order);
    }
    String activeFlow(long order){return jdbc.queryForObject("select p.provider_payment_id from payments p join orders o on o.active_payment_id=p.id where o.id=?",String.class,order);}
    int total(long order){return jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,order);}
    void captureTransport(long order,CaptureResultStatus result){
        String flow=activeFlow(order);int amount=total(order);
        when(gateway.capture(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var http=(CaptureHttpRequest)call.getArgument(0);
            assertThat(http.flowId()).isEqualTo(flow);assertThat(http.amount()).isEqualTo(amount);
            assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,order)).isEqualTo("SHIPPED");
            return new CaptureResult(result,flow,null,null);
        });
    }
    UUID edit(OrderEditAdmissionServicePostgresTest.Fixture f,List<Line> lines,OrderChangeRoutePolicy.Route route){
        UUID admission=h.accepted(f,lines,route);reset(gateway);h.mockTransport(f);
        assertThat(h.service().execute(admission).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        return admission;
    }
    @ParameterizedTest @ValueSource(ints={6000,5000,4000})
    void retainedReplacementShipsAndCapturesCurrentTotal(int price){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long product=h.helper().product(f,price);
        edit(f,List.of(new Line(null,product,1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(1);
        h.stopRuntime(); // Existing shipping/Capture must not depend on the new editing runtime being enabled.
        preparation(f.order());captureTransport(f.order(),CaptureResultStatus.CAPTURED);
        assertThat(payments.canCaptureForShipment(f.order())).isTrue();
        assertThat(views.read(orders.findById(f.order()).orElseThrow()).canCapture()).isTrue();
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(jdbc.queryForObject("select amount from payment_transactions where payment_id=? and transaction_type='CAPTURE'",Integer.class,f.payment())).isEqualTo(total(f.order()));
        assertThat(jdbc.queryForObject("select amount from payment_transactions where payment_id=? and transaction_type='AUTHORIZE'",Integer.class,f.payment())).isEqualTo(6000);
        assertThat(views.read(orders.findById(f.order()).orElseThrow()).state()).isEqualTo(com.example.ecsite.controller.OrderPaymentViewFactory.State.CAPTURED);
        verify(gateway,times(1)).capture(any());verify(gateway,never()).sendOrderChangeRequest(any());
    }
    @Test void pendingCaptureRequeriesWithoutASecondPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long product=h.helper().product(f,6000);
        edit(f,List.of(new Line(null,product,1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        preparation(f.order());captureTransport(f.order(),CaptureResultStatus.PENDING);
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.PENDING);
        byte[] body=jdbc.queryForObject("select request_body from capture_requests where payment_id=?",byte[].class,f.payment());
        when(gateway.retrievePaymentFlow(activeFlow(f.order()))).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PaymentFlowState(activeFlow(f.order()),PaymentFlowStatus.SUCCEEDED,null,null);
        });
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(jdbc.queryForObject("select request_body from capture_requests where payment_id=?",byte[].class,f.payment())).isEqualTo(body);
        verify(gateway,times(1)).capture(any());
    }
    @Test void reauthorizationThenRetainedEditCapturesOnlyTheNewPayment(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long added=h.helper().product(f,7000);
        edit(f,List.of(new Line(null,added,1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);
        long newPayment=jdbc.queryForObject("select active_payment_id from orders where id=?",Long.class,f.order());
        assertThat(newPayment).isNotEqualTo(f.payment());
        long newItem=jdbc.queryForObject("select id from order_items where order_id=?",Long.class,f.order());
        long replacement=h.helper().product(f,6000);
        // Current epoch is the completed reauthorization; retaining it must prove the next revision too.
        edit(f,List.of(new Line(null,replacement,1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(jdbc.queryForObject("select count(*) from order_items where id=?",Integer.class,newItem)).isZero();
        preparation(f.order());captureTransport(f.order(),CaptureResultStatus.CAPTURED);
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(jdbc.queryForList("select payment_id from payment_transactions where payment_id in (?,?) and transaction_type='CAPTURE'",Long.class,f.payment(),newPayment)).containsExactly(newPayment);
    }
    @Test void unresolvedAdmissionBlocksPreparationAndCompletedPreparationBlocksFurtherEditing(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);long p=h.helper().product(f,6000);
        UUID id=h.accepted(f,List.of(new Line(null,p,1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThatThrownBy(()->preparation(f.order())).isInstanceOf(RuntimeException.class);
        verify(gateway,never()).capture(any());
        reset(orders,clock,evidenceGuard);h.mockTransport(f);assertThat(h.service().execute(id).status()).isEqualTo(OrderEditExecutionService.Status.COMPLETED);
        preparation(f.order());
        var rejected=OrderEditAdmissionServicePostgresTest.tx(()->{
            var read=new JdbcOrderPaymentEvidenceReader(h.db.jdbc).readLocked(f.order());
            var line=read.facts().current().items().getFirst();
            var request=new Request(f.order(),read.facts().current().contentRevision(),List.of(new Line(line.orderItemId(),line.productId(),2)));
            var now=h.db.jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",LocalDateTime.class);
            return new OrderEditCandidateCalculator().calculateForAdmission(OrderEditCandidateSources.context(read,f.customer(),now),request,List.of());
        });
        assertThat(rejected.route()).isEqualTo(OrderChangeRoutePolicy.Route.BLOCKED);
    }
    @Test void repeatedRetainedChangesCanReturnToOriginalAuthorizationAmount(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        edit(f,List.of(new Line(null,h.helper().product(f,5000),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        edit(f,List.of(new Line(null,h.helper().product(f,6000),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(jdbc.queryForObject("select content_revision from orders where id=?",Integer.class,f.order())).isEqualTo(2);
        preparation(f.order());captureTransport(f.order(),CaptureResultStatus.CAPTURED);
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        verify(gateway,times(1)).capture(any());
    }
    @Test void reauthorizedOrderCapturesItsFullNewTotal(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        edit(f,List.of(new Line(null,h.helper().product(f,7000),1)),OrderChangeRoutePolicy.Route.REAUTHORIZE);
        preparation(f.order());captureTransport(f.order(),CaptureResultStatus.CAPTURED);
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=? and transaction_type='CAPTURE'",Integer.class,f.payment())).isZero();
    }
    @Test void failedCaptureDoesNotCreateAnotherPost(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        edit(f,List.of(new Line(null,h.helper().product(f,6000),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        preparation(f.order());captureTransport(f.order(),CaptureResultStatus.FAILED);
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.FAILED);
        assertThatThrownBy(()->capture.captureForShipment(f.order(),9L,"shipper",null)).isInstanceOf(RuntimeException.class);
        verify(gateway,times(1)).capture(any());
    }
    @Test void unknownCapturePostIsReconciledByGetWithoutResending(){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        edit(f,List.of(new Line(null,h.helper().product(f,5000),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        preparation(f.order());
        when(gateway.capture(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();throw new PaymentGatewayException("response lost");});
        assertThatThrownBy(()->capture.captureForShipment(f.order(),9L,"shipper",null)).isInstanceOf(PaymentGatewayException.class);
        when(gateway.retrievePaymentFlow(activeFlow(f.order()))).thenReturn(new PaymentFlowState(activeFlow(f.order()),PaymentFlowStatus.SUCCEEDED,null,null));
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        verify(gateway,times(1)).capture(any());
    }
    @Test void concurrentCaptureStartsOnlyOnePost()throws Exception{
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        edit(f,List.of(new Line(null,h.helper().product(f,6000),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        preparation(f.order());String flow=activeFlow(f.order());
        var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        when(gateway.capture(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();
            assertThat(resume.await(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return new CaptureResult(CaptureResultStatus.CAPTURED,flow,null,null);
        });
        when(gateway.retrievePaymentFlow(flow)).thenReturn(new PaymentFlowState(flow,PaymentFlowStatus.PROCESSING,null,null));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var first=pool.submit(()->{admin();try{return capture.captureForShipment(f.order(),9L,"shipper",null);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}});
            assertThat(entered.await(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.PENDING);
            resume.countDown();assertThat(first.get(30,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(CaptureResultStatus.CAPTURED);
            verify(gateway,times(1)).capture(any());
            assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=? and transaction_type='CAPTURE'",Integer.class,f.payment())).isEqualTo(1);
        }finally{resume.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void ordinaryCancellationAfterCompletedEditStillAppliesAndReplays(boolean reauthorize){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        edit(f,List.of(new Line(null,h.helper().product(f,reauthorize?7000:6000),1)),reauthorize?OrderChangeRoutePolicy.Route.REAUTHORIZE:OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        var start=cancellation.start(f.order(),f.customer(),"customer");
        assertThat(start.providerPaymentId()).isEqualTo(activeFlow(f.order()));
        var result=new CancellationResult(CancellationResultStatus.CANCELLED,start.providerPaymentId());
        assertThat(cancellationResults.apply(start.snapshot(),result)).isEqualTo(com.example.ecsite.service.payment.PaymentResultApplication.APPLIED);
        assertThat(cancellationResults.apply(start.snapshot(),result)).isEqualTo(com.example.ecsite.service.payment.PaymentResultApplication.ALREADY_APPLIED);
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.order())).isEqualTo("CANCELLED");
    }

    @Test void mixedSavedAndNewTaxSnapshotsCaptureTheRecomputedTotal(){
        var f=h.helper().twoLines();long added=h.helper().product(f,1000);
        jdbc.update("update products set tax_category_id=(select id from tax_categories where code='REDUCED') where id=?",added);
        edit(f,List.of(new Line(f.item(),f.product(),1),new Line(null,added,2)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        assertThat(total(f.order())).isEqualTo(5000);
        assertThat(jdbc.queryForList("select distinct tax_rate::integer from order_items where order_id=? order by 1",Integer.class,f.order())).containsExactly(8,10);
        preparation(f.order());captureTransport(f.order(),CaptureResultStatus.CAPTURED);
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
    }

    @ParameterizedTest @ValueSource(ints={-1,0,1,2,3})
    void unavailableContentProofStopsEveryCaptureStage(int stage){
        var f=OrderEditAdmissionServicePostgresTest.fixture(false);
        edit(f,List.of(new Line(null,h.helper().product(f,6000),1)),OrderChangeRoutePolicy.Route.RETAIN_AUTH);
        var blocked=mock(com.example.ecsite.payment.PaymentContentEvidence.class);
        when(blocked.state()).thenReturn(com.example.ecsite.payment.PaymentContentEvidence.State.BLOCKED);
        if(stage==-1){
            doReturn(blocked).when(evidenceGuard).inspect(any());
            assertThatThrownBy(()->preparation(f.order())).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select shipping_preparation_started_at is null from orders where id=?",Boolean.class,f.order())).isTrue();
            verify(gateway,never()).capture(any());return;
        }
        preparation(f.order());
        if(stage==0){
            doReturn(blocked).when(evidenceGuard).inspect(any());
            assertThatThrownBy(()->capture.captureForShipment(f.order(),9L,"shipper",null)).isInstanceOf(RuntimeException.class);
            verify(gateway,never()).capture(any());
        }else{
            var started=captureStart.start(f.order(),9L,"shipper",null);
            doReturn(blocked).when(evidenceGuard).inspect(any());
            if(stage==1)assertThatThrownBy(()->capturePreparation.prepare(started.initialPost())).isInstanceOf(RuntimeException.class);
            if(stage==2)assertThatThrownBy(()->capture.captureForShipment(f.order(),9L,"shipper",null)).isInstanceOf(RuntimeException.class);
            if(stage==3)assertThat(captureResults.apply(started.snapshot(),new CaptureResult(CaptureResultStatus.CAPTURED,activeFlow(f.order()),null,null)))
                    .isEqualTo(com.example.ecsite.service.payment.PaymentResultApplication.CONFLICT);
            verify(gateway,never()).capture(any());verify(gateway,never()).retrievePaymentFlow(anyString());
            assertThat(jdbc.queryForObject("select status from payment_transactions where payment_id=? and transaction_type='CAPTURE'",String.class,f.payment())).isEqualTo("PENDING");
        }
        assertThat(jdbc.queryForObject("select paid_at is null from orders where id=?",Boolean.class,f.order())).isTrue();
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,f.payment())).isEqualTo("AUTHORIZED");
    }

}
