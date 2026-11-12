package com.example.ecsite.service.order.history;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import com.example.ecsite.security.CustomUserDetails;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

class OrderChangeEvidenceContractTest {
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    @Test void customerIdentityComesOnlyFromAuthenticatedPrincipal(){
        var calls=new ArrayList<Long>();
        var result=new OrderChangeEvidenceBundle(Status.COMPLETE,new OrderEvidence(3,42,0,null,null),Map.of(),new Coverage(0,0),List.of());
        var service=new CustomerOrderChangeHistoryReadService((order,customer)->{calls.add(order);calls.add(customer);return result;});
        var user=new CustomUserDetails(42L,"owner","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user,null,"ROLE_USER"));
        assertThat(service.readForCustomer(3)).isSameAs(result);assertThat(calls).containsExactly(3L,42L);
    }
    @Test void missingAndNonCustomerPrincipalsCannotReachReader(){
        var calls=new ArrayList<Long>();var service=new CustomerOrderChangeHistoryReadService((o,c)->{calls.add(o);return null;});
        assertThatThrownBy(()->service.readForCustomer(1)).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin",null,"ROLE_ADMIN"));
        assertThatThrownBy(()->service.readForCustomer(1)).isInstanceOf(AccessDeniedException.class);assertThat(calls).isEmpty();
    }
}
