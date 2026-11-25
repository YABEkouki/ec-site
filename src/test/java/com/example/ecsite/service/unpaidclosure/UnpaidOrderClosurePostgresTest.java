package com.example.ecsite.service.unpaidclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.ecsite.payment.*;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.savedcard.CheckoutCardSendStore;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureService.Outcome;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureService.Timing;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.*;
import com.example.ecsite.support.CaptureConnectionTest;

/** Feature142-A on the latest schema: the closure, its fence against the authorization, its recovery and V110. */
@SpringBootTest(properties = { "logging.level.root=WARN", "spring.jpa.show-sql=false" })
@AutoConfigureMockMvc
@CaptureConnectionTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnpaidOrderClosurePostgresTest {
    private static PostgreSQLContainer database;

    @DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        database = new PostgreSQLContainer("postgres:17").withDatabaseName("isolated_f142_" + UUID.randomUUID().toString().replace("-", ""));
        database.start();
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
        properties.add("spring.flyway.url", database::getJdbcUrl);
        properties.add("spring.flyway.user", database::getUsername);
        properties.add("spring.flyway.password", database::getPassword);
    }

    @AfterAll static void stop() { if (database != null) database.stop(); }

    static final Actor ADMIN = new Actor(1L, "closer", "お客様と連絡がとれないため");
    static final String SECRET = "synthetic_client_secret";
    static final Duration DAY = Duration.ofHours(23);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired UnpaidOrderClosureService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean UnpaidOrderClosureStore store;
    @Autowired AdminUnpaidOrderQueries queries;
    @Autowired PaymentAuthorizationResultService results;
    @Autowired PaymentAuthorizationService authorizations;
    @Autowired PaymentOperationSnapshotService snapshots;
    @Autowired PaymentOperationSyncService synchronization;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean CheckoutCardSendStore cardSends;
    @Autowired com.example.ecsite.service.savedcard.CheckoutSavedCardService savedCards;
    @MockitoBean com.example.ecsite.service.savedcard.SavedCardService cards;
    @MockitoBean com.example.ecsite.payment.savedcard.SavedCardGateway cardGateway;
    @Autowired com.example.ecsite.service.OrderService orders;
    @MockitoBean PaymentGateway gateway;

    record Fixture(long order, long user, long payment, long transaction, long product, String flow) {}

    Fixture f;
    /** The status PAY.JP would return now. */
    AuthorizationResultStatus provider;

    @BeforeEach
    void setUp() {
        f = fixture(120);
        provider = AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD;
        when(gateway.retrieveAuthorization(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new AuthorizationRecovery(result(provider, call.getArgument(0)), SECRET);
        });
        when(gateway.cancelUnpaidCheckout(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            CancellationRequest request = call.getArgument(0);
            assertThat(request.providerPaymentId()).isEqualTo(f.flow());
            assertThat(closureStatus()).isEqualTo("SENDING");
            provider = AuthorizationResultStatus.FAILED;
            return new CancellationResult(CancellationResultStatus.CANCELLED, f.flow());
        });
    }

    static AuthorizationResult result(AuthorizationResultStatus status, String flow) {
        return new AuthorizationResult(status, status == AuthorizationResultStatus.AUTHORIZED ? flow : null, null, null);
    }

    /** The production service with other waits: the recorded times themselves are fixed by V110. */
    UnpaidOrderClosureService waiting(Duration staleRequest, Duration answer, Duration key, Duration retry) {
        return new UnpaidOrderClosureService(store, gateway, snapshots, results, new Timing(staleRequest, answer, key, retry));
    }

    UnpaidOrderClosureService impatient() { return waiting(Duration.ZERO, Duration.ZERO, DAY, Duration.ZERO); }

    Fixture fixture(int minutesAgo) {
        return new TransactionTemplate(manager).execute(s -> {
            String key = UUID.randomUUID().toString();
            long user = jdbc.queryForObject("insert into users(username,password,enabled,created_at,updated_at) "
                    + "values (?,'unused',true,localtimestamp,localtimestamp) returning id", Long.class, "f142-" + key);
            long product = jdbc.queryForObject("insert into products(name,price,stock,category_id,tax_category_id) "
                    + "select 'f142',5980,14,(select min(id) from categories),(select min(id) from tax_categories) returning id", Long.class);
            long order = jdbc.queryForObject("""
                    insert into orders(user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,
                        shipping_city,shipping_address_line,shipping_phone,handling_status,item_subtotal,charge_total,tax_amount,
                        content_revision,acceptance_mode)
                    values (?,5980,'ORDERED',timezone('Asia/Tokyo',clock_timestamp())-?::interval,'A','123-4567','A','A','A','0900000000',
                        'NONE',5980,0,543,0,'AUTHORIZED_ACCEPTANCE') returning id
                    """, Long.class, user, minutesAgo + " minutes");
            jdbc.update("""
                    insert into order_items(order_id,product_id,product_name,price,quantity,subtotal,category_id,category_name,
                        tax_category_id,tax_category_code,tax_category_name,tax_rate)
                    select ?,p.id,p.name,5980,1,5980,p.category_id,'c',p.tax_category_id,'STANDARD','t',10 from products p where p.id=?
                    """, order, product);
            String flow = "pfw_" + key.replace("-", "");
            long payment = jdbc.queryForObject("insert into payments(order_id,provider,payment_method,status,amount,provider_payment_id,"
                    + "created_at,updated_at) values (?,'PAYJP','CARD','PENDING',5980,?,localtimestamp,localtimestamp) returning id",
                    Long.class, order, flow);
            jdbc.update("update orders set active_payment_id=? where id=?", payment, order);
            long transaction = jdbc.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,"
                    + "order_content_revision,idempotency_key,created_at,purpose) values (?,'AUTHORIZE','PENDING',5980,0,?,"
                    + "localtimestamp,'INITIAL_ORDER_AUTHORIZATION') returning id", Long.class, payment, key);
            return new Fixture(order, user, payment, transaction, product, flow);
        });
    }

    PaymentOperationSnapshot snapshot() { return snapshots.find(f.transaction()).orElseThrow(); }

    // ---- the ordinary closure ----

    @ParameterizedTest
    @EnumSource(value = AuthorizationResultStatus.class, names = { "REQUIRES_PAYMENT_METHOD", "REQUIRES_CONFIRMATION", "REQUIRES_ACTION" })
    void closesAfterTheFlowIsReadAsCanceled(AuthorizationResultStatus before) {
        provider = before;
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.CLOSED);

        assertClosed();
        assertThat(sendStatuses()).containsExactly("ACCEPTED");
        var key = ArgumentCaptor.forClass(CancellationRequest.class);
        verify(gateway).cancelUnpaidCheckout(key.capture());
        assertThat(key.getValue().idempotencyKey()).isEqualTo(store.sends(closureId()).getFirst().idempotencyKey());
        assertThat(jdbc.queryForMap("select requested_by_type,requested_by_account_id,requested_by_username,internal_note "
                + "from unpaid_order_closures where order_id=?", f.order()))
                .containsEntry("requested_by_type", "ADMIN").containsEntry("requested_by_account_id", 1L)
                .containsEntry("requested_by_username", "closer").containsEntry("internal_note", ADMIN.note());
        verify(gateway, never()).cancelAuthorization(any());
    }

    @Test void aCancelAnswerAloneNeverClosesTheOrder() {
        doReturn(new CancellationResult(CancellationResultStatus.CANCELLED, f.flow())).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.UNCONFIRMED); // the GET does not show canceled
        assertOpen("SENDING");
        assertThat(sendStatuses()).containsExactly("ACCEPTED");
        provider = AuthorizationResultStatus.FAILED;
        service.recover(100);
        assertClosed();
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
    }

    @Test void anAlreadyCanceledFlowIsClosedWithoutSending() {
        provider = AuthorizationResultStatus.FAILED;
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.CLOSED);
        assertClosed();
        verify(gateway, never()).cancelUnpaidCheckout(any());
    }

    @Test void theSystemCanRequestAClosure() {
        assertThat(service.close(f.order(), new Actor(null, "SYSTEM", null))).isEqualTo(Outcome.CLOSED);
        assertClosed();
    }

    // ---- nothing is sent ----

    @Test void aFinishedAuthorizationIsAcceptedInsteadOfClosed() {
        provider = AuthorizationResultStatus.AUTHORIZED;
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.ACCEPTED);
        assertAccepted();
        assertReleased("AUTHORIZED");
    }

    @Test void aProcessingFlowIsLeftAlone() {
        provider = AuthorizationResultStatus.PENDING;
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.PROCESSING);
        assertUntouched();
        assertReleased("PROCESSING");
    }

    @Test void anUnreadableFlowIsLeftAlone() {
        doThrow(unknown()).when(gateway).retrieveAuthorization(anyString());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.UNREADABLE);
        assertUntouched();
        assertReleased("UNREADABLE");
    }

    enum Ineligible { TOO_RECENT, LEGACY, CANCELLED, NO_FLOW, EXTRA_TRANSACTION, CARD_SEND, AMOUNT, REVISION, AUTHORIZED }

    @ParameterizedTest @EnumSource(Ineligible.class)
    void ineligibleOrdersAreRefusedByTheApplicationAndTheDatabase(Ineligible reason) {
        switch (reason) {
            case TOO_RECENT -> f = fixture(59);
            case LEGACY -> {
                long order = jdbc.queryForObject("insert into orders(user_id,total_amount,status,ordered_at,change_deadline_at,shipping_name,"
                        + "shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,"
                        + "item_subtotal,charge_total,tax_amount,content_revision,acceptance_mode) values (?,5980,'ORDERED',"
                        + "localtimestamp-interval '2 days',localtimestamp-interval '1 day','A','123-4567','A','A','A','0900000000','NONE',"
                        + "5980,0,543,0,'LEGACY') returning id", Long.class, f.user());
                f = new Fixture(order, f.user(), f.payment(), f.transaction(), f.product(), f.flow());
            }
            case CANCELLED -> {
                provider = AuthorizationResultStatus.FAILED;
                synchronization.synchronize(snapshot());
            }
            case NO_FLOW -> jdbc.update("update payments set provider_payment_id=null where id=?", f.payment());
            case EXTRA_TRANSACTION -> jdbc.update("insert into payment_transactions(payment_id,transaction_type,status,amount,"
                    + "order_content_revision,idempotency_key,created_at,completed_at,purpose) values (?,'AUTHORIZE','FAILED',5980,0,?,"
                    + "localtimestamp,localtimestamp,'INITIAL_ORDER_AUTHORIZATION')", f.payment(), UUID.randomUUID().toString());
            case CARD_SEND -> cardSend();
            case AMOUNT -> jdbc.update("update payments set amount=5981 where id=?", f.payment());
            case REVISION -> jdbc.update("update orders set content_revision=1 where id=?", f.order());
            case AUTHORIZED -> {
                provider = AuthorizationResultStatus.AUTHORIZED;
                synchronization.synchronize(snapshot());
            }
        }
        clearInvocations(gateway);
        assertThat(store.closable(f.order())).isFalse();
        assertThatThrownBy(() -> service.close(f.order(), ADMIN)).isInstanceOf(UnpaidOrderClosureException.class);
        assertThatThrownBy(this::insertClosure).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("An unpaid order closure needs an unaccepted order");
        assertThat(count("unpaid_order_closures")).isZero();
        verifyNoInteractions(gateway);
    }

    @Test void sixtyMinutesAreEnough() {
        f = fixture(60);
        assertThat(store.closable(f.order())).isTrue();
    }

    // ---- the race against the authorization ----

    /** The authorization succeeds after the last GET and before the cancel: it is never accepted, and no state disagrees. */
    @Test void anAuthorizationBetweenTheReadAndTheCancelIsNeverAccepted() {
        doAnswer(call -> {
            provider = AuthorizationResultStatus.AUTHORIZED;
            assertThat(results.apply(snapshot(), result(AuthorizationResultStatus.AUTHORIZED, f.flow())))
                    .isEqualTo(PaymentResultApplication.DEFERRED);
            assertThat(synchronization.synchronize(snapshot())).isEqualTo(PaymentSynchronizationOutcome.WAITING);
            assertThatThrownBy(() -> authorizations.refreshAuthorization(f.order(), f.payment()))
                    .isInstanceOfSatisfying(PaymentSafetyStopException.class, e -> assertThat(e.reason())
                            .isEqualTo(PaymentSafetyStopException.Reason.CLOSURE_IN_PROGRESS));
            assertOpen("SENDING");
            provider = AuthorizationResultStatus.FAILED; // the cancel also hits requires_capture
            return new CancellationResult(CancellationResultStatus.CANCELLED, f.flow());
        }).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.CLOSED);
        assertClosed();
        assertThat(count("order_acceptances")).isZero();
    }

    /** The answer is lost while the Payment Flow is authorized: acceptance waits until the same cancel is answered. */
    @Test void anUnknownCancelKeepsTheFenceUntilItIsAnswered() {
        doAnswer(call -> {
            provider = AuthorizationResultStatus.AUTHORIZED;
            throw unknown();
        }).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.UNCONFIRMED);
        assertOpen("SENDING");
        assertThat(sendStatuses()).containsExactly("UNCONFIRMED");
        assertThat(synchronization.synchronize(snapshot())).isEqualTo(PaymentSynchronizationOutcome.WAITING);
        service.recover(100);
        assertOpen("SENDING");
        doAnswer(call -> {
            provider = AuthorizationResultStatus.FAILED;
            return new CancellationResult(CancellationResultStatus.CANCELLED, f.flow());
        }).when(gateway).cancelUnpaidCheckout(any());
        service.recover(100);
        assertClosed();
        var requests = ArgumentCaptor.forClass(CancellationRequest.class);
        verify(gateway, times(3)).cancelUnpaidCheckout(requests.capture());
        assertThat(requests.getAllValues().stream().map(CancellationRequest::idempotencyKey).distinct()).hasSize(1);
        assertThat(sendStatuses()).containsExactly("ACCEPTED");
    }

    @ParameterizedTest @ValueSource(strings = { "invalid_status", "payment_flow_not_cancelable" })
    void aRejectedCancelLiftsTheFenceAndAcceptsTheAuthorization(String code) {
        doAnswer(call -> {
            provider = AuthorizationResultStatus.AUTHORIZED;
            throw failure(400, code);
        }).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.ACCEPTED);
        assertAccepted();
        assertThat(closureStatus()).isEqualTo("REJECTED");
        assertThat(store.sends(closureId()).getFirst().errorCode()).isEqualTo(code);
    }

    @Test void aRejectedCancelOfAnUnpaidFlowLeavesTheOrderAndAllowsAnotherClosure() {
        doThrow(failure(400, "invalid_status")).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.REJECTED);
        assertUntouched();
        assertThat(closureStatus()).isEqualTo("REJECTED");
        assertThat(store.closable(f.order())).isTrue();
    }

    @Test void aRejectedCancelOfACanceledFlowCloses() {
        doAnswer(call -> {
            provider = AuthorizationResultStatus.FAILED;
            throw failure(400, "invalid_status");
        }).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.CLOSED);
        assertClosed();
        assertThat(sendStatuses()).containsExactly("REJECTED");
    }

    @ParameterizedTest
    @CsvSource({ "400,other_code,ERROR", "401,unauthorized,ERROR", "404,not_found,ERROR", "409,idempotency_conflict,ERROR",
            "422,validation_error,ERROR", "500,internal_server_error,ERROR", "409,idempotency_timeout,UNCONFIRMED",
            "429,rate_limit_exceeded,UNCONFIRMED", "502,bad_gateway,UNCONFIRMED", "503,unavailable,UNCONFIRMED", "504,timeout,UNCONFIRMED" })
    void otherAnswersKeepTheFence(int http, String code, String expected) {
        doAnswer(call -> {
            provider = AuthorizationResultStatus.AUTHORIZED;
            throw failure(http, code);
        }).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.UNCONFIRMED);
        assertOpen("SENDING");
        assertThat(sendStatuses()).containsExactly(expected);
        finish();
    }

    @Test void anUnexpectedStatusIsNotASuccess() {
        doReturn(new CancellationResult(CancellationResultStatus.PENDING, f.flow())).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.UNCONFIRMED);
        assertOpen("SENDING");
        assertThat(sendStatuses()).containsExactly("ERROR");
        finish();
    }

    // ---- recovery ----

    @Test void anErrorIsRetriedWithANewKeyAfterTheWaitAndFiveTimesAtMost() {
        doThrow(failure(500, "internal_server_error")).when(gateway).cancelUnpaidCheckout(any());
        service.close(f.order(), ADMIN);
        service.recover(100);
        assertThat(sendStatuses()).hasSize(1); // ten minutes have not passed
        for (int expected = 2; expected <= 5; expected++) {
            impatient().recover(100);
            assertThat(sendStatuses()).hasSize(expected);
        }
        impatient().recover(100);
        assertThat(sendStatuses()).hasSize(5);
        assertThat(jdbc.queryForObject("select count(distinct idempotency_key) from unpaid_order_closure_sends where closure_id=?",
                Integer.class, closureId())).isEqualTo(5);
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.UNCONFIRMED); // the administrator may still try
        assertThat(sendStatuses()).hasSize(6);
        finish();
    }

    @Test void anExpiredKeyIsReplaced() {
        doThrow(unknown()).when(gateway).cancelUnpaidCheckout(any());
        service.close(f.order(), ADMIN);
        waiting(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO).recover(100);
        assertThat(sendStatuses()).containsExactly("EXPIRED", "UNCONFIRMED");
        finish();
    }

    @Test void aCrashAfterTheRequestIsReleasedLaterAndNeverSent() {
        var closure = store.request(f.order(), ADMIN).orElseThrow();
        service.recover(100);
        assertOpen("REQUESTED");
        assertThatThrownBy(() -> service.close(f.order(), ADMIN)).isInstanceOf(UnpaidOrderClosureException.class);
        impatient().recover(100);
        assertReleased("STALE");
        assertThat(store.startSending(closure)).isEmpty(); // the stalled worker wakes up after the release
        verify(gateway, never()).cancelUnpaidCheckout(any());
    }

    /** SENDING is committed before the POST, so a crash may have happened on either side of it. */
    @Test void aCrashWhileSendingIsResolvedWithTheSameKey() {
        var send = store.startSending(store.request(f.order(), ADMIN).orElseThrow()).orElseThrow();
        service.recover(100);
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.UNCONFIRMED);
        verify(gateway, never()).cancelUnpaidCheckout(any());
        impatient().recover(100);
        assertClosed();
        var request = ArgumentCaptor.forClass(CancellationRequest.class);
        verify(gateway).cancelUnpaidCheckout(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(send.idempotencyKey());
    }

    @Test void aProcessingFlowIsNotSentAgain() {
        doThrow(unknown()).when(gateway).cancelUnpaidCheckout(any());
        service.close(f.order(), ADMIN);
        provider = AuthorizationResultStatus.PENDING;
        service.recover(100);
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
        assertOpen("SENDING");
        finish();
    }

    /** The reconciliation reads canceled first; the closure finds it applied and nothing happens twice. */
    @Test void theReconciliationMayApplyTheCanceledFlowFirst() {
        doAnswer(call -> {
            provider = AuthorizationResultStatus.FAILED;
            assertThat(synchronization.synchronize(snapshot())).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
            return new CancellationResult(CancellationResultStatus.CANCELLED, f.flow());
        }).when(gateway).cancelUnpaidCheckout(any());
        assertThat(service.close(f.order(), ADMIN)).isEqualTo(Outcome.CLOSED);
        assertClosed();
    }

    @Test void oneFailureDoesNotStopTheOthers() {
        doThrow(unknown()).when(gateway).cancelUnpaidCheckout(any());
        service.close(f.order(), ADMIN);
        var first = f;
        f = fixture(120);
        service.close(f.order(), ADMIN);
        doAnswer(call -> {
            if (first.flow().equals(call.getArgument(0))) throw new IllegalArgumentException("broken");
            return new AuthorizationRecovery(result(AuthorizationResultStatus.FAILED, call.getArgument(0)), SECRET);
        }).when(gateway).retrieveAuthorization(anyString());
        service.recover(100);
        assertClosed();
        f = first;
        assertOpen("SENDING");
        doAnswer(call -> new AuthorizationRecovery(result(AuthorizationResultStatus.FAILED, call.getArgument(0)), SECRET))
                .when(gateway).retrieveAuthorization(anyString());
        service.recover(100);
        assertClosed();
    }

    // ---- concurrency ----

    @Test void twoAdministratorsSendOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> {
                start.await();
                try { return service.close(f.order(), ADMIN); } catch (UnpaidOrderClosureException refused) { return "refused"; }
            }));
            start.countDown();
            for (var task : tasks) task.get(60, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertClosed();
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
    }

    /** An authorization being applied holds the order; the closure requested meanwhile sees it and is refused. */
    @Test void aClosureWaitsForTheAuthorizationBeingApplied() throws Exception {
        var applied = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> new TransactionTemplate(manager).execute(s -> {
                var outcome = results.apply(snapshot(), result(AuthorizationResultStatus.AUTHORIZED, f.flow()));
                applied.countDown();
                await(commit);
                return outcome;
            }));
            assertThat(applied.await(30, TimeUnit.SECONDS)).isTrue();
            var closure = pool.submit(() -> store.request(f.order(), ADMIN));
            assertThatThrownBy(() -> closure.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            assertThat(winner.get(30, TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.APPLIED);
            assertThat(closure.get(30, TimeUnit.SECONDS)).isEmpty();
        } finally { commit.countDown(); pool.shutdownNow(); }
        assertAccepted();
    }

    /** The other order: an authorization observed while the request is uncommitted waits and is deferred. */
    @Test void anAuthorizationWaitsForTheClosureBeingRequested() throws Exception {
        var requested = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var closing = pool.submit(() -> new TransactionTemplate(manager).execute(s -> {
                insertClosure();
                requested.countDown();
                await(commit);
                return true;
            }));
            assertThat(requested.await(30, TimeUnit.SECONDS)).isTrue();
            var authorization = pool.submit(() -> results.apply(snapshot(), result(AuthorizationResultStatus.AUTHORIZED, f.flow())));
            assertThatThrownBy(() -> authorization.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            assertThat(closing.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(authorization.get(30, TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.DEFERRED);
        } finally { commit.countDown(); pool.shutdownNow(); }
        assertOpen("REQUESTED");
        store.release(closureId(), ReleaseReason.STALE);
    }

    /** A raw status update that skips the application check is serialized and refused by V110 as well. */
    @Test void aRawAuthorizationWaitsForTheClosureAndIsRefused() throws Exception {
        var requested = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var closing = pool.submit(() -> new TransactionTemplate(manager).execute(s -> {
                insertClosure();
                requested.countDown();
                await(commit);
                return true;
            }));
            assertThat(requested.await(30, TimeUnit.SECONDS)).isTrue();
            var update = pool.submit(() -> jdbc.update("update payments set status='AUTHORIZED' where id=?", f.payment()));
            assertThatThrownBy(() -> update.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            assertThat(closing.get(30, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> update.get(30, TimeUnit.SECONDS)).rootCause().hasMessageContaining("The order is being closed as unpaid");
        } finally { commit.countDown(); pool.shutdownNow(); }
        store.release(closureId(), ReleaseReason.STALE);
    }

    static void await(CountDownLatch latch) {
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }

    /** The closure is requested after the saved card service checked the target, just before either of its sends. */
    @ParameterizedTest @EnumSource(CheckoutCardSendStore.SendType.class)
    void aSavedCardPaymentStopsWhenTheClosureStartsFirst(CheckoutCardSendStore.SendType type) {
        when(cards.enabled()).thenReturn(true);
        when(cards.customerIdOf(f.user())).thenReturn("cus_f142");
        when(cardGateway.updateFlowCustomer(any(), any(), any(), any())).thenReturn(
                new com.example.ecsite.payment.savedcard.SavedCardGateway.FlowState(f.flow(), "requires_confirmation", "cus_f142", "pm_f142"));
        doAnswer(call -> {
            if (call.getArgument(4) == type) store.request(f.order(), ADMIN).orElseThrow();
            return call.callRealMethod();
        }).when(cardSends).begin(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
        assertThat(savedCards.pay(f.user(), f.order(), f.payment(), f.transaction(), "pm_f142"))
                .isEqualTo(com.example.ecsite.service.savedcard.CheckoutSavedCardService.PayOutcome.PENDING);
        verify(cardGateway, never()).confirmFlow(any(), any(), any());
        verify(cardGateway, times(type == CheckoutCardSendStore.SendType.CONFIRM ? 1 : 0)).updateFlowCustomer(any(), any(), any(), any());
        assertOpen("REQUESTED");
        // While it is open the service stops before any send.
        savedCards.pay(f.user(), f.order(), f.payment(), f.transaction(), "pm_f142");
        verify(cardGateway, never()).confirmFlow(any(), any(), any());
        store.release(closureId(), ReleaseReason.STALE);
    }

    // ---- V110 ----

    @Test void theDatabaseRefusesAcceptanceWhileAClosureIsOpen() {
        store.request(f.order(), ADMIN).orElseThrow();
        String message = "The order is being closed as unpaid";
        assertThatThrownBy(() -> jdbc.update("update payments set status='AUTHORIZED' where id=?", f.payment())).hasMessageContaining(message);
        assertThatThrownBy(() -> jdbc.update("update orders set accepted_at=timezone('Asia/Tokyo',clock_timestamp()) where id=?", f.order()))
                .hasMessageContaining(message);
        assertThatThrownBy(this::cardSend).hasMessageContaining(message);
        assertThatThrownBy(() -> jdbc.update("update orders set status='CANCELLED',cancelled_at=localtimestamp where id=?", f.order()))
                .rootCause().hasMessageContaining("A cancelled order cannot keep an open unpaid closure");
        assertUntouched();
        store.release(closureId(), ReleaseReason.STALE);
        cardSend();
    }

    @Test void theDatabaseRefusesFalseHistories() {
        var closure = store.request(f.order(), ADMIN).orElseThrow();
        assertThatThrownBy(() -> jdbc.update("update unpaid_order_closures set status='CLOSED' where id=?", closure.id()))
                .rootCause().hasMessageContaining("A closed unpaid order needs");
        assertThatThrownBy(() -> jdbc.update("update unpaid_order_closures set status='REJECTED' where id=?", closure.id()))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> jdbc.update("update unpaid_order_closures set requested_by_username='other' where id=?", closure.id()))
                .hasMessageContaining("cannot be redefined");
        assertThatThrownBy(() -> jdbc.update("delete from unpaid_order_closures where id=?", closure.id())).hasMessageContaining("cannot be removed");
        assertThatThrownBy(() -> jdbc.execute("truncate unpaid_order_closures cascade"))
                .hasMessageContaining("Unpaid order closures cannot be removed");
        assertThatThrownBy(() -> jdbc.execute("truncate unpaid_order_closure_sends"))
                .hasMessageContaining("Unpaid order closure sends cannot be removed");
        assertThatThrownBy(() -> insertSend(closure.id(), "early")).hasMessageContaining("only for a sending closure");
        assertThatThrownBy(this::insertClosure).hasMessageContaining("uq_unpaid_order_closure_open");
        var send = store.startSending(closure).orElseThrow();
        assertThat(store.release(closure.id(), ReleaseReason.STALE)).isFalse();
        assertThatThrownBy(() -> jdbc.update("update unpaid_order_closures set status='RELEASED',release_reason='STALE' where id=?", closure.id()))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> store.reject(closure.id())).hasMessageContaining("only by a rejected cancel");
        assertThatThrownBy(() -> insertSend(closure.id(), "second")).hasMessageContaining("without an open answer");
        store.finishSend(send.id(), SendStatus.ACCEPTED, null);
        store.finishSend(send.id(), SendStatus.ERROR, "late");
        assertThat(sendStatuses()).containsExactly("ACCEPTED");
        assertThatThrownBy(() -> jdbc.update("update unpaid_order_closure_sends set status='ERROR' where id=?", send.id()))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> jdbc.update("update unpaid_order_closure_sends set idempotency_key='other' where id=?", send.id()))
                .hasMessageContaining("cannot be redefined");
        assertThatThrownBy(() -> jdbc.update("delete from unpaid_order_closure_sends where id=?", send.id())).hasMessageContaining("cannot be removed");
        finish();
    }

    @Test void aFailureWithoutAClosureKeepsItsHistory() {
        provider = AuthorizationResultStatus.FAILED;
        synchronization.synchronize(snapshot());
        assertThat(one("select internal_note from order_status_histories where order_id=? and to_status='CANCELLED'", f.order()))
                .isEqualTo("カード与信失敗による注文キャンセル");
    }

    // ---- screens ----

    @Test void theCustomerCannotPayWhileTheOrderIsBeingClosed() throws Exception {
        mvc.perform(get("/orders/{id}", f.order()).with(customer())).andExpect(content().string(containsString("カード決済を再開")));
        doThrow(unknown()).when(gateway).cancelUnpaidCheckout(any());
        service.close(f.order(), ADMIN);

        mvc.perform(get("/orders/{id}", f.order()).with(customer()))
                .andExpect(content().string(containsString("unpaid-closure-in-progress")))
                .andExpect(content().string(not(containsString("カード決済を再開"))));
        mvc.perform(post("/orders/{id}/payment/resume", f.order()).with(customer()).with(csrf()))
                .andExpect(redirectedUrl("/orders/" + f.order()))
                .andExpect(flash().attribute("errorMessage", containsString("決済の状態を確認しています")));
        var session = new MockHttpSession();
        session.setAttribute("checkoutOrderId", f.order());
        session.setAttribute("checkoutPaymentId", f.payment());
        mvc.perform(get("/checkout/payment").session(session).with(customer())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(SECRET))));
        assertThat(snapshots.checkoutTarget(f.order(), f.payment(), f.user())).isEmpty();

        finish();
        mvc.perform(get("/orders/{id}", f.order()).with(customer()))
                .andExpect(content().string(containsString("closed-as-unpaid")))
                .andExpect(content().string(not(containsString("unpaid-closure-in-progress"))));
    }

    @Test void theAdministratorClosesFromTheOrderDetail() throws Exception {
        String name = "f142-" + f.order();
        long admin = jdbc.queryForObject("insert into admin_accounts(username,password,enabled,created_at,updated_at) "
                + "values (?,'unused',true,localtimestamp,localtimestamp) returning id", Long.class, name);
        var account = new com.example.ecsite.security.AdminUserDetails(admin, name, "unused", true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mvc.perform(get("/admin/orders/{id}", f.order()).with(user(account))).andExpect(status().isOk())
                .andExpect(content().string(containsString("決済未完了の注文を終了する</button>")));
        for (var reason : ReleaseReason.values()) {
            store.release(store.request(f.order(), ADMIN).orElseThrow().id(), reason);
        }
        mvc.perform(get("/admin/orders/{id}", f.order()).with(user(account))).andExpect(status().isOk())
                .andExpect(content().string(containsString("PAY.JPで処理中のため中止")))
                .andExpect(content().string(containsString("中断のため中止（取消は未送信）")));
        mvc.perform(post("/admin/orders/{id}/close-unpaid", f.order()).with(customer()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/orders/{id}/close-unpaid", f.order()).with(user(account))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/orders/{id}/close-unpaid", f.order()).param("internalNote", "x".repeat(501)).with(user(account)).with(csrf()))
                .andExpect(flash().attributeExists("errorMessage"));
        verify(gateway, never()).cancelUnpaidCheckout(any());

        mvc.perform(post("/admin/orders/{id}/close-unpaid", f.order()).param("internalNote", " 連絡なし ").with(user(account)).with(csrf()))
                .andExpect(flash().attribute("successMessage", containsString("終了しました")));
        assertClosed();
        assertThat(store.forOrder(f.order()).getFirst().note()).isEqualTo("連絡なし");
        mvc.perform(get("/admin/orders/{id}", f.order()).with(user(account))).andExpect(status().isOk())
                .andExpect(content().string(containsString("連絡なし")))
                .andExpect(content().string(not(containsString("決済未完了の注文を終了する</button>"))));
        mvc.perform(post("/admin/orders/{id}/close-unpaid", f.order()).with(user(account)).with(csrf()))
                .andExpect(flash().attribute("errorMessage", containsString("終了できません")));
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
    }

    // ---- Feature142-B: the automatic closure and the list ----

    static final Duration AFTER = Duration.ofHours(24);
    static final Duration RETRY = Duration.ofHours(6);

    void automatic() { service.closeDue(AFTER, RETRY, 100); }

    @Test void theSchedulerIsDisabledByDefault(@Autowired org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeansOfType(UnpaidOrderClosureScheduler.class)).isEmpty();
        assertThat(context.getBean(com.example.ecsite.config.UnpaidOrderClosureProperties.class).enabled()).isFalse();
    }

    @Test void anOrderUnpaidForTwentyFourHoursIsClosedOnce() {
        f = fixture(1440);
        automatic();
        assertClosed();
        assertThat(jdbc.queryForMap("select requested_by_type,requested_by_account_id,requested_by_username,internal_note "
                + "from unpaid_order_closures where order_id=?", f.order())).containsEntry("requested_by_type", "SYSTEM")
                .containsEntry("requested_by_account_id", null).containsEntry("requested_by_username", "SYSTEM")
                .containsEntry("internal_note", "自動終了");
        automatic();
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
        assertThat(count("unpaid_order_closures")).isEqualTo(1);
    }

    @Test void aYoungerOrderIsLeftAlone() {
        f = fixture(1438);
        automatic();
        assertUntouched();
        assertThat(count("unpaid_order_closures")).isZero();
        assertThat(store.requestDue(f.order(), AFTER, RETRY)).isEmpty();
        verify(gateway, never()).retrieveAuthorization(f.flow());
    }

    /** V110 refuses anything younger than 60 minutes whatever the configured wait is. */
    @Test void sixtyMinutesAreKeptBelowTheConfiguration() {
        f = fixture(58);
        assertThat(store.dueOrders(Duration.ZERO, RETRY, 1000)).doesNotContain(f.order());
        assertThat(store.requestDue(f.order(), Duration.ZERO, RETRY)).isEmpty();
        assertUntouched();
        assertThat(count("unpaid_order_closures")).isZero();
    }

    @Test void anAuthorizedOrderIsAcceptedByTheAutomaticClosure() {
        f = fixture(1500);
        provider = AuthorizationResultStatus.AUTHORIZED;
        automatic();
        assertAccepted();
        automatic();
        assertThat(count("unpaid_order_closures")).isEqualTo(1);
        verify(gateway, never()).cancelUnpaidCheckout(any());
    }

    @Test void aReleasedClosureIsTriedAgainOnlyAfterTheWait() {
        f = fixture(1500);
        provider = AuthorizationResultStatus.PENDING;
        automatic();
        assertReleased("PROCESSING");
        automatic();
        assertThat(store.requestDue(f.order(), AFTER, RETRY)).isEmpty();
        assertThat(count("unpaid_order_closures")).isEqualTo(1);
        provider = AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD;
        service.closeDue(AFTER, Duration.ZERO, 100);
        assertClosed();
        assertThat(count("unpaid_order_closures")).isEqualTo(2);
    }

    @Test void aRejectedClosureIsTriedAgainOnlyAfterTheWait() {
        f = fixture(1500);
        doThrow(failure(400, "invalid_status")).when(gateway).cancelUnpaidCheckout(any());
        automatic();
        assertThat(closureStatus()).isEqualTo("REJECTED");
        automatic();
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
        service.closeDue(AFTER, Duration.ZERO, 100);
        verify(gateway, times(2)).cancelUnpaidCheckout(any());
        provider = AuthorizationResultStatus.FAILED;
        service.closeDue(AFTER, Duration.ZERO, 100);
        assertClosed();
    }

    /** Open closures belong to the recovery: the automatic pass neither resends nor restarts them. */
    @Test void anOpenClosureIsNotTouchedByTheAutomaticPass() {
        f = fixture(1500);
        doThrow(failure(500, "internal_server_error")).when(gateway).cancelUnpaidCheckout(any());
        automatic();
        assertOpen("SENDING");
        assertThat(store.dueOrders(AFTER, Duration.ZERO, 100)).doesNotContain(f.order()); // it would use up the limit
        service.closeDue(AFTER, Duration.ZERO, 100);
        assertThat(store.requestDue(f.order(), AFTER, Duration.ZERO)).isEmpty();
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
        assertThat(count("unpaid_order_closures")).isEqualTo(1);
        finish();
    }

    @Test void theOldestComeFirstUpToTheLimit() {
        var newer = fixture(1500);
        f = fixture(3000);
        assertThat(store.dueOrders(AFTER, RETRY, 1)).containsExactly(f.order());
        service.closeDue(AFTER, RETRY, 1);
        assertClosed();
        assertThat(store.dueOrders(AFTER, RETRY, 1)).containsExactly(newer.order());
        f = newer;
        automatic();
        assertClosed();
    }

    @Test void oneFailureDoesNotStopTheOtherAutomaticClosures() {
        var broken = fixture(3000);
        f = fixture(1500);
        doThrow(new org.springframework.dao.CannotAcquireLockException("fixture")).when(store)
                .requestDue(eq(broken.order()), any(), any());
        automatic();
        assertClosed();
        f = broken;
        assertUntouched();
        reset(store);
        automatic();
        assertClosed();
    }

    @Test void twoProcessesSendOnce() throws Exception {
        f = fixture(1500);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> { start.await(); automatic(); return null; }));
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertClosed();
        assertThat(count("unpaid_order_closures")).isEqualTo(1);
        verify(gateway, times(1)).cancelUnpaidCheckout(any());
    }

    /** The other worker's closure may have ended between the listing and the request. */
    @Test void aListedOrderIsDecidedAgainUnderTheLocks() {
        f = fixture(1500);
        assertThat(store.dueOrders(AFTER, RETRY, 100)).contains(f.order());
        store.release(store.request(f.order(), ADMIN).orElseThrow().id(), ReleaseReason.UNREADABLE);
        assertThat(store.requestDue(f.order(), AFTER, RETRY)).isEmpty();
        var closure = store.requestDue(f.order(), AFTER, Duration.ZERO).orElseThrow();
        store.release(closure.id(), ReleaseReason.UNREADABLE);
        service.closeDue(AFTER, Duration.ZERO, 100);
        assertClosed();
    }

    @Test void theListAndTheCountsShowUnpaidAndStuckOrders() throws Exception {
        long unpaid = queries.summary().unpaid(), stuck = queries.summary().stuck();
        f = fixture(120);
        var young = fixture(5);
        assertThat(queries.summary()).isEqualTo(new AdminUnpaidOrderQueries.Summary(unpaid + 2, stuck));
        assertThat(row(AdminUnpaidOrderQueries.View.CLOSABLE, f).closable()).isTrue();
        assertThat(row(AdminUnpaidOrderQueries.View.CLOSABLE, young)).isNull();
        assertThat(row(AdminUnpaidOrderQueries.View.ALL, young).closable()).isFalse();

        doThrow(failure(400, "invalid_status")).when(gateway).cancelUnpaidCheckout(any());
        service.close(f.order(), ADMIN);
        var rejected = row(AdminUnpaidOrderQueries.View.STUCK, f);
        assertThat(rejected.closureStatus()).isEqualTo("REJECTED");
        assertThat(rejected.sendStatus()).isEqualTo("REJECTED");
        assertThat(rejected.sendCount()).isEqualTo(1);
        assertThat(rejected.closable()).isTrue();
        assertThat(rejected.requestedBy()).isEqualTo("closer");
        assertThat(queries.summary().stuck()).isEqualTo(stuck + 1);

        doThrow(unknown()).when(gateway).cancelUnpaidCheckout(any());
        service.close(f.order(), ADMIN); // a new closure that has only just started sending is not stuck
        assertThat(row(AdminUnpaidOrderQueries.View.STUCK, f)).isNull();
        assertThat(row(AdminUnpaidOrderQueries.View.ALL, f).closable()).isFalse();
        var waited = new AdminUnpaidOrderQueries(jdbc, Duration.ZERO);
        assertThat(waited.summary().stuck()).isEqualTo(stuck + 1);
        assertThat(waited.list(AdminUnpaidOrderQueries.View.STUCK, 0).rows()).anyMatch(r -> r.orderId() == f.order() && r.stuck());
        mvc.perform(get("/admin/unpaid-orders").with(user(new com.example.ecsite.security.AdminUserDetails(1L, "a", "unused", true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))).andExpect(status().isOk())
                .andExpect(content().string(containsString("取消結果を確認中")));
        finish();
        assertThat(row(AdminUnpaidOrderQueries.View.ALL, f)).isNull();
        assertThat(queries.summary()).isEqualTo(new AdminUnpaidOrderQueries.Summary(unpaid + 1, stuck));
    }

    AdminUnpaidOrderQueries.Row row(AdminUnpaidOrderQueries.View view, Fixture fixture) {
        for (int page = 0; ; page++) {
            var result = queries.list(view, page);
            for (var r : result.rows()) if (r.orderId() == fixture.order()) return r;
            if (!result.hasNext()) return null;
        }
    }

    // ---- helpers ----

    RequestPostProcessor customer() {
        return user(new CustomUserDetails(f.user(), "fixture", "unused", true, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    void cardSend() {
        cardSends.begin(f.user(), f.order(), f.payment(), f.transaction(), CheckoutCardSendStore.SendType.FLOW_UPDATE,
                CheckoutCardSendStore.Purpose.SAVED_CARD, f.flow(), "cus_f142", "pm_f142");
    }

    void insertClosure() {
        jdbc.update("insert into unpaid_order_closures(order_id,payment_id,transaction_id,provider_payment_flow_id,requested_by_type,"
                + "requested_by_account_id,requested_by_username,status,created_at) values (?,?,?,?,'ADMIN',1,'closer','REQUESTED',localtimestamp)",
                f.order(), f.payment(), f.transaction(), f.flow());
    }

    void insertSend(long closure, String key) {
        jdbc.update("insert into unpaid_order_closure_sends(closure_id,idempotency_key,status,created_at) values (?,?,'PENDING',localtimestamp)",
                closure, key);
    }

    static PaymentGatewayException unknown() {
        return new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN, null, null, true);
    }

    static PaymentGatewayException failure(int http, String code) {
        var category = http >= 500 ? PaymentGatewayErrorCategory.SERVER_RESULT_UNKNOWN
                : code.equals("idempotency_timeout") ? PaymentGatewayErrorCategory.IDEMPOTENCY_WAIT_TIMEOUT
                : PaymentGatewayErrorCategory.OTHER_HTTP_ERROR;
        return new PaymentGatewayException(category, http, code, true);
    }

    /** Ends an unresolved closure through the production path, so later tests start clean. */
    void finish() {
        provider = AuthorizationResultStatus.FAILED;
        impatient().recover(100);
        assertClosed();
    }

    void assertClosed() {
        assertThat(closureStatus()).isEqualTo("CLOSED");
        assertThat(one("select status from orders where id=?", f.order())).isEqualTo("CANCELLED");
        assertThat(one("select status from payments where id=?", f.payment())).isEqualTo("FAILED");
        assertThat(one("select status from payment_transactions where id=?", f.transaction())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select active_payment_id is null and accepted_at is null from orders where id=?", Boolean.class, f.order())).isTrue();
        assertThat(stock()).isEqualTo(15);
        assertThat(count("stock_movements")).isEqualTo(1);
        assertThat(jdbc.queryForList("select changed_by_type||':'||internal_note from order_status_histories where order_id=? and to_status='CANCELLED'",
                String.class, f.order())).containsExactly("SYSTEM:決済未完了のため注文を終了");
        assertThat(jdbc.queryForList("select event_type from customer_order_notifications where order_id=?", String.class, f.order()))
                .containsExactly("ORDER_CANCELLED");
    }

    void assertAccepted() {
        assertThat(jdbc.queryForObject("select status='ORDERED' and accepted_at is not null from orders where id=?", Boolean.class, f.order())).isTrue();
        assertThat(one("select status from payments where id=?", f.payment())).isEqualTo("AUTHORIZED");
        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(stock()).isEqualTo(14);
    }

    void assertUntouched() {
        assertThat(jdbc.queryForObject("select status='ORDERED' and accepted_at is null and active_payment_id is not null from orders where id=?",
                Boolean.class, f.order())).isTrue();
        assertThat(one("select status from payment_transactions where id=?", f.transaction())).isEqualTo("PENDING");
        assertThat(one("select status from payments where id=?", f.payment())).isIn("PENDING", "REQUIRES_ACTION");
        assertThat(stock()).isEqualTo(14);
        assertThat(count("stock_movements") + count("customer_order_notifications")).isZero();
    }

    void assertOpen(String status) {
        assertThat(closureStatus()).isEqualTo(status);
        assertUntouched();
    }

    void assertReleased(String reason) {
        assertThat(jdbc.queryForMap("select status,release_reason from unpaid_order_closures where order_id=?", f.order()))
                .containsEntry("status", "RELEASED").containsEntry("release_reason", reason);
        verify(gateway, never()).cancelUnpaidCheckout(any());
    }

    int stock() { return jdbc.queryForObject("select stock from products where id=?", Integer.class, f.product()); }
    String closureStatus() { return one("select status from unpaid_order_closures where order_id=? order by id desc limit 1", f.order()); }
    long closureId() { return jdbc.queryForObject("select max(id) from unpaid_order_closures where order_id=?", Long.class, f.order()); }
    List<String> sendStatuses() { return store.sends(closureId()).stream().map(s -> s.status().name()).toList(); }
    int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where order_id=?", Integer.class, f.order()); }
    String one(String sql, long id) { return jdbc.queryForObject(sql, String.class, id); }
}
