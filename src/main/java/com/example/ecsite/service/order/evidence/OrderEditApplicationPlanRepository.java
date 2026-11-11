package com.example.ecsite.service.order.evidence;

import java.util.*;
import java.security.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.*;

/** Internal same-DataSource repository; no Bean, stock writer, applier or provider dependency. */
public final class OrderEditApplicationPlanRepository {
    private final JdbcTemplate jdbc;
    private final OrderEditAdmissionPayloadCodec payloads=new OrderEditAdmissionPayloadCodec();
    private final OrderContentSnapshotCodec snapshots=new OrderContentSnapshotCodec();
    public OrderEditApplicationPlanRepository(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public Plan read(UUID id){
        transaction();Objects.requireNonNull(id);
        return jdbc.queryForObject("""
            select a.request_payload,a.candidate_payload,a.candidate_digest,
                case when a.base_evidence_history_id is null then e.start_snapshot else h.after_snapshot end as before_snapshot
            from order_edit_admissions a join order_payment_evidence_epochs e on e.id=a.base_evidence_epoch_id and e.order_id=a.order_id
            left join order_content_change_histories h on h.id=a.base_evidence_history_id and h.evidence_epoch_id=e.id and h.order_id=a.order_id
            where a.id=?
            """,(r,n)->{
                byte[] body=r.getBytes("candidate_payload"),digest=r.getBytes("candidate_digest");
                if(body==null||digest==null||!MessageDigest.isEqual(hash(body),digest))throw new IllegalStateException("Candidate digest mismatch");
                var candidate=payloads.decodeCandidate(body);
                var before=snapshots.decode(r.getBytes("before_snapshot"));
                payloads.validateSaved(payloads.decodeRequest(r.getBytes("request_payload")),candidate,before);
                var fresh=new TreeMap<Long,OrderContentSnapshot.Item>();candidate.preview().items().forEach(i->fresh.put(i.orderItemId(),i));
                var newKeys=new HashSet<Long>();candidate.newLineReferences().forEach(i->newKeys.add(i.previewItemId()));
                var lines=new ArrayList<Line>();
                for(var old:before.items())lines.add(new Line(new LineKey(KeyKind.EXISTING,old.orderItemId()),old.orderItemId(),old,fresh.get(old.orderItemId())));
                for(var item:candidate.preview().items())if(newKeys.contains(item.orderItemId()))lines.add(new Line(new LineKey(KeyKind.NEW,item.orderItemId()),null,null,item));
                return new Plan(id,candidate,lines,inventory(id));
            },id);
    }
    public Plan prepare(UUID id){
        writable();Objects.requireNonNull(id);
        jdbc.queryForObject("select f125_execution_lock(?)",Object.class,id);
        jdbc.queryForObject("select f125_execution_assert_basis(?)",Object.class,id);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select execution_state in ('QUEUED','OBSERVING') from order_edit_admissions where id=?",Boolean.class,id)))
            throw new IllegalStateException("Business state changed");
        if(Boolean.TRUE.equals(jdbc.queryForObject("select route='REAUTHORIZE' and exists(select 1 from order_change_operations op where op.order_id=a.order_id and op.submission_key=a.submission_key) from order_edit_admissions a where id=?",Boolean.class,id)))
            jdbc.queryForObject("select f125_reauth_assert_current(?)",Object.class,id);
        else if(!Boolean.TRUE.equals(jdbc.queryForObject("select unapplied_baseline=f125_execution_local(order_id) from order_edit_admissions where id=?",Boolean.class,id)))
            throw new IllegalStateException("Business state changed");
        var plan=read(id);
        byte[] digest=HexFormat.of().parseHex(digest(id));
        for(var d:plan.candidate().stockDeltas())jdbc.update("""
            insert into order_edit_inventory_plans(admission_id,order_id,product_id,candidate_digest,before_quantity,after_quantity)
            values (?,?,?,?,?,?) on conflict(admission_id,product_id) do nothing
            """,id,plan.candidate().orderId(),d.productId(),digest,d.beforeQuantity(),d.afterQuantity());
        var saved=inventory(id);
        if(saved.size()!=plan.candidate().stockDeltas().size())throw new IllegalStateException("Incomplete inventory plan");
        for(int i=0;i<saved.size();i++){
            var a=saved.get(i);var b=plan.candidate().stockDeltas().get(i);
            if(a.productId()!=b.productId()||a.beforeQuantity()!=b.beforeQuantity()||a.afterQuantity()!=b.afterQuantity()
                ||a.reserveQuantity()!=b.additionalRequired()||a.releaseQuantity()!=b.releaseRequired()
                ||a.reservationState()!=null&&!"REAUTHORIZE".equals(jdbc.queryForObject("select route from order_edit_admissions where id=?",String.class,id)))
                throw new IllegalStateException("Saved inventory plan mismatch");
        }
        return new Plan(id,plan.candidate(),plan.lines(),saved);
    }
    public Context applicationContext(Ownership ownership){
        writable();Objects.requireNonNull(ownership);
        jdbc.queryForObject("select f125_edit_application_context(?,?,?,?)",Object.class,
            ownership.admissionId(),ownership.attemptId(),ownership.token(),ownership.controlVersion());
        var plan=read(ownership.admissionId());var c=plan.candidate();
        return new Context(1,ownership,c.orderId(),c.revision(),Math.incrementExact(c.revision()),c.preview().totalAmount(),digest(ownership.admissionId()));
    }
    private String digest(UUID id){return jdbc.queryForObject("select encode(candidate_digest,'hex') from order_edit_admissions where id=?",String.class,id);}
    private List<InventoryPlan> inventory(UUID id){return jdbc.query("select product_id,before_quantity,after_quantity,reserve_quantity,release_quantity,reservation_state from order_edit_inventory_plans where admission_id=? order by product_id",
        (r,n)->new InventoryPlan(r.getObject(1,Long.class),r.getObject(2,Integer.class),r.getObject(3,Integer.class),r.getObject(4,Integer.class),r.getObject(5,Integer.class),r.getString(6)),id);}
    private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("Same-DataSource transaction required");}
    private void writable(){transaction();if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!"read committed".equals(jdbc.queryForObject("show transaction_isolation",String.class)))throw new IllegalStateException("Writable READ COMMITTED transaction required");}
    private static byte[] hash(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
