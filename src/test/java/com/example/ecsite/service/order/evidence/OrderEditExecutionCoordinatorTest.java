package com.example.ecsite.service.order.evidence;
import java.util.*;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.ecsite.service.order.evidence.OrderEditExecution.*;
class OrderEditExecutionCoordinatorTest {
    @Test void connectionFailureIsUnknownNotRollback() throws Exception {
        var source=mock(DataSource.class);when(source.getConnection()).thenThrow(new SQLException("offline","08006"));
        var service=new OrderEditExecutionCoordinator(source);var c=new Claim(UUID.randomUUID(),UUID.randomUUID(),0);
        assertThat(service.claim(c).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        assertThat(service.recoverClaim(c).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
    }
    @Test void ambientTransactionCannotSuspendLocks(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->new OrderEditExecutionCoordinator(mock(DataSource.class)).claim(new Claim(UUID.randomUUID(),UUID.randomUUID(),0))).isInstanceOf(IllegalStateException.class);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void noAutomaticProductionConnectionOrGateway(){
        assertThat(OrderEditExecutionCoordinator.class.isAnnotationPresent(org.springframework.stereotype.Service.class)).isFalse();
        assertThat(Arrays.stream(OrderEditExecutionCoordinator.class.getDeclaredFields()).noneMatch(f->f.getType()==com.example.ecsite.payment.PaymentGateway.class)).isTrue();
        assertThatThrownBy(()->new Claim(UUID.randomUUID(),UUID.randomUUID(),-1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void closureIsAlsoIndependentAndConnectionFailureNeverMeansRollback()throws Exception{
        var source=mock(DataSource.class);when(source.getConnection()).thenThrow(new SQLException("offline","08006"));
        var c=new OrderUnappliedClosure.Command(UUID.randomUUID(),UUID.randomUUID(),0,"CLOSED_UNAPPLIED","TEST_CLOSE");
        var service=new OrderEditExecutionCoordinator(source);
        assertThat(service.closeUnapplied(c).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        assertThat(service.recoverClosure(c).outcome()).isEqualTo(Outcome.COMMIT_UNKNOWN);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->service.closeUnapplied(c)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->service.recoverClosure(c)).isInstanceOf(IllegalStateException.class);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
