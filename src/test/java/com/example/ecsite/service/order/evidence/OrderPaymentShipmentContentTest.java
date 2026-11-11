package com.example.ecsite.service.order.evidence;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidence.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidenceValidatorTest.*;

class OrderPaymentShipmentContentTest {
    private OrderPaymentEvidenceDecision content(Facts facts)throws Exception {
        return (OrderPaymentEvidenceDecision)OrderPaymentEvidenceValidator.class.getMethod("validateContent",Facts.class)
            .invoke(new OrderPaymentEvidenceValidator(),facts);
    }
    @Test void actualCapturedPaymentAndPendingCaptureDoNotInvalidateSavedContent()throws Exception {
        var f=facts(initial(),List.of());
        var captured=copy(f,f.current(),10L,List.of(new Payment(10,1,"PAYJP","CARD","CAPTURED",6000,"pfw_test")),f.authorizations(),f.epoch(),f.histories(),null,false,true);
        assertThat(new OrderPaymentEvidenceValidator().validate(captured).locallyVerified()).isFalse();
        assertThat(content(captured).locallyVerified()).isTrue();
    }
    @Test void pendingCaptureStillRequiresCompleteChainAndSuccessfulAuthorization()throws Exception {
        var e=initial();var c=change(e,null,31,5000,7);var f=facts(e,List.of(c));
        var pending=copy(f,f.current(),10L,f.payments(),f.authorizations(),e,f.histories(),c.id(),false,true);
        assertThat(content(pending).locallyVerified()).isTrue();
        var corrupt=copy(pending,pending.current(),10L,pending.payments(),pending.authorizations(),e,List.of(),c.id(),false,true);
        assertThat(content(corrupt).locallyVerified()).isFalse();
        var failed=List.of(new Authorization(20,1,10,"AUTHORIZE","FAILED",6000,0,"pfw_test","INITIAL_ORDER_AUTHORIZATION",null));
        assertThat(content(copy(pending,pending.current(),10L,pending.payments(),failed,e,pending.histories(),c.id(),false,true)).locallyVerified()).isFalse();
    }
}
