package com.example.ecsite.service.order.evidence;

import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderEditFailureIntentServiceTest {
    OrderEditFailureIntentService.Command command(){return new OrderEditFailureIntentService.Command(
        UUID.randomUUID(),UUID.randomUUID(),0,0,UUID.randomUUID(),"ab".repeat(32));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void anonymousCommandCannotOpenConnection(){
        var source=mock(DataSource.class);var service=new OrderEditFailureIntentService(source);
        assertThatThrownBy(()->service.request(command())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(source);
    }
    @Test void anonymousRecoveryCannotReadAnotherCustomersIntent(){
        var source=mock(DataSource.class);var service=new OrderEditFailureIntentService(source);
        assertThatThrownBy(()->service.recover(command())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(source);
    }
    @Test void malformedHistoryAndNegativeVersionAreRejected(){
        var c=command();
        for(String digest:List.of("", "ab", "z".repeat(64), "AB".repeat(32)))
            assertThatThrownBy(()->new OrderEditFailureIntentService.Command(c.id(),c.admission(),0,0,c.generation(),digest))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrderEditFailureIntentService.Command(c.id(),c.admission(),-1,0,c.generation(),c.historyDigest()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrderEditFailureIntentService.Command(c.id(),c.admission(),0,-1,c.generation(),c.historyDigest()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
