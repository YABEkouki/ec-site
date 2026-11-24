package com.example.ecsite.service.orderchange;

import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.pricing.*;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rows of a quantity increase accepted before Feature140-A closed the legacy entrance: the same Order lock, checks,
 * stock movements and HELD reservations as the removed production acceptance. Works only on a schema at V107 or
 * earlier ("spring.flyway.target=107"); V108 refuses these inserts.
 */
public final class LegacyOrderIncreaseFixture {
    private LegacyOrderIncreaseFixture() {}

    public static OrderChangeStore.Operation accept(ApplicationContext context,long orderId,long customerId,Map<Long,Integer> quantities){
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
            .execute(s->acceptLocked(context,orderId,customerId,quantities));
    }

    private static OrderChangeStore.Operation acceptLocked(ApplicationContext context,long orderId,long customerId,Map<Long,Integer> quantities){
        var orders=context.getBean(OrderRepository.class);var products=context.getBean(ProductRepository.class);
        var jdbc=context.getBean(JdbcTemplate.class);var store=context.getBean(OrderChangeStore.class);
        context.getBean(OrderWriteContext.class).requireCleanWritableReadCommitted();
        var order=orders.findByIdAndUserIdForUpdate(orderId,customerId).orElseThrow();
        context.getBean(OrderWriteContext.class).refreshWritableOrder(order);
        context.getBean(OrderPaymentGuard.class).requireNoOrderChange(order);
        var pricing=new OrderPricingContext();
        for(var item:order.getItems())pricing.addItem(item.getPrice(),quantities.getOrDefault(item.getId(),item.getQuantity()),
            item.getTaxCategoryId(),item.getTaxCategoryCode(),item.getTaxCategoryName(),item.getTaxRate());
        var amount=context.getBean(OrderAmountCalculator.class).calculate(pricing,ChargeTaxSnapshot.from(order.getCharges().getFirst()));
        long payment=context.getBean(ActivePaymentService.class).initializeForOrderChange(orderId);
        var changed=order.getItems().stream().filter(i->quantities.containsKey(i.getId()))
            .sorted(Comparator.comparing(OrderItem::getProductId).thenComparing(OrderItem::getId)).toList();
        for(long product:changed.stream().map(OrderItem::getProductId).distinct().toList())products.findByIdForUpdate(product).orElseThrow();
        var now=context.getBean(OrderModificationGuard.class).requireAllowed(order);
        var id=UUID.randomUUID();var shipping=amount.charges().getFirst();
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
                old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
                shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
                accepted_at,deadline_at,grace_until,state,created_at,updated_at)
            values(?,?,?,?,repeat('a',64),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?)
            """,id,orderId,customerId,UUID.randomUUID(),order.getContentRevision(),payment,order.getTotalAmount(),
            amount.totalAmount(),amount.itemSubtotal(),amount.chargeTotal(),amount.taxAmount(),shipping.name(),shipping.amount(),
            shipping.taxCategoryId(),shipping.taxCategoryCode(),shipping.taxCategoryName(),shipping.taxRate(),shipping.displayOrder(),
            now,order.getChangeDeadlineAt(),context.getBean(OrderModificationPolicy.class).changeGraceDeadline(order),now,now);
        for(var item:changed){
            int quantity=quantities.get(item.getId()),delta=quantity-item.getQuantity();
            jdbc.update("""
                insert into order_change_items(operation_id,order_id,order_item_id,product_id,before_quantity,after_quantity,
                  increase_quantity,unit_price,tax_category_id,tax_category_code,tax_category_name,tax_rate,before_subtotal,after_subtotal)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,orderId,item.getId(),item.getProductId(),item.getQuantity(),quantity,delta,item.getPrice(),item.getTaxCategoryId(),
                item.getTaxCategoryCode(),item.getTaxCategoryName(),item.getTaxRate(),item.getSubtotal(),Math.multiplyExact(item.getPrice(),quantity));
            var product=products.findByIdForUpdate(item.getProductId()).orElseThrow();
            int before=product.getStock();product.adjustStock(-delta);
            long movement=context.getBean(StockMovementRepository.class).saveAndFlush(
                StockMovement.createOrderChange(product,before,product.getStock(),-delta,orderId,id,item.getId())).getId();
            jdbc.update("insert into inventory_reservations(operation_id,order_item_id,product_id,quantity,state,hold_movement_id,held_at) values(?,?,?,?,'HELD',?,?)",
                id,item.getId(),item.getProductId(),delta,movement,now);
        }
        return store.operation(orderId,id).orElseThrow();
    }
}
