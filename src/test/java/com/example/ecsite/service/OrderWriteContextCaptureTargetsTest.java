package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.persistence.EntityManager;
import java.sql.*;
import java.time.LocalDateTime;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import org.hibernate.Session;
import org.hibernate.FlushMode;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderWriteContextCaptureTargetsTest {
    private final EntityManager em=mock(EntityManager.class);
    private final Session session=mock(Session.class);
    private final OrderWriteContext context=new OrderWriteContext(em,mock(PaymentRepository.class),mock(PaymentTransactionRepository.class));
    private final Payment payment=new Payment(new Order(),PaymentProvider.PAYJP,PaymentMethod.CARD,200,LocalDateTime.of(2026,10,19,15,0));
    private final PaymentTransaction transaction=new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,"key",LocalDateTime.of(2026,10,19,15,0));
    @BeforeEach void setup() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(em.unwrap(Session.class)).thenReturn(session); when(session.getHibernateFlushMode()).thenReturn(FlushMode.AUTO);
        when(em.contains(payment)).thenReturn(true); when(em.contains(transaction)).thenReturn(true);
        Connection connection=mock(Connection.class); PreparedStatement statement=mock(PreparedStatement.class); ResultSet result=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getString(1)).thenReturn("read committed"); when(result.getString(2)).thenReturn("off");
        doAnswer(call -> { ((Work)call.getArgument(0)).execute(connection); return null; }).when(session).doWork(any());
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    @Test void checksBothTargetsBeforeFirstRefresh() {
        context.refreshWritableCaptureTargets(payment,transaction);
        var sequence=inOrder(session,em);
        sequence.verify(session).isDirty();
        sequence.verify(session).isReadOnly(payment); sequence.verify(session).isReadOnly(transaction);
        sequence.verify(em).refresh(payment); sequence.verify(em).refresh(transaction);
    }
    @ParameterizedTest @ValueSource(strings={"payment","transaction"})
    void readOnlyTargetRejectsWithoutRefreshingEitherEntity(String which) {
        when(session.isReadOnly(which.equals("payment")?payment:transaction)).thenReturn(true);
        assertThatIllegalStateException().isThrownBy(() -> context.refreshWritableCaptureTargets(payment,transaction));
        verify(em,never()).refresh(any());
    }
    @ParameterizedTest @ValueSource(strings={"dirty","readonly-tx","readonly-session","manual-flush","no-tx"})
    void existingTransactionSafetyRemainsInForce(String mode) {
        switch(mode) {
            case "dirty" -> when(session.isDirty()).thenReturn(true);
            case "readonly-tx" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            case "readonly-session" -> when(session.isDefaultReadOnly()).thenReturn(true);
            case "manual-flush" -> when(session.getHibernateFlushMode()).thenReturn(FlushMode.MANUAL);
            case "no-tx" -> TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThatIllegalStateException().isThrownBy(() -> context.refreshWritableCaptureTargets(payment,transaction));
        verify(em,never()).refresh(any());
    }
    @Test void detachedTargetsAreRejected() {
        when(em.contains(transaction)).thenReturn(false);
        assertThatIllegalArgumentException().isThrownBy(() -> context.refreshWritableCaptureTargets(payment,transaction));
        verify(em,never()).refresh(any());
    }
    @Test void unrelatedReadOnlyEntityIsNotInspectedOrRefreshed() {
        Object unrelated=new Order(); when(session.isReadOnly(unrelated)).thenReturn(true);
        context.refreshWritableCaptureTargets(payment,transaction);
        verify(session,never()).isReadOnly(unrelated); verify(em,never()).refresh(unrelated);
    }
}
