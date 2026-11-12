package com.example.ecsite.service.orderchange;

import org.springframework.context.ApplicationContext;
import com.example.ecsite.payment.PaymentGateway;
import static org.assertj.core.api.Assertions.*;

/** Uses the existing old-recovery writer fixture without changing production or existing test files. */
public final class LegacyRecoveredOrderChangeHistoryFixture {
    private LegacyRecoveredOrderChangeHistoryFixture() {}
    private static OrderReauthorizationPostgresTest wired(ApplicationContext context,PaymentGateway gateway){
        var test=new OrderReauthorizationPostgresTest();
        test.manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        test.jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        test.users=context.getBean(com.example.ecsite.repository.UserRepository.class);
        test.categories=context.getBean(com.example.ecsite.repository.CategoryRepository.class);
        test.taxes=context.getBean(com.example.ecsite.repository.TaxCategoryRepository.class);
        test.products=context.getBean(com.example.ecsite.repository.ProductRepository.class);
        test.payments=context.getBean(com.example.ecsite.repository.PaymentRepository.class);
        test.transactions=context.getBean(com.example.ecsite.repository.PaymentTransactionRepository.class);
        test.histories=context.getBean(com.example.ecsite.service.OrderStatusHistoryService.class);
        test.service=context.getBean(OrderReauthorizationService.class);test.increases=context.getBean(OrderIncreaseService.class);
        test.orders=context.getBean(com.example.ecsite.repository.OrderRepository.class);test.gateway=gateway;
        test.recovery=context.getBean(OldCancelRecoveryService.class);
        return test;
    }
    /** The older shared fixture sets total only; initialize its headers before creating historical evidence. */
    private static void initializeSavedAmounts(ApplicationContext context,OrderReauthorizationPostgresTest test,long orderId){
        test.tx(()->{
            var order=test.orders.findById(orderId).orElseThrow();var pricing=new com.example.ecsite.service.pricing.OrderPricingContext();int subtotal=0;
            for(var item:order.getItems()){pricing.addItem(item.getPrice(),item.getQuantity(),item.getTaxCategoryId(),item.getTaxCategoryCode(),item.getTaxCategoryName(),item.getTaxRate());subtotal=Math.addExact(subtotal,item.getSubtotal());}
            var charges=order.getCharges().stream().map(c->new com.example.ecsite.service.pricing.OrderChargeAmount(c.getChargeType(),c.getName(),c.getAmount(),c.getTaxCategoryId(),c.getTaxCategoryCode(),c.getTaxCategoryName(),c.getTaxRate(),c.getDisplayOrder())).toList();
            int chargeTotal=charges.stream().mapToInt(com.example.ecsite.service.pricing.OrderChargeAmount::amount).reduce(0,Math::addExact);
            int tax=context.getBean(com.example.ecsite.service.pricing.TaxCalculator.class).calculate(pricing,charges);
            assertThat(order.getTotalAmount()).isEqualTo(Math.addExact(subtotal,chargeTotal));
            order.applyAmount(new com.example.ecsite.service.pricing.OrderAmount(subtotal,charges,chargeTotal,tax,order.getTotalAmount()));test.orders.saveAndFlush(order);return null;
        });
    }
    public static LegacyOrderChangeEvidenceFixture.Completed completeNormally(ApplicationContext context,PaymentGateway gateway){
        var test=wired(context,gateway);var f=test.fixture();initializeSavedAmounts(context,test,f.order());var op=test.accept(f,3);test.mockFlow(f,op);assertThat(test.service.advance(f.order(),op.id()).state()).isEqualTo("COMPLETED");return new LegacyOrderChangeEvidenceFixture.Completed(f.order(),f.user(),op.id());
    }
    public static LegacyOrderChangeEvidenceFixture.Completed complete(ApplicationContext context,PaymentGateway gateway){
        var test=wired(context,gateway);var base=test.fixture();initializeSavedAmounts(context,test,base.order());
        var prior=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        try{
            var f=test.recoveryFixture(base,3);assertThat(test.recovery.recover(test.recoveryCommand(f))).isEqualTo(OldCancelRecovery.Result.COMPLETED);
            return new LegacyOrderChangeEvidenceFixture.Completed(f.base().order(),f.base().user(),f.op().id());
        }finally{org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(prior);}
    }
}
