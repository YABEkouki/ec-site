package com.example.ecsite.support.restart;

import java.time.Duration;
import java.util.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import com.example.ecsite.payment.payjp.PayJpPaymentGateway;
import org.springframework.mail.javamail.JavaMailSender;
import com.example.ecsite.service.payment.*;

/** Installs mocks BEFORE real outbound constructors and prevents automatic scheduler startup. */
public final class ManualResendRestartTestConfiguration {
    public static void install(ConfigurableApplicationContext context,RestartProbe probe,boolean on,String mode){
        context.addBeanFactoryPostProcessor(factory->{
            var registry=(BeanDefinitionRegistry)factory;
            replace(factory,registry,JavaMailSender.class,Mockito.mock(JavaMailSender.class,invocation->{if(invocation.getMethod().getDeclaringClass()==Object.class)return Mockito.RETURNS_DEFAULTS.answer(invocation);throw new IllegalStateException("SMTP forbidden");}));
            replace(factory,registry,PayJpPaymentGateway.class,Mockito.mock(PayJpPaymentGateway.class,invocation->{if(invocation.getMethod().getDeclaringClass()==Object.class)return Mockito.RETURNS_DEFAULTS.answer(invocation);throw new IllegalStateException("PAY.JP forbidden");}));
            String[] mailNames=factory.getBeanNamesForType(PaymentDiscrepancyAuditNotificationMailClient.class,false,false);
            if(mailNames.length!=(on?1:0))throw new IllegalStateException("Mail client definitions not verified");
            if(on){
                var fake=Mockito.mock(PaymentDiscrepancyAuditNotificationMailClient.class);
                Mockito.doAnswer(i->{probe.event("SMTP_ENTERED");if(mode.equals("SENDING"))probe.awaitPermission("RELEASE_SMTP",Duration.ofMinutes(2));return null;}).when(fake).send(Mockito.any());
                replace(factory,registry,PaymentDiscrepancyAuditNotificationMailClient.class,fake);
            }
            for(Class<?> type:List.of(PaymentAuditNotificationScheduler.class,PaymentDiscrepancyAuditScheduler.class,PaymentReconciliationScheduler.class))
                for(String name:factory.getBeanNamesForType(type,false,false))registry.removeBeanDefinition(name);
            String[] names=factory.getBeanNamesForType(PaymentAuditNotificationManualResendScheduler.class,false,false);
            if(names.length!=1)throw new IllegalStateException("Manual scheduler definition not verified");
            registry.removeBeanDefinition(names[0]);
            registry.registerBeanDefinition(names[0],new RootBeanDefinition(ControlledScheduler.class,()->new ControlledScheduler(
                factory.getBean(PaymentAuditNotificationManualResendDeliveryService.class),
                factory.getBean(com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties.class),
                factory.getBeanProvider(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class),probe)));
        });
    }
    private static <T> void replace(ConfigurableListableBeanFactory factory,BeanDefinitionRegistry registry,Class<T> type,T fake){
        String[] names=factory.getBeanNamesForType(type,false,false);
        if(names.length!=1 || factory.containsSingleton(names[0]))throw new IllegalStateException("Outbound definition not verified");
        registry.removeBeanDefinition(names[0]);registry.registerBeanDefinition(names[0],new RootBeanDefinition(type,()->fake));
    }
    public static final class ControlledScheduler extends PaymentAuditNotificationManualResendScheduler {
        private final PaymentAuditNotificationManualResendDeliveryService delivery;
        private final RestartProbe probe;
        private final boolean on;
        public ControlledScheduler(PaymentAuditNotificationManualResendDeliveryService delivery,com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties properties,
            org.springframework.beans.factory.ObjectProvider<org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor> scheduling,RestartProbe probe){
            super(delivery,properties,scheduling);this.delivery=delivery;this.probe=probe;this.on=properties.enabled();
        }
        @Override public void start(){/* Parent authorizes lifecycle only after safety checks. */}
        public void authorizedStart(){super.start();}
        @Override public synchronized void stop(){
            try{
                try{probe.event("SCHEDULER_STOP_START");}finally{super.stop();}
                probe.event("SCHEDULER_STOP_COMPLETE");
            }
            catch(RuntimeException | Error failure){probe.exception(RestartProbe.Stage.SCHEDULER_STOP,failure);throw failure;}
        }
        @Override public void tick(){
            try {delivery.recoverExpired();if(on && probe.allowed("DELIVER"))delivery.deliverOne();probe.event("TICK");}
            catch(RuntimeException e){probe.event("FAILED");}
        }
    }
}
