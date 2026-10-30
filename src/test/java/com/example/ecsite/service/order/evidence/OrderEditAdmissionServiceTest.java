package com.example.ecsite.service.order.evidence;

import java.util.*;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;

class OrderEditAdmissionServiceTest {
    OrderEditAdmissionService.Command command(){
        var fixture=new OrderEditCandidateCalculatorTest();var request=fixture.request(new Line(3L,8,2));
        return new OrderEditAdmissionService.Command(2,UUID.randomUUID(),request,fixture.calculator.calculate(fixture.context(),request,List.of(fixture.offer(8,1000))).candidate());
    }
    @Test void unavailableConnectionIsUnknownAndNeverAssumedRolledBack() throws Exception {
        var source=mock(DataSource.class);when(source.getConnection()).thenThrow(new SQLException("offline","08006"));
        var result=new OrderEditAdmissionService(source).submit(command());
        assertThat(result.outcome()).isEqualTo(OrderEditAdmissionService.Outcome.COMMIT_UNKNOWN);assertThat(result.admissionId()).isNull();
        assertThat(new OrderEditAdmissionService(source).recover(2,1,UUID.randomUUID()).outcome()).isEqualTo(OrderEditAdmissionService.Outcome.COMMIT_UNKNOWN);
    }
    @Test void coordinatorRefusesCallerTransactionToAvoidSuspendedOrderLockDeadlock(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->new OrderEditAdmissionService(mock(DataSource.class)).submit(command())).isInstanceOf(IllegalStateException.class);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void serviceHasNoExternalGatewayOrAutomaticBeanConnection(){
        assertThat(OrderEditAdmissionService.class.isAnnotationPresent(org.springframework.stereotype.Service.class)).isFalse();
        assertThat(Arrays.stream(OrderEditAdmissionService.class.getDeclaredFields())
            .noneMatch(field -> field.getType() == com.example.ecsite.payment.PaymentGateway.class)).isTrue();
    }
}
