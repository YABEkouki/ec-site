package com.example.ecsite.service.order.evidence;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.payment.PaymentGateway;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderEditFailureClosureServiceTest {
    final DataSource source=mock(DataSource.class);
    final PaymentGateway gateway=mock(PaymentGateway.class);
    final OrderEditFailureClosureService service=new OrderEditFailureClosureService(source,gateway);
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.setActualTransactionActive(false);}
    @Test void unauthenticatedClosureNeverAccessesDatabaseOrProvider(){
        assertThatThrownBy(()->service.terminate(UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(source,gateway);
    }
    @Test void unauthenticatedRecoveryNeverAccessesDatabaseOrProvider(){
        assertThatThrownBy(()->service.recover(UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(source,gateway);
    }
    @Test void ambientTransactionCannotEncloseProviderObservationOrClosure(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(()->service.terminate(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.recover(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(source,gateway);
    }
}
