package com.example.ecsite.service.order.evidence;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.exception.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidenceValidatorTest.*;
class OrderEditBrowserServiceTest {
    OrderEditPreviewService preview=mock(OrderEditPreviewService.class);
    LegacyOrderEvidenceEnrollment legacy=mock(LegacyOrderEvidenceEnrollment.class);
    @SuppressWarnings("unchecked") ObjectProvider<LegacyOrderEvidenceEnrollment> provider=mock(ObjectProvider.class);
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked") ObjectProvider<OrderEditAdmissionService> admissions=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") ObjectProvider<OrderEditExecutionService> executions=mock(ObjectProvider.class);
    OrderEditAdmissionService admissionService=mock(OrderEditAdmissionService.class);
    OrderEditExecutionService executionService=mock(OrderEditExecutionService.class);
    @SuppressWarnings("unchecked") ObjectProvider<OrderEditCardService> cards=mock(ObjectProvider.class);
    OrderEditBrowserService service=new OrderEditBrowserService(preview,provider,jdbc,admissions,executions,cards);
    LocalDateTime now=LocalDateTime.of(2026,11,4,13,0);
    @BeforeEach void actor(){
        var p=new CustomUserDetails(2L,"buyer","unused",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,"unused",p.getAuthorities()));
        when(provider.getObject()).thenReturn(legacy);when(preview.load(1,2)).thenReturn(page("ORDERED",now.plusHours(1),null));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
    OrderEditPreviewService.PageContext page(String state,LocalDateTime deadline,LocalDateTime preparing){
        return new OrderEditPreviewService.PageContext(new OrderEditCandidate.Context(facts(initial(),List.of()),2,state,now.minusDays(1),deadline,preparing,null,null,now),null);
    }
    OrderEditCandidate.Candidate candidate(){return new OrderEditCandidate.Candidate(facts(initial(),List.of()),initial().start(),List.of(),List.of(),List.of());}
    Map<String,Object> receipt(UUID id,String state){return Map.of("id",id,"submission_key",UUID.randomUUID(),"admission_state","ACCEPTED","execution_state",state);}
    void owned(){when(jdbc.queryForList(anyString(),eq(1L),eq(2L))).thenReturn(List.of(Map.of("id",1L)));}
    @Test void submissionUsesSavedKeyAndOnlyExecutesAcceptedQueuedReceipt(){
        owned();var id=UUID.randomUUID();var key=UUID.randomUUID();var request=new OrderEditCandidate.Request(1,0,List.of());
        when(admissions.getObject()).thenReturn(admissionService);when(executions.getObject()).thenReturn(executionService);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of(receipt(id,"QUEUED")));
        var accepted=new OrderEditAdmissionService.Result(OrderEditAdmissionService.Outcome.ACCEPTED,id,"ACCEPTED","QUEUED","test");
        when(admissionService.submit(any())).thenReturn(accepted);
        assertThat(service.submit(1,key,request,candidate())).isEqualTo(accepted);
        var command=org.mockito.ArgumentCaptor.forClass(OrderEditAdmissionService.Command.class);verify(admissionService).submit(command.capture());
        assertThat(command.getValue().customerId()).isEqualTo(2);assertThat(command.getValue().submissionKey()).isEqualTo(key);verify(executionService).execute(id);
    }
    @Test void unavailableRuntimeNeverResolvesExecutionEvenForAcceptedReceipt(){
        owned();when(admissions.getObject()).thenReturn(admissionService);
        when(admissionService.submit(any())).thenReturn(new OrderEditAdmissionService.Result(OrderEditAdmissionService.Outcome.RUNTIME_UNAVAILABLE,null,null,null,"STOPPED"));
        service.submit(1,UUID.randomUUID(),new OrderEditCandidate.Request(1,0,List.of()),candidate());verifyNoInteractions(executions,executionService);
    }
    @Test void heldReauthorizationContinuesOnlyByExistingReconciliation(){
        var id=UUID.randomUUID();var row=new HashMap<>(receipt(id,"RECOVERY_PENDING"));row.put("route","REAUTHORIZE");
        when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of(row));when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true);
        when(admissions.getObject()).thenReturn(admissionService);when(executions.getObject()).thenReturn(executionService);
        when(admissionService.recover(2,1,(UUID)row.get("submission_key"))).thenReturn(new OrderEditAdmissionService.Result(OrderEditAdmissionService.Outcome.ACCEPTED,id,"ACCEPTED","RECOVERY_PENDING","saved"));
        service.continueAdmission(1,id);verify(executionService).reconcile(id);verify(executionService,never()).execute(any());verifyNoInteractions(preview,cards);
    }
    @Test void cardStartRequiresOwnedReauthorizationAndRuntime(){
        var id=UUID.randomUUID();var row=new HashMap<>(receipt(id,"RECOVERY_PENDING"));row.put("route","RETAIN_AUTH");
        when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of(row));
        assertThat(service.startCard(1,id)).isEmpty();verifyNoInteractions(cards);
        row.put("route","REAUTHORIZE");assertThat(service.startCard(1,id)).isEmpty();verifyNoInteractions(cards);
    }
    @Test void ambiguousSubmissionLookupOnlyReadsOwnedSavedKey(){
        owned();var key=UUID.randomUUID();var id=UUID.randomUUID();
        when(jdbc.queryForList(anyString(),eq(UUID.class),eq(1L),eq(2L),eq(key))).thenReturn(List.of(id));
        assertThat(service.findAdmission(1,key)).isEqualTo(id);verifyNoInteractions(admissions,executions,preview,legacy);
    }
    @ParameterizedTest @ValueSource(strings={"COMPLETED","CLOSED_UNAPPLIED","OBSERVING","REVIEW_REQUIRED"})
    void resultDisplaysPersistedStateWithoutExecuting(String state){
        var id=UUID.randomUUID();var row=new HashMap<>(receipt(id,state));
        if(state.equals("COMPLETED")||state.equals("CLOSED_UNAPPLIED"))row.put("closed_at",now);
        if(state.equals("COMPLETED"))row.put("applied_operation_id",UUID.randomUUID());
        when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of(row));
        var label=switch(state){case "COMPLETED"->"変更完了";case "CLOSED_UNAPPLIED"->"未適用終了";case "OBSERVING"->"処理中";default->"確認待ち";};
        assertThat(service.result(1,id).label()).isEqualTo(label);verifyNoInteractions(admissions,executions);
    }
    @Test void resultReadHasNoAdmissionOrExecutionSideEffects(){
        var id=UUID.randomUUID();when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of(receipt(id,"RECOVERY_PENDING")));
        assertThat(service.result(1,id).label()).isEqualTo("確認待ち");verifyNoInteractions(admissions,executions,admissionService,executionService,legacy);
    }
    @Test void ownedQueuedContinuationDoesNotRevalidateTodaysDeadline(){
        when(preview.load(1,2)).thenReturn(page("ORDERED",now.minusHours(1),null));
        var id=UUID.randomUUID();var row=receipt(id,"QUEUED");when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of(row));
        when(admissions.getObject()).thenReturn(admissionService);when(executions.getObject()).thenReturn(executionService);when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true);
        when(admissionService.recover(2,1,(UUID)row.get("submission_key"))).thenReturn(new OrderEditAdmissionService.Result(OrderEditAdmissionService.Outcome.ACCEPTED,id,"ACCEPTED","QUEUED","saved"));
        service.continueAdmission(1,id);verify(executionService).execute(id);verifyNoInteractions(preview);
    }
    @Test void cardAuthenticationWaitIsDisplayOnly(){
        var id=UUID.randomUUID();var row=new HashMap<>(receipt(id,"RECOVERY_PENDING"));row.put("auth_waiting",true);
        when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of(row));
        assertThat(service.result(1,id).label()).isEqualTo("カード認証待ち");verifyNoInteractions(executions,admissions);
    }
    @Test void getOnlyLoadsOwnedPreviewWithoutResolvingEnrollmentBean(){
        assertThat(service.load(1)).isNotNull();verify(preview).load(1,2);verifyNoInteractions(provider,legacy,jdbc);
        assertThat(OrderEditBrowserService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
    }
    @Test void explicitEnrollmentUsesServerCustomerAndDelegatesExistingLogic(){
        when(legacy.enrollForCustomer(1,2)).thenReturn(new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.ENROLLED,UUID.randomUUID()));
        assertThat(service.enroll(1).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.ENROLLED);verify(preview).load(1,2);verify(legacy).enrollForCustomer(1,2);
    }
    @Test void foreignOrderFailsBeforeResolvingLegacy(){
        when(preview.load(99,2)).thenThrow(new OrderNotFoundException(99L));
        assertThatThrownBy(()->service.enroll(99)).isInstanceOf(OrderNotFoundException.class);verifyNoInteractions(provider,legacy);
    }
    @ParameterizedTest @ValueSource(strings={"expired","preparing","cancelled"})
    void invalidStateStopsBeforeExternalObservation(String kind){
        when(preview.load(1,2)).thenReturn(page(kind.equals("cancelled")?"CANCELLED":"ORDERED",kind.equals("expired")?now:now.plusHours(1),kind.equals("preparing")?now:null));
        assertThatThrownBy(()->service.enroll(1)).isInstanceOf(InvalidOrderStatusException.class);verifyNoInteractions(provider,legacy);
    }
    @ParameterizedTest @ValueSource(strings={"missing","admin","transaction"})
    void unauthorizedOrAmbientTransactionIsRejected(String kind){
        if(kind.equals("missing"))SecurityContextHolder.clearContext();
        if(kind.equals("admin"))SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        if(kind.equals("transaction"))org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(()->service.enroll(1)).isInstanceOfAny(AccessDeniedException.class,IllegalStateException.class);verifyNoInteractions(preview,provider,legacy,jdbc);
    }
    @Test void unavailableProviderConfigurationIsSafeAndDoesNotEnroll(){
        when(provider.getObject()).thenThrow(new org.springframework.beans.factory.BeanCreationException("legacy","missing connection identity"));
        assertThat(service.enroll(1).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.REVIEW_REQUIRED);
        verifyNoInteractions(legacy,jdbc);
    }
    @Test void unknownEnrollmentIsNotReportedAsSuccessOrRetried(){
        when(legacy.enrollForCustomer(1,2)).thenReturn(new LegacyOrderEvidenceEnrollment.Result(LegacyOrderEvidenceEnrollment.Status.COMMIT_UNKNOWN,null));
        assertThat(service.enroll(1).status()).isEqualTo(LegacyOrderEvidenceEnrollment.Status.COMMIT_UNKNOWN);verify(legacy,times(1)).enrollForCustomer(1,2);
    }
    @Test void receiptUuidRequiresMatchingOrderAndCustomerInOneRead(){
        var id=UUID.randomUUID();when(jdbc.queryForList(anyString(),eq(1L),eq(2L),eq(id))).thenReturn(List.of());
        assertThatThrownBy(()->service.admission(1,id)).isInstanceOf(OrderNotFoundException.class);verifyNoInteractions(provider,legacy,preview);
    }
}
