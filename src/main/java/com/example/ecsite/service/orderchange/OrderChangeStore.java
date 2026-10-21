package com.example.ecsite.service.orderchange;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.service.pricing.OrderAmount;

/** Small JDBC store; all calls join the caller's Order-locked transaction. No financial API. */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class OrderChangeStore {
    private final JdbcTemplate jdbc;
    public OrderChangeStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Set<UUID> completedOperationIds(long orderId){return settledIds(orderId,"COMPLETED");}
    public Set<UUID> abortedOperationIds(long orderId){return settledIds(orderId,"ABORTED");}
    private Set<UUID> settledIds(long orderId,String state){return Set.copyOf(jdbc.queryForList(
        "select id from order_change_operations where order_id=? and state=?",UUID.class,orderId,state));}
    public record Operation(UUID id,long orderId,long customerId,String requestDigest,String state,
            int beforeAmount,int afterAmount,LocalDateTime graceUntil,LocalDateTime switchDecidedAt,long version){}
    public record Reservation(long itemId,long productId,int quantity,String state){}
    public Optional<Operation> submission(long orderId,UUID key){return find("order_id=? and submission_key=?",orderId,key);}
    public Optional<Operation> operation(long orderId,UUID id){return find("order_id=? and id=?",orderId,id);}
    private Optional<Operation> find(String predicate,Object... args){
        var rows=jdbc.query("select * from order_change_operations where "+predicate+" for update",(r,n)->new Operation(
                r.getObject("id",UUID.class),r.getLong("order_id"),r.getLong("customer_id"),r.getString("request_digest"),
                r.getString("state"),r.getInt("before_amount"),r.getInt("after_amount"),r.getObject("grace_until",LocalDateTime.class),
                r.getObject("switch_decided_at",LocalDateTime.class),r.getLong("version")),args);
        if(rows.size()>1)throw new IllegalStateException("Ambiguous order change");
        return rows.stream().findFirst();
    }
    /** Parent Order lock serializes existence checks with candidate creation and all competing starts. */
    public boolean unresolved(long orderId){return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from order_change_operations where order_id=? and state not in ('COMPLETED','ABORTED'))",Boolean.class,orderId));}
    public void insert(UUID id,OrderIncreaseService.Request request,String digest,long payment,int before,OrderAmount amount,
            LocalDateTime now,LocalDateTime deadline,LocalDateTime grace){
        var shipping=amount.charges().getFirst();
        jdbc.update("""
            insert into order_change_operations(id,order_id,customer_id,submission_key,request_digest,base_content_revision,
                old_payment_id,before_amount,after_amount,item_subtotal,charge_total,tax_amount,shipping_name,shipping_amount,
                shipping_tax_category_id,shipping_tax_category_code,shipping_tax_category_name,shipping_tax_rate,shipping_display_order,
                accepted_at,deadline_at,grace_until,state,created_at,updated_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?)
            """,id,request.orderId(),request.customerId(),request.submissionKey(),digest,request.baseRevision(),payment,before,
            amount.totalAmount(),amount.itemSubtotal(),amount.chargeTotal(),amount.taxAmount(),shipping.name(),shipping.amount(),
            shipping.taxCategoryId(),shipping.taxCategoryCode(),shipping.taxCategoryName(),shipping.taxRate(),shipping.displayOrder(),
            now,deadline,grace,now,now);
    }
    public void item(UUID id,long orderId,OrderItem item,int quantity){
        jdbc.update("""
            insert into order_change_items(operation_id,order_id,order_item_id,product_id,before_quantity,after_quantity,
              increase_quantity,unit_price,tax_category_id,tax_category_code,tax_category_name,tax_rate,before_subtotal,after_subtotal)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,orderId,item.getId(),item.getProductId(),item.getQuantity(),quantity,quantity-item.getQuantity(),item.getPrice(),
            item.getTaxCategoryId(),item.getTaxCategoryCode(),item.getTaxCategoryName(),item.getTaxRate(),item.getSubtotal(),Math.multiplyExact(item.getPrice(),quantity));
    }
    public void held(UUID id,long item,long product,int quantity,long movement,LocalDateTime now){
        jdbc.update("insert into inventory_reservations(operation_id,order_item_id,product_id,quantity,state,hold_movement_id,held_at) values(?,?,?,?,'HELD',?,?)",id,item,product,quantity,movement,now);
    }
    public List<Reservation> reservations(UUID id){return jdbc.query("select * from inventory_reservations where operation_id=? order by product_id,order_item_id for update",
            (r,n)->new Reservation(r.getLong("order_item_id"),r.getLong("product_id"),r.getInt("quantity"),r.getString("state")),id);}
    public void released(UUID id,long item,long movement,LocalDateTime now){
        if(jdbc.update("update inventory_reservations set state='RELEASED',release_movement_id=?,released_at=? where operation_id=? and order_item_id=? and state='HELD'",movement,now,id,item)!=1)
            throw new IllegalStateException("Reservation release conflict");
    }
    public void aborted(Operation op,LocalDateTime now){
        if(jdbc.update("update order_change_operations set state='ABORTED',version=version+1,updated_at=? where id=? and version=? and state='RESERVED' and switch_decided_at is null",now,op.id(),op.version())!=1)
            throw new IllegalStateException("Order change state conflict");
    }
}
