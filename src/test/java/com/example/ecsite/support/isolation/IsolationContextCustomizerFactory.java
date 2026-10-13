package com.example.ecsite.support.isolation;

import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.mockito.Mockito;
import com.example.ecsite.payment.payjp.PayJpPaymentGateway;
import org.springframework.mail.javamail.JavaMailSender;

/** Programmatic BFPP executes before DataSource creation and Flyway migration. */
public class IsolationContextCustomizerFactory implements ContextCustomizerFactory {
    @Override public ContextCustomizer createContextCustomizer(Class<?> testClass,List<ContextConfigurationAttributes> attributes) {
        boolean database=AnnotatedElementUtils.hasAnnotation(testClass,SpringBootTest.class)
            || AnnotatedElementUtils.hasAnnotation(testClass,DataJpaTest.class);
        return database ? new GuardCustomizer() : null;
    }
    private record GuardCustomizer() implements ContextCustomizer {
        @Override public void customizeContext(org.springframework.context.ConfigurableApplicationContext context,MergedContextConfiguration merged) {
            context.addBeanFactoryPostProcessor(factory->IsolatedDatabaseGuard.verify(context.getEnvironment()));
            context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    // Explicit Mockito mocks/stubs keep their configured behavior.
                    if(Mockito.mockingDetails(bean).isMock()) return bean;
                    if(bean instanceof PayJpPaymentGateway || bean instanceof JavaMailSender) {
                        Class<?> type=bean instanceof PayJpPaymentGateway ? PayJpPaymentGateway.class : JavaMailSender.class;
                        return Mockito.mock(type, invocation->{
                            if(invocation.getMethod().getDeclaringClass()==Object.class) return Mockito.RETURNS_DEFAULTS.answer(invocation);
                            throw new IllegalStateException("Real payment/SMTP operations are forbidden in database regression tests");
                        });
                    }
                    return bean;
                }
            });
        }
    }
}
