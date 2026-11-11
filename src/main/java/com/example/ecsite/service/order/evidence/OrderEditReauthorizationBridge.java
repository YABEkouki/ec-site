package com.example.ecsite.service.order.evidence;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.ecsite.entity.OrderChargeType;
import com.example.ecsite.service.pricing.*;
import static com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.Ownership;

/** Internal same-transaction adapter for Feature125's full candidate and aggregated holds. */
public final class OrderEditReauthorizationBridge {
    private final JdbcTemplate jdbc;
    public OrderEditReauthorizationBridge(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public boolean isOwned(long order,UUID operation){
        // Pre-V75 legacy databases cannot contain the atomically introduced owned route.
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select to_regprocedure('f125_reauth_is_owned(bigint,uuid)') is not null",Boolean.class)))return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("select f125_reauth_is_owned(?,?)",Boolean.class,order,operation));
    }
    public void canonicalLock(long order,UUID operation){jdbc.queryForObject("select f125_reauth_lock(?,?)",Object.class,order,operation);}
    public void requireOwner(long order,UUID operation,Ownership owner){
        Objects.requireNonNull(owner);
        jdbc.queryForObject("select f125_reauth_require_owner(?,?,?,?,?)",Object.class,owner.admissionId(),owner.attemptId(),owner.token(),owner.controlVersion(),operation);
        if(!owner.admissionId().equals(admissionId(order,operation)))throw new IllegalStateException("Reauthorization owner mismatch");
    }
    public UUID admissionId(long order,UUID operation){
        if(!isOwned(order,operation))throw new IllegalStateException("Not an order edit reauthorization");
        return jdbc.queryForObject("select a.id from order_edit_admissions a join order_change_operations o on o.order_id=a.order_id and o.submission_key=a.submission_key where o.order_id=? and o.id=?",UUID.class,order,operation);
    }
    public void validateBase(long order,UUID operation){jdbc.queryForObject("select f125_reauth_assert_current(?)",Object.class,admissionId(order,operation));}
    public boolean expired(long order,UUID operation){return Boolean.TRUE.equals(jdbc.queryForObject("select automatic_until<=clock_timestamp() from order_edit_execution_controls where admission_id=?",Boolean.class,admissionId(order,operation)));}
    public OrderAmount candidateAmount(long order,UUID operation){
        var preview=new OrderEditApplicationPlanRepository(jdbc).read(admissionId(order,operation)).candidate().preview();
        var charges=preview.charges().stream().map(c->new OrderChargeAmount(OrderChargeType.valueOf(c.type()),c.name(),c.amount(),c.taxCategoryId(),c.taxCategoryCode(),c.taxCategoryName(),BigDecimal.valueOf(c.taxRateBasisPoints(),2),c.displayOrder())).toList();
        return new OrderAmount(preview.itemSubtotal(),charges,preview.chargeTotal(),preview.taxAmount(),preview.totalAmount());
    }
    public void hold(long order,UUID operation){
        canonicalLock(order,operation);var admission=admissionId(order,operation);
        for(var row:jdbc.queryForList("select * from order_edit_inventory_plans where admission_id=? and reserve_quantity>0 order by product_id for update",admission)){
            if("HELD".equals(row.get("reservation_state"))&&operation.equals(row.get("operation_id")))continue;
            if(row.get("reservation_state")!=null)throw new IllegalStateException("Hold already resolved");
            long product=((Number)row.get("product_id")).longValue();int quantity=((Number)row.get("reserve_quantity")).intValue();
            long movement=movement(order,operation,product,-quantity);
            if(jdbc.update("update order_edit_inventory_plans set operation_id=?,reservation_state='HELD',hold_movement_id=?,held_at=timezone('Asia/Tokyo',clock_timestamp()) where admission_id=? and product_id=? and reservation_state is null",operation,movement,admission,product)!=1)throw new IllegalStateException("Hold conflict");
        }
        requireHeld(order,operation);
    }
    public void requireHeld(long order,UUID operation){
        var plan=new OrderEditApplicationPlanRepository(jdbc).read(admissionId(order,operation));
        var expected=plan.candidate().stockDeltas().stream().filter(d->d.additionalRequired()>0).toList();
        var rows=jdbc.queryForList("select * from order_edit_inventory_plans where admission_id=? and reserve_quantity>0 order by product_id",plan.admissionId());
        if(rows.size()!=expected.size())throw new IllegalStateException("Incomplete aggregated holds");
        for(var row:rows)if(!"HELD".equals(row.get("reservation_state"))||!operation.equals(row.get("operation_id"))||row.get("hold_movement_id")==null
            ||expected.stream().noneMatch(d->d.productId()==((Number)row.get("product_id")).longValue()&&d.additionalRequired()==((Number)row.get("reserve_quantity")).intValue()))throw new IllegalStateException("Aggregated hold mismatch");
        int proven=jdbc.queryForObject("""
            select count(*) from order_edit_inventory_plans p join stock_movements m on m.id=p.hold_movement_id
            where p.admission_id=? and p.reserve_quantity>0 and p.operation_id=? and p.reservation_state='HELD'
              and m.product_id=p.product_id and m.order_id=p.order_id and m.movement_type='ORDER_CHANGE_RESERVATION'
              and m.quantity=-p.reserve_quantity and m.stock_after=m.stock_before-p.reserve_quantity
              and m.reason=?
            """,Integer.class,plan.admissionId(),operation,"order_change="+operation);
        if(proven!=expected.size())throw new IllegalStateException("Hold movement proof mismatch");
    }
    public void release(long order,UUID operation){
        canonicalLock(order,operation);requireHeld(order,operation);var admission=admissionId(order,operation);
        for(var row:jdbc.queryForList("select * from order_edit_inventory_plans where admission_id=? and reserve_quantity>0 order by product_id for update",admission)){
            long product=((Number)row.get("product_id")).longValue();long movement=movement(order,operation,product,((Number)row.get("reserve_quantity")).intValue());
            if(jdbc.update("update order_edit_inventory_plans set reservation_state='RELEASED',release_movement_id=?,released_at=timezone('Asia/Tokyo',clock_timestamp()) where admission_id=? and product_id=? and reservation_state='HELD' and operation_id=?",movement,admission,product,operation)!=1)throw new IllegalStateException("Release conflict");
        }
    }
    private long movement(long order,UUID operation,long product,int delta){
        int before=jdbc.queryForObject("select stock from products where id=? for update",Integer.class,product);int after=Math.addExact(before,delta);
        if(after<0)throw new IllegalStateException("Insufficient stock");
        jdbc.update("update products set stock=? where id=?",after,product);
        return jdbc.queryForObject("insert into stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,changed_by_type,changed_by_username,order_id,reason) select id,name,?,?,?,?, 'SYSTEM','SYSTEM',?,? from products where id=? returning id",Long.class,delta<0?"ORDER_CHANGE_RESERVATION":"ORDER_CHANGE_RELEASE",delta,before,after,order,"order_change="+operation,product);
    }
}
