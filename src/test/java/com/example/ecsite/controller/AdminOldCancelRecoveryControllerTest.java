package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.example.ecsite.service.orderchange.*;
import com.example.ecsite.security.AdminUserDetails;

class AdminOldCancelRecoveryControllerTest {
    final AdminOldCancelRecoveryViewService views=mock(AdminOldCancelRecoveryViewService.class);
    final OldCancelRecoveryService recovery=mock(OldCancelRecoveryService.class);
    final UUID operation=UUID.randomUUID();final String url="/admin/orders/42/changes/"+operation+"/old-cancel-recovery";
    MockMvc mvc;MockHttpSession session;
    @BeforeEach void setup(){
        var actor=new AdminUserDetails(9L,"admin","unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
        var target=new AdminOldCancelRecoveryViewService.Target(42,operation,"MANUAL_REVIEW","CANCEL_FLOW_EVIDENCE_MISMATCH",1,730,2,910,7,true);
        when(views.page(42,operation)).thenReturn(new AdminOldCancelRecoveryViewService.Page(target,List.of(),null));
        mvc=MockMvcBuilders.standaloneSetup(new AdminOldCancelRecoveryController(views,recovery)).build();session=new MockHttpSession();
    }
    @AfterEach void clear(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    OldCancelRecovery.Command confirmation()throws Exception{
        var result=mvc.perform(get(url).session(session)).andExpect(status().isOk()).andReturn();
        return (OldCancelRecovery.Command)result.getModelAndView().getModel().get("recoveryCommand");
    }
    @Test void getNeverCallsRecoveryAndReusesConfirmationUuid()throws Exception{
        var first=confirmation();assertThat(confirmation()).isEqualTo(first);verifyNoInteractions(recovery);
    }
    @ParameterizedTest @EnumSource(OldCancelRecovery.Detail.class)
    void everyOutcomeHasDistinctSafePrgMessage(OldCancelRecovery.Detail detail)throws Exception{
        var command=confirmation();when(recovery.recoverDetailed(command)).thenReturn(new OldCancelRecovery.Outcome(OldCancelRecovery.Result.HOLD,detail));
        mvc.perform(post(url).session(session).param("requestId",command.requestId().toString()).param("expectedVersion","7").param("reason",command.reason()).param("actorId","88"))
            .andExpect(status().isFound()).andExpect(redirectedUrl(url)).andExpect(flash().attribute("recoveryResult",detail.name()))
            .andExpect(flash().attribute("recoveryMessage",AdminOldCancelRecoveryController.message(detail)));
        verify(recovery).recoverDetailed(command);
    }
    @ParameterizedTest @ValueSource(strings={"uuid","version","reason"})
    void tamperedConfirmationNeverCallsRecovery(String field)throws Exception{
        var command=confirmation();mvc.perform(post(url).session(session).param("requestId",field.equals("uuid")?UUID.randomUUID().toString():command.requestId().toString())
            .param("expectedVersion",field.equals("version")?"8":"7").param("reason",field.equals("reason")?"private":"VERIFY_OLD_CANCEL"))
            .andExpect(status().isBadRequest());verifyNoInteractions(recovery);
    }
    @Test void mismatchedOrderOperationIs404WithoutRecovery()throws Exception{
        when(views.page(42,operation)).thenThrow(new com.example.ecsite.exception.OrderNotFoundException(42L));
        mvc.perform(get(url)).andExpect(status().isNotFound());verifyNoInteractions(recovery);
    }
    @Test void storageFailureDoesNotExposeExceptionText()throws Exception{
        var command=confirmation();when(recovery.recoverDetailed(command)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private client secret"));
        var response=mvc.perform(post(url).session(session).param("requestId",command.requestId().toString()).param("expectedVersion","7").param("reason",command.reason()))
            .andExpect(flash().attribute("recoveryResult","AUDIT_FAILED")).andReturn();
        assertThat(response.getFlashMap().toString()).doesNotContain("private","client secret");
    }
    @Test void ineligiblePageHasNoExecutionCommand()throws Exception{
        var target=new AdminOldCancelRecoveryViewService.Target(42,operation,"MANUAL_REVIEW","OTHER",1,730,2,910,7,false);
        when(views.page(42,operation)).thenReturn(new AdminOldCancelRecoveryViewService.Page(target,List.of(),null));
        mvc.perform(get(url)).andExpect(model().attributeDoesNotExist("recoveryCommand"));verifyNoInteractions(recovery);
    }
}
