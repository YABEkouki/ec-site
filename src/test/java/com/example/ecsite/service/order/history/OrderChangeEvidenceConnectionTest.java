package com.example.ecsite.service.order.history;

import javax.sql.DataSource;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderChangeEvidenceConnectionTest {
    @Test void brokenAutocommitConnectionFailsBeforeAnyStatement()throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(connection));
        try{
            assertThatThrownBy(()->new JdbcCustomerOrderChangeEvidenceReader(source).readForCustomer(1,2)).isInstanceOf(IllegalStateException.class);
            verify(connection,never()).prepareStatement(anyString());
        }finally{
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }
    }
}
