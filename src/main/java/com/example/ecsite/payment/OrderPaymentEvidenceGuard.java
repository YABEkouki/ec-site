package com.example.ecsite.payment;

import java.util.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.service.order.evidence.JdbcOrderPaymentEvidenceReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Same-DataSource reads under the caller's existing Order lock. Acquires no upper locks or runtime locks. */
@Component
public final class OrderPaymentEvidenceGuard {
    private final JdbcTemplate jdbc;
    public OrderPaymentEvidenceGuard(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    private enum ReadMode { ORDER_LOCKED, ADVISORY }
    public PaymentContentEvidence inspect(Order order){return inspect(order,ReadMode.ORDER_LOCKED);}
    /** Display assessment only. Financial callers must always mint a fresh inspect(Order) proof under their Order lock. */
    public PaymentContentEvidence inspectAdvisory(Order order){return inspect(order,ReadMode.ADVISORY);}
    private PaymentContentEvidence inspect(Order order,ReadMode mode){
        if(order==null||order.getId()==null||!TransactionSynchronizationManager.isActualTransactionActive()
            ||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))return blocked();
        try{
            boolean schema=Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('order_payment_evidence_epochs') is not null",Boolean.class));
            if(!schema)return blocked();
            boolean enrolled=Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from orders o where o.id=? and
                    (o.current_evidence_epoch_id is not null or o.evidence_head_history_id is not null
                    or exists(select 1 from order_payment_evidence_epochs e where e.order_id=o.id)
                    or exists(select 1 from order_content_change_histories h where h.order_id=o.id and
                        (h.evidence_version is not null or h.evidence_epoch_id is not null or h.after_snapshot is not null))))
                """,Boolean.class,order.getId()));
            if(!enrolled)return order.getCurrentEvidenceEpochId()==null&&order.getEvidenceHeadHistoryId()==null?legacy():blocked();
            var reader=new JdbcOrderPaymentEvidenceReader(jdbc);
            var read=mode==ReadMode.ADVISORY?reader.readShipmentAdvisory(order.getId()):reader.readShipmentUnderOrderLock(order.getId());
            if(read.state()!=JdbcOrderPaymentEvidenceReader.State.READ||read.decision()==null||!read.decision().locallyVerified())return blocked();
            var proof=new PaymentContentEvidence(PaymentContentEvidence.State.VERIFIED,read.facts());
            return proof.matchesOrder(order)?proof:blocked();
        }catch(RuntimeException invalid){return blocked();}
    }
    private static PaymentContentEvidence blocked(){return new PaymentContentEvidence(PaymentContentEvidence.State.BLOCKED,null);}
    private static PaymentContentEvidence legacy(){return new PaymentContentEvidence(PaymentContentEvidence.State.LEGACY,null);}
}
