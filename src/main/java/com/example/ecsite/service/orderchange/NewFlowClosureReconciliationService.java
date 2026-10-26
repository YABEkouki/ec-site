package com.example.ecsite.service.orderchange;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.example.ecsite.payment.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** GET-only automation. Financial decisions remain exclusively in the existing closure control. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class NewFlowClosureReconciliationService {
    private static final Logger log=LoggerFactory.getLogger(NewFlowClosureReconciliationService.class);
    static final int BATCH_SIZE=100;
    private static final String ELIGIBLE="abort_requested_at is not null and abort_flow_closed_at is null and state in ('RESERVED','AUTHORIZING','REQUIRES_ACTION','AUTH_UNKNOWN','AUTHORIZED','ABORTING','ABORT_UNKNOWN')";
    private final JdbcTemplate jdbc;
    private final PaymentGateway gateway;
    private final TransactionTemplate transaction;
    private final NewFlowClosureControl control;
    public record ScanReport(int attempted,Map<String,Long> outcomes,long getFailures,long elapsedNanos,
                             long unresolved,long manualHeld,long oldestSeconds){
        public ScanReport{outcomes=Map.copyOf(outcomes);}
    }
    public NewFlowClosureReconciliationService(JdbcTemplate jdbc,PlatformTransactionManager manager,
            OrderReauthorizationTransactions results,PaymentGateway gateway){
        this.jdbc=jdbc;this.gateway=gateway;this.control=new NewFlowClosureControl(results,manager);
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** Cursor commit is confirmed before any GET. A lost reserved batch is revisited after wrap-around. */
    List<UUID> reserve(){
        requireNoTransaction();var committed=new AtomicBoolean();
        var ids=Objects.requireNonNull(transaction.execute(tx->{
            var cursor=jdbc.queryForMap("select after_id,upper_id from order_change_closure_scan_cursor where id=1 for update");
            UUID after=(UUID)cursor.get("after_id"),upper=(UUID)cursor.get("upper_id");
            if(upper==null){
                var highest=jdbc.queryForList("select id from order_change_operations where "+ELIGIBLE+" order by id desc limit 1",UUID.class);
                if(highest.isEmpty()){confirmCommit(committed);return List.<UUID>of();}
                upper=highest.getFirst();
            }
            var page=jdbc.queryForList("select id from order_change_operations where "+ELIGIBLE+
                " and (?::uuid is null or id>?::uuid) and id<=?::uuid order by id limit ?",UUID.class,after,after,upper,BATCH_SIZE);
            boolean finished=page.size()<BATCH_SIZE||page.getLast().equals(upper);
            jdbc.update("update order_change_closure_scan_cursor set after_id=?,upper_id=?,updated_at=clock_timestamp() where id=1",
                finished?null:page.getLast(),finished?null:upper);
            confirmCommit(committed);return page;
        }));
        if(!committed.get())throw new IllegalStateException("Closure scan cursor commit unconfirmed");
        return List.copyOf(ids);
    }
    private static void confirmCommit(AtomicBoolean committed){
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){committed.set(status==STATUS_COMMITTED);}
        });
    }
    public ScanReport scan(){
        requireNoTransaction();long start=System.nanoTime();var ids=reserve();var counts=new TreeMap<String,Long>();var failures=new AtomicLong();
        for(var result:NewFlowClosureControl.ReconcileResult.values())counts.put(result.name(),0L);
        for(var id:ids){
            NewFlowClosureControl.ReconcileResult result;
            try{result=control.reconcile(id,gateway,failures::incrementAndGet);}
            catch(RuntimeException failure){result=NewFlowClosureControl.ReconcileResult.RETRY_LATER;}
            counts.merge(result.name(),1L,Long::sum);
        }
        var backlog=jdbc.queryForMap("""
            select count(*) as unresolved,
              count(*) filter(where state='MANUAL_REVIEW') as manual,
              greatest(0,coalesce(extract(epoch from(current_timestamp-(min(abort_requested_at) at time zone 'Asia/Tokyo'))),0))::bigint as oldest
            from order_change_operations where abort_requested_at is not null and abort_flow_closed_at is null
              and state not in ('ABORTED','COMPLETED')
            """);
        var report=new ScanReport(ids.size(),counts,failures.get(),Math.max(0,System.nanoTime()-start),
            ((Number)backlog.get("unresolved")).longValue(),((Number)backlog.get("manual")).longValue(),((Number)backlog.get("oldest")).longValue());
        // Aggregate only: no payload, URLs, credentials, session data or exception causes.
        log.info("New Flow closure scan: {}",report);
        if(report.getFailures()>0)log.warn("New Flow closure GET deferred: failures={}, retryLater={}",
            report.getFailures(),report.outcomes().get("RETRY_LATER"));
        if(report.outcomes().get("CONFLICT")>0||report.outcomes().get("MANUAL_REVIEW_REQUIRED")>0||report.manualHeld()>0)
            log.warn("New Flow closure scan requires operator review: conflicts={}, dangerous={}, manualHeld={}",
                report.outcomes().get("CONFLICT"),report.outcomes().get("MANUAL_REVIEW_REQUIRED"),report.manualHeld());
        return report;
    }
    /** Called only after authenticated existing webhook synchronization returns normally with no Tx. */
    public void afterWebhook(String providerFlowId){
        requireNoTransaction();
        if(providerFlowId==null||!providerFlowId.matches("pfw_[A-Za-z0-9_-]{1,251}"))return;
        try{
            var committed=new AtomicBoolean();
            var targets=transaction.execute(tx->{var found=jdbc.queryForList("""
                select o.id from order_change_operations o
                join payments p on p.id=o.new_payment_id and p.order_id=o.order_id
                join order_change_financial_requests r on r.operation_id=o.id and r.order_id=o.order_id and r.payment_id=p.id
                where p.provider='PAYJP' and p.payment_method='CARD' and p.provider_payment_id=?
                  and r.request_kind='CREATE' and r.purpose='ORDER_CHANGE_AUTHORIZATION'
                  and o.new_payment_id<>o.old_payment_id and o.abort_requested_at is not null
                  and o.abort_flow_closed_at is null and o.state not in ('MANUAL_REVIEW','ABORTED','COMPLETED')
                order by o.id limit 2
                """,UUID.class,providerFlowId);confirmCommit(committed);return found;});
            if(!committed.get()||targets==null||targets.size()!=1)return; // Never guess a latest Payment/operation.
            var failures=new AtomicLong();var result=control.reconcile(targets.getFirst(),gateway,failures::incrementAndGet);
            log.info("New Flow closure webhook trigger: result={}, getFailures={}",result,failures.get());
            if(failures.get()>0)log.warn("New Flow closure webhook GET deferred: failures={}",failures.get());
            if(result==NewFlowClosureControl.ReconcileResult.CONFLICT||result==NewFlowClosureControl.ReconcileResult.MANUAL_REVIEW_REQUIRED)
                log.warn("New Flow closure webhook trigger requires operator review: result={}",result);
        }catch(RuntimeException failure){log.warn("New Flow closure webhook trigger deferred; durable operation scan will recover");}
    }
    private static void requireNoTransaction(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Closure automation requires no ambient transaction");
    }
}
