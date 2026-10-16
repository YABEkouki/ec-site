package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.FlushMode;
import org.hibernate.jdbc.Work;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Optional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.payment.PaymentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class OrderShippingPreparationServiceTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 16, 14, 0);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final OrderPaymentGuard manual = mock(OrderPaymentGuard.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Session session = mock(Session.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet settings = mock(ResultSet.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentTransactionRepository transactionRepository = mock(PaymentTransactionRepository.class);
    private final OrderShippingPreparationService service = new OrderShippingPreparationService(
            orders, payments, manual, new OrderModificationPolicy(), entityManager,
            paymentRepository, transactionRepository);
    private final Order order = new Order(1L, 1000, DEADLINE.minusHours(4), DEADLINE);

    @BeforeEach void authenticate() throws Exception {
        actor(7L, "first", "ROLE_ADMIN");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        ReflectionTestUtils.setField(order, "id", 1L);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.getHibernateFlushMode()).thenReturn(FlushMode.AUTO);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(settings);
        when(settings.next()).thenReturn(true);
        when(settings.getString(1)).thenReturn("read committed");
        when(settings.getString(2)).thenReturn("off");
        doAnswer(invocation -> { ((Work) invocation.getArgument(0)).execute(connection); return null; })
                .when(session).doWork(any(Work.class));
    }
    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clear();
    }
    private void actor(Long id, String name, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        var principal = new AdminUserDetails(id, name, "password", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "password", authorities));
    }
    private void locked() { when(orders.findByIdForUpdate(1L)).thenReturn(Optional.of(order)); }
    private void unstarted() {
        assertThat(order.getShippingPreparationStartedAt()).isNull();
        assertThat(order.getShippingPreparationStartedByAccountId()).isNull();
        assertThat(order.getShippingPreparationStartedByUsername()).isNull();
        assertThat(order.getShipmentCapturePaymentId()).isNull();
    }

    @ParameterizedTest @CsvSource({"ORDERED,0", "ORDERED,1", "PAID,0", "PAID,1"})
    void startsOnlyAfterPaymentValidationAndDatabaseTime(OrderStatus status, long nanos) {
        ReflectionTestUtils.setField(order, "status", status);
        locked();
        when(orders.findCurrentTimeInTokyo()).thenAnswer(invocation -> { unstarted(); return DEADLINE.plusNanos(nanos); });
        var result = service.start(1L);
        assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(result.startedAt()).isEqualTo(DEADLINE.plusNanos(nanos));
        assertThat(result.startedByAccountId()).isEqualTo(7L);
        assertThat(result.startedByUsername()).isEqualTo("first");
        assertThat(order.getShippingPreparationStartedAt()).isEqualTo(result.startedAt());
        assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(7L);
        assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo("first");
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getTotalAmount()).isEqualTo(1000);
        assertThat(order.getContentRevision()).isZero();
        assertThat(order.getShipmentCapturePaymentId()).isNull();
        var sequence = inOrder(orders, payments, manual);
        sequence.verify(orders).findByIdForUpdate(1L);
        if (status == OrderStatus.ORDERED) sequence.verify(payments).validateCardPaymentForShippingPreparation(order);
        else sequence.verify(manual).validateManualShipment(order);
        sequence.verify(orders).findCurrentTimeInTokyo();
        verifyNoMoreInteractions(orders, payments, manual);
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class, names={"ORDERED", "PAID"})
    void rejectsOneNanosecondBeforeDeadline(OrderStatus status) {
        ReflectionTestUtils.setField(order, "status", status); locked();
        when(orders.findCurrentTimeInTokyo()).thenReturn(DEADLINE.minusNanos(1));
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)); unstarted();
    }

    @ParameterizedTest @CsvSource({"SHIPPED,false", "SHIPPED,true", "CANCELLED,false", "CANCELLED,true"})
    void terminalOrdersAreRejectedEvenIfAlreadyPrepared(OrderStatus status, boolean prepared) {
        if (prepared) order.beginShippingPreparation(DEADLINE, 9L, "original");
        ReflectionTestUtils.setField(order, "status", status); locked();
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L));
        verify(orders).findByIdForUpdate(1L); verifyNoMoreInteractions(orders);
        verifyNoInteractions(payments, manual);
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class, names={"ORDERED", "PAID"})
    void retryByAnotherAdminReturnsOriginalWithoutPaymentOrTimeChecks(OrderStatus status) {
        ReflectionTestUtils.setField(order, "status", status);
        order.beginShippingPreparation(DEADLINE, 9L, "original"); locked();
        for (String username : List.of("first", "another")) {
            actor(7L, username, "ROLE_ADMIN");
            var result = service.start(1L);
            assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.ALREADY_STARTED);
            assertThat(result.startedAt()).isEqualTo(DEADLINE);
            assertThat(result.startedByAccountId()).isEqualTo(9L);
            assertThat(result.startedByUsername()).isEqualTo("original");
        }
        verify(orders, times(2)).findByIdForUpdate(1L); verifyNoMoreInteractions(orders);
        verifyNoInteractions(payments, manual);
    }

    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void rejectsEveryPartialMetadataCombination(int mask) {
        if ((mask & 1)!=0) ReflectionTestUtils.setField(order,"shippingPreparationStartedAt",DEADLINE);
        if ((mask & 2)!=0) ReflectionTestUtils.setField(order,"shippingPreparationStartedByAccountId",9L);
        if ((mask & 4)!=0) ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername","original");
        locked(); assertThatIllegalStateException().isThrownBy(() -> service.start(1L));
        verifyNoInteractions(payments, manual); verify(orders, never()).findCurrentTimeInTokyo();
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class, names={"ORDERED", "PAID"})
    void paymentFailureNeverMutatesMetadataOrReadsTime(OrderStatus status) {
        ReflectionTestUtils.setField(order,"status",status); locked();
        if (status==OrderStatus.ORDERED) doAnswer(i -> { unstarted(); throw new IllegalStateException("invalid card"); })
                .when(payments).validateCardPaymentForShippingPreparation(order);
        else doAnswer(i -> { unstarted(); throw new IllegalStateException("invalid manual"); })
                .when(manual).validateManualShipment(order);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)); unstarted();
        verify(orders, never()).findCurrentTimeInTokyo();
    }

    @Test void missingOrder() {
        when(orders.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.start(1L)).isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(payments, manual);
    }
    @Test void databaseClockFailureDoesNotMutate() {
        locked(); when(orders.findCurrentTimeInTokyo()).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)); unstarted();
    }
    @Test void nullDatabaseTimeFailsClosed() {
        locked(); assertThatIllegalStateException().isThrownBy(() -> service.start(1L)); unstarted();
    }
    @ParameterizedTest @NullSource @ValueSource(longs={0,-1})
    void invalidOrderIdBeforeAnyRepositoryCall(Long id) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.start(id));
        verifyNoInteractions(orders, payments, manual);
    }
    @Test void noAuthenticationBeforeLock() {
        SecurityContextHolder.clearContext(); denied();
    }
    @Test void unauthenticatedPrincipalBeforeLock() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "password")); denied();
    }
    @Test void nonAdminRoleBeforeLock() { actor(7L,"admin","ROLE_USER"); denied(); }
    @Test void wrongPrincipalTypeBeforeLock() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","password",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))); denied();
    }
    @ParameterizedTest @NullSource @ValueSource(longs={0,-1})
    void invalidActorIdBeforeLock(Long id) { actor(id,"admin","ROLE_ADMIN"); denied(); }
    @ParameterizedTest @NullSource @ValueSource(strings={"", " ", "\t", "　"})
    void invalidActorUsernameBeforeLock(String name) { actor(7L,name,"ROLE_ADMIN"); denied(); }
    @Test void longActorUsernameBeforeLock() { actor(7L,"x".repeat(101),"ROLE_ADMIN"); denied(); }
    @Test void dirtySessionRejectedWithoutQueryFlushOrRefresh() {
        when(session.isDirty()).thenReturn(true);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("未flush");
        verifyNoInteractions(orders, payments, manual, paymentRepository, transactionRepository, connection);
        verify(entityManager, never()).flush();
        verify(entityManager, never()).refresh(any());
        unstarted();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void individuallyReadOnlyOrderIsRejectedBeforeRefreshEvenIfPrepared(boolean prepared) {
        if (prepared) order.beginShippingPreparation(DEADLINE, 9L, "original");
        var beforeAt = order.getShippingPreparationStartedAt();
        var beforeId = order.getShippingPreparationStartedByAccountId();
        var beforeName = order.getShippingPreparationStartedByUsername();
        locked(); when(session.isReadOnly(order)).thenReturn(true);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("読取り専用");
        verify(orders).findByIdForUpdate(1L); verifyNoMoreInteractions(orders);
        verify(session).isReadOnly(order);
        verify(entityManager, never()).refresh(any());
        verify(entityManager, never()).flush();
        verify(session, never()).setReadOnly(any(), anyBoolean());
        verifyNoInteractions(payments, manual, paymentRepository, transactionRepository);
        assertThat(order.getShippingPreparationStartedAt()).isEqualTo(beforeAt);
        assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(beforeId);
        assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo(beforeName);
        assertThat(order.getShipmentCapturePaymentId()).isNull();
    }
    @Test void individuallyWritableOrderProceedsAfterTheReadOnlyCheck() {
        locked(); when(session.isReadOnly(order)).thenReturn(false);
        when(orders.findCurrentTimeInTokyo()).thenReturn(DEADLINE);
        assertThat(service.start(1L).status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        var sequence = inOrder(orders, session, entityManager, payments);
        sequence.verify(orders).findByIdForUpdate(1L);
        sequence.verify(session).isReadOnly(order);
        sequence.verify(entityManager).refresh(order);
        sequence.verify(payments).validateCardPaymentForShippingPreparation(order);
        sequence.verify(orders).findCurrentTimeInTokyo();
    }

    @Test void requiresAnActualTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("書込み");
        verifyNoInteractions(entityManager, orders, connection);
    }
    @Test void rejectsReadOnlyTransactionBeforeQueries() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("書込み");
        verifyNoInteractions(entityManager, orders, connection);
    }
    @Test void rejectsReadOnlySession() {
        when(session.isDefaultReadOnly()).thenReturn(true);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("書込み");
        verifyNoInteractions(orders, connection);
    }
    @Test void rejectsManualFlushMode() {
        when(session.getHibernateFlushMode()).thenReturn(FlushMode.MANUAL);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("書込み");
        verifyNoInteractions(orders, connection);
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"repeatable read", "serializable", "", "unknown"})
    void rejectsAnythingOtherThanConfirmedReadCommitted(String isolation) throws Exception {
        when(settings.getString(1)).thenReturn(isolation);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("READ COMMITTED");
        verifyNoInteractions(orders, paymentRepository, transactionRepository, payments, manual);
        verify(entityManager, never()).refresh(any()); unstarted();
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"on", "", "unknown"})
    void rejectsAnythingOtherThanConfirmedWritableServer(String readOnly) throws Exception {
        when(settings.getString(2)).thenReturn(readOnly);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("書込み");
        verifyNoInteractions(orders); unstarted();
    }
    @Test void rejectsMissingServerSettings() throws Exception {
        when(settings.next()).thenReturn(false);
        assertThatIllegalStateException().isThrownBy(() -> service.start(1L)).withMessageContaining("確認できません");
        verifyNoInteractions(orders); unstarted();
    }
    @Test void serverSettingsFailureNeverFallsBackToAssumedDefaults() throws Exception {
        when(statement.executeQuery()).thenThrow(new SQLException("connection failed"));
        assertThatThrownBy(() -> service.start(1L)).hasMessageContaining("connection failed");
        verifyNoInteractions(orders); unstarted();
    }
    @Test void refreshesParentsAndHistoryBeforeEligibilityAndDatabaseTime() {
        Payment payment = mock(Payment.class);
        PaymentTransaction transaction = mock(PaymentTransaction.class);
        when(payment.getId()).thenReturn(10L);
        when(paymentRepository.findByOrderIdForUpdate(1L)).thenReturn(List.of(payment));
        when(transactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(List.of(transaction));
        locked(); when(orders.findCurrentTimeInTokyo()).thenReturn(DEADLINE);
        service.start(1L);
        var sequence = inOrder(orders, entityManager, paymentRepository, transactionRepository, payments);
        sequence.verify(entityManager).unwrap(Session.class);
        sequence.verify(orders).findByIdForUpdate(1L);
        sequence.verify(entityManager).refresh(order);
        sequence.verify(paymentRepository).findByOrderIdForUpdate(1L);
        sequence.verify(entityManager).refresh(payment);
        sequence.verify(transactionRepository).findByPaymentIdOrderByCreatedAtAscIdAsc(10L);
        sequence.verify(entityManager).refresh(transaction);
        sequence.verify(payments).validateCardPaymentForShippingPreparation(order);
        sequence.verify(orders).findCurrentTimeInTokyo();
    }

    private void denied() {
        assertThatThrownBy(() -> service.start(1L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(orders, payments, manual); unstarted();
    }
}
