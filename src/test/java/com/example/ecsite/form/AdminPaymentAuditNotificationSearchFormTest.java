package com.example.ecsite.form;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.Map;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.validation.DataBinder;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;

class AdminPaymentAuditNotificationSearchFormTest {
    static ValidatorFactory factory;
    @BeforeAll static void validator() { factory=Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }
    @ParameterizedTest @CsvSource({"from,2026-02-30","to,not-a-date","status,BOGUS","warningType,BOGUS","notificationId,abc","notificationId,9223372036854775808"})
    void malformedInputBecomesBindingError(String field,String value) {
        var binder=new DataBinder(new AdminPaymentAuditNotificationSearchForm());
        binder.setConversionService(new DefaultFormattingConversionService());
        binder.bind(new MutablePropertyValues(Map.of(field,value)));
        assertThat(binder.getBindingResult().hasFieldErrors(field)).isTrue();
    }
    @ParameterizedTest @ValueSource(longs={0,-1,Long.MIN_VALUE})
    void nonpositiveIdHasFieldValidationError(long id) {
        var form=new AdminPaymentAuditNotificationSearchForm();form.setNotificationId(id);
        assertThat(factory.getValidator().validate(form)).anyMatch(v->v.getPropertyPath().toString().equals("notificationId"));
    }
    @Test void typedBindingSupportsValidIdEnumsAndIsoDates() {
        var form=new AdminPaymentAuditNotificationSearchForm();var binder=new DataBinder(form);
        binder.setConversionService(new DefaultFormattingConversionService());
        binder.bind(new MutablePropertyValues(Map.of("notificationId","12","status","SENT","warningType","LATEST_FAILURE","from","2026-10-09","to","2026-10-09")));
        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(form.getNotificationId()).isEqualTo(12);assertThat(form.getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENT);
        assertThat(form.getWarningType()).isEqualTo(PaymentAuditNotificationWarningType.LATEST_FAILURE);
        assertThat(form.getFrom()).isEqualTo(LocalDate.of(2026,10,9));assertThat(factory.getValidator().validate(form)).isEmpty();
    }
    @Test void reverseAndExtremeDatesHaveValidationErrorsWhileOptionalBoundsAreValid() {
        var form=new AdminPaymentAuditNotificationSearchForm();
        assertThat(factory.getValidator().validate(form)).isEmpty();
        form.setFrom(LocalDate.of(2026,10,10));assertThat(factory.getValidator().validate(form)).isEmpty();
        form.setTo(LocalDate.of(2026,10,9));
        assertThat(factory.getValidator().validate(form)).anyMatch(v->v.getPropertyPath().toString().equals("dateRangeValid"));
        form.setFrom(null);form.setTo(LocalDate.MAX);
        assertThat(factory.getValidator().validate(form)).anyMatch(v->v.getPropertyPath().toString().equals("dateBoundsValid"));
    }
}
