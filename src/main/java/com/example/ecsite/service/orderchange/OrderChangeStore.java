package com.example.ecsite.service.orderchange;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
            int beforeAmount,int afterAmount,LocalDateTime graceUntil,LocalDateTime switchDecidedAt,long version,boolean deadlineClosed,String finalOutcome){}
    public record Reservation(long itemId,long productId,int quantity,String state){}
    public record ExpiringOperation(long orderId,UUID operationId){}
    public boolean abortRequested(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select abort_request_id is not null from order_change_operations where id=?",Boolean.class,id));}
    public boolean financialStarted(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from order_change_operations where id=? and new_payment_id is not null)
          or exists(select 1 from order_change_financial_requests where operation_id=?)
          or exists(select 1 from payment_transactions where order_change_operation_id=?)
        """,Boolean.class,id,id,id));}
    public List<ExpiringOperation> expiredReserved(int limit){
        if(limit<1 || limit>1000)throw new IllegalArgumentException("Invalid expiry batch size");
        return jdbc.query("""
            select o.order_id,o.id from order_change_operations o
            where o.state='RESERVED' and o.abort_request_id is null and o.switch_decided_at is null and o.new_payment_id is null
              and o.grace_until<=timezone('Asia/Tokyo',clock_timestamp())
              and not exists(select 1 from order_change_financial_requests r where r.operation_id=o.id)
              and not exists(select 1 from payment_transactions t where t.order_change_operation_id=o.id)
            order by o.grace_until,o.order_id limit ?
            """,(r,n)->new ExpiringOperation(r.getLong(1),r.getObject(2,UUID.class)),limit);
    }
    public Optional<Operation> operation(long orderId,UUID id){return find("order_id=? and id=?",orderId,id);}
    private Optional<Operation> find(String predicate,Object... args){
        var rows=jdbc.query("select *,exists(select 1 from order_change_deadline_closures c where c.operation_id=order_change_operations.id) as f128_deadline_closed,"
                +"(select d.outcome from order_change_final_decisions d where d.operation_id=order_change_operations.id) as f128_final_outcome"
                +" from order_change_operations where "+predicate+" for update of order_change_operations",(r,n)->new Operation(
                r.getObject("id",UUID.class),r.getLong("order_id"),r.getLong("customer_id"),r.getString("request_digest"),
                r.getString("state"),r.getInt("before_amount"),r.getInt("after_amount"),r.getObject("grace_until",LocalDateTime.class),
                r.getObject("switch_decided_at",LocalDateTime.class),r.getLong("version"),r.getBoolean("f128_deadline_closed"),r.getString("f128_final_outcome")),args);
        if(rows.size()>1)throw new IllegalStateException("Ambiguous order change");
        return rows.stream().findFirst();
    }
    /** Parent Order lock serializes existence checks with candidate creation and all competing starts. */
    public boolean unresolved(long orderId){return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from order_change_operations where order_id=? and state not in ('COMPLETED','ABORTED'))",Boolean.class,orderId));}
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
