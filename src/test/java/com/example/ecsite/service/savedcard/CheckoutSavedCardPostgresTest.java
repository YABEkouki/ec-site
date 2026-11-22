package com.example.ecsite.service.savedcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.entity.User;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;
import com.example.ecsite.repository.UserRepository;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.savedcard.CheckoutSavedCardService.PayOutcome;
import com.example.ecsite.service.savedcard.SavedCardPostgresTest.FakePayJp;
import com.example.ecsite.support.isolation.IsolatedTestMode;

/** Feature139-B: V106 guard, saved card payments and card registration at checkout against an in-memory PAY.JP. */
@SpringBootTest(properties = { "logging.level.root=WARN", "spring.jpa.show-sql=false",
        "app.payment.discrepancy-audit.notification.manual-resend-scheduler-enabled=false",
        "payjp.customer.id-prefix=t139b-" })
@AutoConfigureMockMvc
@com.example.ecsite.support.CaptureConnectionTest
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@IsolatedTestMode(IsolatedTestMode.Mode.SHARED)
class CheckoutSavedCardPostgresTest {

    @TestConfiguration
    static class FakeProvider {
        @Bean @Primary FakePayJp fakePayJp() { return new FakePayJp(); }
    }

    static final String SECRET = "synthetic_saved_card_secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired OrderStatusHistoryService histories;
    @Autowired FakePayJp payjp;
    @Autowired SavedCardService savedCards;
    @Autowired CheckoutSavedCardService checkout;
    @MockitoBean PaymentGateway gateway;

    record Fixture(long order, long user, long payment, long transaction, String flow) {}

    Fixture f;

    @BeforeEach
    void setUp() {
        f = fixture(null);
        // The payment gateway reads the fake Payment Flow, so the existing refresh/apply path decides the result.
        doAnswer(i -> {
            String flow = i.getArgument(0);
            var state = payjp.flows.get(flow);
            var status = switch (state.status()) {
                case "requires_capture" -> AuthorizationResultStatus.AUTHORIZED;
                case "requires_action" -> AuthorizationResultStatus.REQUIRES_ACTION;
                case "requires_confirmation" -> AuthorizationResultStatus.REQUIRES_CONFIRMATION;
                case "canceled" -> AuthorizationResultStatus.FAILED;
                default -> AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD;
            };
            return new AuthorizationRecovery(new AuthorizationResult(status,
                    status == AuthorizationResultStatus.AUTHORIZED ? flow : null, null, null), SECRET);
        }).when(gateway).retrieveAuthorization(anyString());
    }

    Fixture fixture(Long existingUser) {
        return new TransactionTemplate(manager).execute(s -> {
            var now = orders.findCurrentTimeInTokyo();
            String key = UUID.randomUUID().toString();
            User customer;
            if (existingUser == null) {
                customer = new User();
                customer.setUsername("f139b-" + key);
                customer.setPassword("unused");
                customer.setEnabled(true);
                customer.setCreatedAt(now);
                customer.setUpdatedAt(now);
                users.saveAndFlush(customer);
            } else {
                customer = users.findById(existingUser).orElseThrow();
            }
            var order = new Order(customer.getId(), 750, now, now.plusHours(1));
            orders.saveAndFlush(order);
            histories.record(order, null, OrderStatus.ORDERED, OrderStatusHistoryActorType.USER, customer.getId(), customer.getUsername(), null);
            var payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 750, now);
            payment.setProviderPaymentId("pfw_" + key.replace("-", ""), now);
            payments.saveAndFlush(payment);
            order.bindActivePayment(payment);
            orders.flush();
            var auth = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, 750, 0, key, now,
                    PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
            transactions.saveAndFlush(auth);
            payjp.newFlow(payment.getProviderPaymentId());
            return new Fixture(order.getId(), customer.getId(), payment.getId(), auth.getId(), payment.getProviderPaymentId());
        });
    }

    String registered(String fingerprint, boolean secured) {
        var start = savedCards.startRegistration(f.user());
        String pm = payjp.browserConfirms(start.setupFlowId(), fingerprint, 2030);
        savedCards.completeRegistration(f.user(), start.setupFlowId());
        if (secured) payjp.secured.add(pm);
        return pm;
    }

    MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute("checkoutOrderId", f.order());
        session.setAttribute("checkoutPaymentId", f.payment());
        return session;
    }

    RequestPostProcessor customer() {
        return user(new CustomUserDetails(f.user(), "fixture", "unused", true, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    PayOutcome pay(String pm) {
        return checkout.pay(f.user(), f.order(), f.payment(), f.transaction(), pm);
    }

    List<String> sends() {
        return jdbc.queryForList("select send_type||':'||purpose||':'||status||coalesce(':'||error_code,'') from checkout_card_sends "
                + "where transaction_id=? order by id", String.class, f.transaction());
    }

    String paymentStatus() {
        return jdbc.queryForObject("select status from payments where id=?", String.class, f.payment());
    }

    // ---- V106 guard -------------------------------------------------------------------------------------------

    @Test
    void sendsNeedThePendingInitialAuthorizationOfTheUsersOrder() {
        var store = new CheckoutCardSendStore(jdbc, manager);
        var other = fixture(null);
        assertThatThrownBy(() -> store.begin(other.user(), f.order(), f.payment(), f.transaction(),
                CheckoutCardSendStore.SendType.FLOW_UPDATE, CheckoutCardSendStore.Purpose.REGISTER_NEW_CARD, f.flow(), "c-1", null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("pending initial authorization");
        assertThatThrownBy(() -> store.begin(f.user(), f.order(), f.payment(), f.transaction(),
                CheckoutCardSendStore.SendType.FLOW_UPDATE, CheckoutCardSendStore.Purpose.REGISTER_NEW_CARD, "pfw_other", "c-1", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> store.begin(f.user(), f.order(), f.payment(), f.transaction(),
                CheckoutCardSendStore.SendType.CONFIRM, CheckoutCardSendStore.Purpose.SAVED_CARD, f.flow(), "c-1", "pm_x"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("succeeded saved card update");

        var update = store.begin(f.user(), f.order(), f.payment(), f.transaction(),
                CheckoutCardSendStore.SendType.FLOW_UPDATE, CheckoutCardSendStore.Purpose.SAVED_CARD, f.flow(), "c-1", "pm_x");
        assertThatThrownBy(() -> store.begin(f.user(), f.order(), f.payment(), f.transaction(),
                CheckoutCardSendStore.SendType.FLOW_UPDATE, CheckoutCardSendStore.Purpose.SAVED_CARD, f.flow(), "c-1", "pm_x"))
                .isInstanceOf(CheckoutCardSendStore.BusyException.class);
        store.finish(update.id(), CheckoutCardSendStore.Status.PENDING, CheckoutCardSendStore.Status.SUCCEEDED, null);
        for (int i = 0; i < CheckoutCardSendStore.MAX_CONFIRMS; i++) {
            var confirm = store.begin(f.user(), f.order(), f.payment(), f.transaction(),
                    CheckoutCardSendStore.SendType.CONFIRM, CheckoutCardSendStore.Purpose.SAVED_CARD, f.flow(), "c-1", "pm_x");
            store.finish(confirm.id(), CheckoutCardSendStore.Status.PENDING, CheckoutCardSendStore.Status.FAILED, "card_declined");
        }
        // The database refuses a fourth confirm even without the application check.
        assertThatThrownBy(() -> jdbc.update("insert into checkout_card_sends(user_id,order_id,payment_id,transaction_id,send_type,"
                + "purpose,provider_payment_flow_id,provider_customer_id,provider_payment_method_id,idempotency_key,status,created_at) "
                + "values (?,?,?,?,'CONFIRM','SAVED_CARD',?,'c-1','pm_x','k4',"
                + "'PENDING',localtimestamp)", f.user(), f.order(), f.payment(), f.transaction(), f.flow()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("three");
        assertThatThrownBy(() -> jdbc.update("update checkout_card_sends set provider_payment_method_id='pm_y' where id=?", update.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("redefined");
        assertThatThrownBy(() -> jdbc.update("delete from checkout_card_sends where id=?", update.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("removed");
    }

    @Test
    void attachIsAnOperationTypeOfSavedCards() {
        var store = new SavedCardOperationStore(jdbc, manager);
        var op = store.begin(f.user(), SavedCardOperationStore.Type.ATTACH, SavedCardOperationStore.Reason.USER, "c-1", "pm_a");
        assertThat(op.status()).isEqualTo(SavedCardOperationStore.Status.PENDING);
        store.finish(op.id(), SavedCardOperationStore.Status.PENDING, SavedCardOperationStore.Status.SUCCEEDED, null, null);
        assertThatThrownBy(() -> store.begin(f.user(), SavedCardOperationStore.Type.ATTACH, SavedCardOperationStore.Reason.LIMIT, "c-1", "pm_a"))
                .isInstanceOf(DataAccessException.class);
    }

    // ---- paying with a saved card -----------------------------------------------------------------------------

    @Test
    void securedSavedCardAuthorizesThroughTheExistingObservation() throws Exception {
        String pm = registered("fp-1", true);
        mvc.perform(post("/checkout/payment/saved-card").session(session()).with(customer()).with(csrf())
                        .param("expectedOrderId", "" + f.order()).param("expectedPaymentId", "" + f.payment())
                        .param("expectedTransactionId", "" + f.transaction()).param("paymentMethodId", pm))
                .andExpect(redirectedUrl("/checkout/complete"));
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
        assertThat(sends()).containsExactly("FLOW_UPDATE:SAVED_CARD:SUCCEEDED", "CONFIRM:SAVED_CARD:SUCCEEDED");
        assertThat(payjp.flows.get(f.flow()).customerId()).isEqualTo("t139b-" + f.user());
    }

    @Test
    void threeDSecureOfOurOwnConfirmContinuesInTheBrowser() throws Exception {
        String pm = registered("fp-1", false);
        // Before anything was sent, a requires_action flow stays observation-only (existing behaviour).
        payjp.browserCompletes(f.flow(), "requires_action", null);
        mvc.perform(get("/checkout/payment").session(session()).with(customer()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("handleNextAction"))));
        payjp.browserCompletes(f.flow(), "requires_payment_method", null);

        assertThat(pay(pm)).isEqualTo(PayOutcome.REQUIRES_ACTION);
        mvc.perform(get("/checkout/payment").session(session()).with(customer()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("本人認証へ進む"), containsString("handleNextAction"),
                        containsString(SECRET), not(containsString("widgets.confirmPayment")))));
        payjp.browserCompletes(f.flow(), "requires_capture", null);
        mvc.perform(post("/checkout/payment/continue").session(session()).with(customer()).with(csrf())
                        .param("expectedOrderId", "" + f.order()).param("expectedPaymentId", "" + f.payment())
                        .param("expectedTransactionId", "" + f.transaction()))
                .andExpect(redirectedUrl("/checkout/complete"));
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void declinedCardCanBeReplacedUpToThreeConfirms() {
        String bad = registered("fp-bad", true);
        String good = registered("fp-good", true);
        payjp.declining.add(bad);
        assertThat(pay(bad)).isEqualTo(PayOutcome.DECLINED);
        assertThat(sends()).containsExactly("FLOW_UPDATE:SAVED_CARD:SUCCEEDED", "CONFIRM:SAVED_CARD:FAILED:card_declined");
        assertThat(paymentStatus()).isNotEqualTo("AUTHORIZED");
        assertThat(pay(good)).isEqualTo(PayOutcome.AUTHORIZED);
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void afterThreeConfirmsNoMoreAreSent() {
        String bad = registered("fp-bad", true);
        payjp.declining.add(bad);
        for (int i = 0; i < 3; i++) assertThat(pay(bad)).isEqualTo(PayOutcome.DECLINED);
        int confirms = payjp.confirmCalls;
        assertThat(pay(bad)).isEqualTo(PayOutcome.LIMIT_REACHED);
        assertThat(payjp.confirmCalls).isEqualTo(confirms);
    }

    @Test
    void otherPeoplesCardsAndStaleTargetsSendNothing() {
        int updates = payjp.updateCalls; // the fake is shared by the tests of this class
        payjp.cards.put("pm_foreign", SavedCardPostgresTest.card("pm_foreign", "someone-else", "fp-9", 2030, null));
        assertThat(pay("pm_foreign")).isEqualTo(PayOutcome.CARD_NOT_USABLE);
        String pm = registered("fp-1", true);
        assertThat(checkout.pay(f.user(), f.order(), f.payment(), f.transaction() + 999, pm)).isEqualTo(PayOutcome.PENDING);
        var other = fixture(null);
        assertThat(checkout.pay(other.user(), f.order(), f.payment(), f.transaction(), pm)).isEqualTo(PayOutcome.PENDING);
        assertThat(payjp.updateCalls).isEqualTo(updates);
        assertThat(sends()).isEmpty();
    }

    @Test
    void lostConfirmIsSettledByReadingTheFlow() throws Exception {
        String pm = registered("fp-1", true);
        // The confirm never reached PAY.JP: the flow stays requires_confirmation.
        payjp.failNext = new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "transport");
        payjp.failOn = "confirm";
        assertThat(pay(pm)).isEqualTo(PayOutcome.CONFIRM_NOT_SENT);
        // Settled at once by reading the flow (still requires_confirmation): the confirm did not arrive.
        assertThat(sends()).containsExactly("FLOW_UPDATE:SAVED_CARD:SUCCEEDED", "CONFIRM:SAVED_CARD:FAILED:not_applied");
        // The payment page offers the saved card again (no new card form while the card is set on the flow).
        mvc.perform(get("/checkout/payment").session(session()).with(customer()))
                .andExpect(status().isOk()).andExpect(content().string(allOf(containsString("このカードで支払う"),
                        not(containsString("widgets.confirmPayment")))));
        // This time the confirm reaches PAY.JP but its answer is lost: reading the flow settles it as delivered.
        payjp.failNext = new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "transport");
        payjp.failOn = "confirm";
        payjp.applyDespiteFailure = true;
        assertThat(pay(pm)).isEqualTo(PayOutcome.AUTHORIZED);
        assertThat(sends()).last().isEqualTo("CONFIRM:SAVED_CARD:SUCCEEDED");
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
    }

    // ---- registering the new card of a checkout ----------------------------------------------------------------

    @Test
    void newCardIsRegisteredAfterTheAuthorizationWhenAsked() throws Exception {
        String existing = registered("fp-old", true);
        var html = mvc.perform(get("/checkout/payment").session(session()).with(customer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("登録済みのカードで支払う", existing, "このカードを登録する", "/checkout/payment/register-intent",
                "widgets.confirmPayment");

        mvc.perform(post("/checkout/payment/register-intent").session(session()).with(customer()).with(csrf())
                        .param("expectedOrderId", "" + f.order()).param("expectedPaymentId", "" + f.payment())
                        .param("expectedTransactionId", "" + f.transaction()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"ready\":true")));
        assertThat(payjp.flows.get(f.flow()).customerId()).isEqualTo("t139b-" + f.user());
        assertThat(payjp.flows.get(f.flow()).paymentMethodId()).isNull();

        String entered = payjp.browserCard("fp-new");
        payjp.browserCompletes(f.flow(), "requires_capture", entered);
        mvc.perform(post("/checkout/payment/continue").session(session()).with(customer()).with(csrf())
                        .param("expectedOrderId", "" + f.order()).param("expectedPaymentId", "" + f.payment())
                        .param("expectedTransactionId", "" + f.transaction()))
                .andExpect(redirectedUrl("/checkout/complete"))
                .andExpect(flash().attribute("cardRegistrationMessage", "お支払いに使ったカードを登録しました。"));
        assertThat(payjp.cards.get(entered).customerId()).isEqualTo("t139b-" + f.user());
        assertThat(jdbc.queryForList("select operation_type||':'||status from saved_card_operations where user_id=? "
                + "and operation_type='ATTACH'", String.class, f.user())).containsExactly("ATTACH:SUCCEEDED");
        assertThat(savedCards.cards(f.user()).cards()).extracting(SavedCardService.CardView::id).contains(entered, existing);
    }

    @Test
    void duplicateCheckoutCardIsDetachedWithoutTouchingTheAuthorization() throws Exception {
        registered("fp-same", true);
        assertThat(checkout.prepareRegistration(f.user(), f.order(), f.payment(), f.transaction()))
                .isEqualTo(CheckoutSavedCardService.RegisterOutcome.READY);
        String entered = payjp.browserCard("fp-same");
        payjp.browserCompletes(f.flow(), "requires_capture", entered);
        mvc.perform(post("/checkout/payment/continue").session(session()).with(customer()).with(csrf())
                        .param("expectedOrderId", "" + f.order()).param("expectedPaymentId", "" + f.payment())
                        .param("expectedTransactionId", "" + f.transaction()))
                .andExpect(redirectedUrl("/checkout/complete"))
                .andExpect(flash().attribute("cardRegistrationMessage", "お支払いに使ったカードは、すでに登録されています。"));
        assertThat(payjp.cards.get(entered).detachedAt()).isNotNull();
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void withoutTheCheckboxNothingIsRegisteredAndFailuresNeverBlockTheOrder() throws Exception {
        String entered = payjp.browserCard("fp-x");
        payjp.browserCompletes(f.flow(), "requires_capture", entered);
        assertThat(checkout.completeRegistration(f.user(), f.order(), f.payment()))
                .isEqualTo(CheckoutSavedCardService.CheckoutRegistration.NONE);

        var second = fixture(f.user());
        assertThat(checkout.prepareRegistration(f.user(), second.order(), second.payment(), second.transaction()))
                .isEqualTo(CheckoutSavedCardService.RegisterOutcome.READY);
        String card = payjp.browserCard("fp-y");
        payjp.browserCompletes(second.flow(), "requires_capture", card);
        payjp.readsFail = true;
        assertThat(checkout.completeRegistration(f.user(), second.order(), second.payment()))
                .isEqualTo(CheckoutSavedCardService.CheckoutRegistration.FAILED);
        payjp.readsFail = false;
    }
}
