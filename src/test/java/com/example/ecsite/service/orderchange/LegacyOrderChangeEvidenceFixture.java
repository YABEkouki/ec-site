package com.example.ecsite.service.orderchange;

import java.util.UUID;
import org.springframework.context.ApplicationContext;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.OrderRepository;
import static org.assertj.core.api.Assertions.assertThat;

/** Reuses the existing legacy reauthorization fixture, including its original order event. */
public final class LegacyOrderChangeEvidenceFixture {
    private LegacyOrderChangeEvidenceFixture() {}
    public record Completed(long orderId,long customerId,UUID operationId) {}
    public static Completed complete(ApplicationContext context,PaymentGateway gateway){
        var test=new OrderReauthorizationPostgresTest();
        // Wire only the dependencies used by fixture()/accept()/mockFlow(); no MVC test context is needed.
        test.manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        test.jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        test.users=context.getBean(com.example.ecsite.repository.UserRepository.class);
        test.categories=context.getBean(com.example.ecsite.repository.CategoryRepository.class);
        test.taxes=context.getBean(com.example.ecsite.repository.TaxCategoryRepository.class);
        test.products=context.getBean(com.example.ecsite.repository.ProductRepository.class);
        test.payments=context.getBean(com.example.ecsite.repository.PaymentRepository.class);
        test.transactions=context.getBean(com.example.ecsite.repository.PaymentTransactionRepository.class);
        test.histories=context.getBean(com.example.ecsite.service.OrderStatusHistoryService.class);
        test.service=context.getBean(OrderReauthorizationService.class);
        test.increases=context.getBean(OrderIncreaseService.class);
        test.orders=context.getBean(OrderRepository.class);test.gateway=gateway;
        var order=test.fixture();var operation=test.accept(order,3);test.mockFlow(order,operation);
        assertThat(test.service.advance(order.order(),operation.id()).state()).isEqualTo("COMPLETED");
        return new Completed(order.order(),order.user(),operation.id());
    }
}
