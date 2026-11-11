package com.example.ecsite.config;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.service.orderchange.OrderReauthorizationService;
import com.example.ecsite.service.order.evidence.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OrderEditBackendConfigurationTest {
    @Test void startupAndExplicitBeanConstructionNeverAccessDatabaseOrProvider(){
        var source=mock(DataSource.class);var gateway=mock(PaymentGateway.class);var capture=mock(CaptureConnectionProperties.class);var payjp=mock(PayJpProperties.class);var finance=mock(OrderReauthorizationService.class);
        when(capture.requireIdentity(payjp)).thenReturn(new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp"));
        try(var context=new AnnotationConfigApplicationContext()){
            context.registerBean(DataSource.class,()->source);context.registerBean(PaymentGateway.class,()->gateway);
            context.registerBean(CaptureConnectionProperties.class,()->capture);context.registerBean(PayJpProperties.class,()->payjp);
            context.registerBean(OrderReauthorizationService.class,()->finance);context.register(OrderEditBackendConfiguration.class);context.refresh();
            verifyNoInteractions(source,gateway,capture,finance);
            assertThat(context.getBean(OrderEditCandidateCalculator.class)).isNotNull();
            assertThat(context.getBean(OrderEditAdmissionService.class)).isNotNull();
            assertThat(context.getBean(OrderEditExecutionService.class)).isNotNull();
            assertThat(context.getBean(LegacyOrderEvidenceEnrollment.class)).isNotNull();
            verifyNoInteractions(source,gateway,finance);
        }
    }
}
