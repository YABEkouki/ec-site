package com.example.ecsite.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ecsite.service.orderchange.OldCancelRecoveryService;
import com.example.ecsite.security.AdminUserDetails;

@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@com.example.ecsite.support.CaptureConnectionTest
class AdminOldCancelRecoverySecurityPostgresTest {
    @Autowired MockMvc mvc;
    @MockitoBean OldCancelRecoveryService recovery;
    @MockitoBean com.example.ecsite.payment.PaymentGateway gateway;
    final String url="/admin/orders/42/changes/"+UUID.randomUUID()+"/old-cancel-recovery";
    AdminUserDetails actor(){return new AdminUserDetails(9L,"admin","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));}
    @Test void anonymousAndCustomerCannotInvokeRecovery()throws Exception{
        mvc.perform(get(url)).andExpect(status().isFound());
        mvc.perform(post(url).with(csrf())).andExpect(status().isFound());
        mvc.perform(post(url).with(user("customer").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(recovery,gateway);
    }
    @Test void csrfFailureNeverCallsService()throws Exception{
        mvc.perform(post(url).with(user(actor()))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(actor())).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        verifyNoInteractions(recovery,gateway);
    }
    @Test void adminRoleWithWrongPrincipalIsRejectedBeforeService()throws Exception{
        mvc.perform(post(url).with(user("fake").roles("ADMIN")).with(csrf()).param("requestId",UUID.randomUUID().toString()).param("expectedVersion","7").param("reason","VERIFY_OLD_CANCEL"))
            .andExpect(status().isForbidden());verifyNoInteractions(recovery,gateway);
    }
}
