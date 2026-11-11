package com.example.ecsite.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.service.order.evidence.*;
import com.example.ecsite.service.orderchange.OrderReauthorizationService;

/** Construction only. Runtime remains STOPPED; no lifecycle callback starts business work. */
@Configuration(proxyBeanMethods=false)
public class OrderEditBackendConfiguration {
    @Bean public OrderEditCandidateCalculator orderEditCandidateCalculator(){return new OrderEditCandidateCalculator();}
    @Bean @Lazy public OrderEditAdmissionService orderEditAdmissionService(DataSource source){return new OrderEditAdmissionService(source);}
    @Bean @Lazy public OrderEditFailureClosureService orderEditFailureClosureService(DataSource source,PaymentGateway gateway){
        return new OrderEditFailureClosureService(source,gateway);
    }
    @Bean @Lazy public LegacyOrderEvidenceEnrollment legacyOrderEvidenceEnrollment(DataSource source,PaymentGateway gateway,
            CaptureConnectionProperties capture,PayJpProperties provider){
        return new LegacyOrderEvidenceEnrollment(source,gateway,capture.requireIdentity(provider));
    }
    @Bean @Lazy public OrderEditExecutionService orderEditExecutionService(DataSource source,PaymentGateway gateway,
            CaptureConnectionProperties capture,PayJpProperties provider,OrderReauthorizationService reauthorization){
        // Coordinator/ApplicationTransactions keep their established independent transactions internally.
        return new OrderEditExecutionService(source,gateway,capture.requireIdentity(provider),reauthorization);
    }
}
