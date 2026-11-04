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
    OrderEditBrowserService service=new OrderEditBrowserService(preview,provider,jdbc);
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
