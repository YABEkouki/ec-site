package com.example.ecsite.support;

import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.OrderModificationGuard;
import com.example.ecsite.service.order.*;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.pricing.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order content decreased through /orders/{id}/items before Feature141-A closed that route. Quantities not given stay
 * as they are; zero removes the item.
 */
public final class LegacyItemDecreaseFixture {
    private LegacyItemDecreaseFixture() {}

    /** The same rows as the removed production route. Needs V108 or earlier: V109 refuses its movements and history. */
    public static void decrease(ApplicationContext context, long orderId, long customerId, String username,
            Map<Long, Integer> quantities) {
        run(context, orderId, customerId, username, quantities, true);
    }

    /** Items, charges, amounts and revision only (no stock, movements or history); works on the latest schema. */
    public static void decreaseContentOnly(ApplicationContext context, long orderId, long customerId,
            Map<Long, Integer> quantities) {
        run(context, orderId, customerId, null, quantities, false);
    }

    private static void run(ApplicationContext context, long orderId, long customerId, String username,
            Map<Long, Integer> quantities, boolean recorded) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
                locked(context, orderId, customerId, username, quantities, recorded));
    }

    private static void locked(ApplicationContext context, long orderId, long customerId, String username,
            Map<Long, Integer> quantities, boolean recorded) {
        var orders = context.getBean(OrderRepository.class);
        Order order = orders.findByIdAndUserIdForUpdate(orderId, customerId).orElseThrow();
        context.getBean(OrderPaymentGuard.class).requireNoOrderChange(order);
        LocalDateTime now = context.getBean(OrderModificationGuard.class).requireAllowed(order);
        var beforeItems = order.getItems().stream().map(OrderItemSnapshot::from).toList();
        var beforeAmount = OrderAmountSnapshot.from(order);
        OrderCharge shipping = order.getCharges().getFirst();
        var beforeShipping = OrderChargeSnapshot.from(shipping);

        for (OrderItem item : new ArrayList<>(order.getItems())) {
            int quantity = quantities.getOrDefault(item.getId(), item.getQuantity());
            if (quantity > item.getQuantity()) throw new IllegalArgumentException("decrease only");
            if (quantity == 0) order.removeItem(item);
            else if (quantity != item.getQuantity()) item.setQuantity(quantity);
        }
        var pricing = new OrderPricingContext();
        for (OrderItem item : order.getItems()) pricing.addItem(item.getPrice(), item.getQuantity(), item.getTaxCategoryId(),
                item.getTaxCategoryCode(), item.getTaxCategoryName(), item.getTaxRate());
        var amount = context.getBean(OrderAmountCalculator.class).calculate(pricing, ChargeTaxSnapshot.from(shipping));
        order.applyAmount(amount);
        shipping.updateAmount(amount.charges().getFirst().amount());
        if (recorded) record(context, order, customerId, username, now, beforeItems, beforeAmount, beforeShipping);
        order.incrementContentRevision();
        orders.flush();
    }

    private static void record(ApplicationContext context, Order order, long customerId, String username, LocalDateTime now,
            List<OrderItemSnapshot> beforeItems, OrderAmountSnapshot before, OrderChargeSnapshot beforeShipping) {
        var jdbc = context.getBean(JdbcTemplate.class);
        var products = context.getBean(ProductRepository.class);
        var after = new HashMap<Long, OrderItem>();
        order.getItems().forEach(item -> after.put(item.getId(), item));
        var history = new OrderContentChangeHistory(order, OrderContentChangeSource.CUSTOMER, null,
                OrderContentChangeHistoryActorType.USER, customerId, username, before.itemSubtotal(), order.getItemSubtotal(),
                before.chargeTotal(), order.getChargeTotal(), before.taxAmount(), order.getTaxAmount(),
                before.totalAmount(), order.getTotalAmount(), now);
        for (var item : beforeItems) {
            var current = after.get(item.orderItemId());
            if (current == null) history.addItem(OrderContentChangeItem.removed(item.orderItemId(), item.productId(),
                    item.productName(), item.price(), item.quantity(), item.subtotal(), item.taxCategoryId(),
                    item.taxCategoryCode(), item.taxCategoryName(), item.taxRate()));
            else if (current.getQuantity() != item.quantity()) history.addItem(OrderContentChangeItem.updated(
                    item.orderItemId(), item.productId(), item.productName(), item.price(), item.quantity(),
                    current.getQuantity(), item.subtotal(), current.getSubtotal(), item.taxCategoryId(),
                    item.taxCategoryCode(), item.taxCategoryName(), item.taxRate()));
        }
        OrderCharge shipping = order.getCharges().getFirst();
        if (beforeShipping.amount() != shipping.getAmount()) history.addCharge(OrderContentChangeCharge.updated(shipping.getId(),
                shipping.getChargeType(), beforeShipping.name(), shipping.getName(), beforeShipping.amount(), shipping.getAmount(),
                beforeShipping.taxCategoryId(), shipping.getTaxCategoryId(), beforeShipping.taxCategoryCode(),
                shipping.getTaxCategoryCode(), beforeShipping.taxCategoryName(), shipping.getTaxCategoryName(),
                beforeShipping.taxRate(), shipping.getTaxRate(), beforeShipping.displayOrder(), shipping.getDisplayOrder()));
        context.getBean(OrderContentChangeHistoryRepository.class).saveAndFlush(history);
        var sorted = beforeItems.stream().sorted(Comparator.comparing(OrderItemSnapshot::productId)
                .thenComparing(OrderItemSnapshot::orderItemId)).toList();
        for (var item : sorted) {
            var current = after.get(item.orderItemId());
            int restored = item.quantity() - (current == null ? 0 : current.getQuantity());
            if (restored == 0) continue;
            var product = products.findByIdForUpdateIncludingInactive(item.productId()).orElseThrow();
            int stock = product.getStock();
            product.adjustStock(restored);
            products.flush();
            jdbc.update("""
                    insert into stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,
                        changed_by_type,changed_by_username,order_id,changed_at)
                    values(?,?,'ORDER_ITEM_CHANGE',?,?,?,'SYSTEM','SYSTEM',?,?)
                    """, product.getId(), product.getName(), restored, stock, product.getStock(), order.getId(), now);
        }
    }
}
