package com.example.ecsite.service.order.history;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.exception.OrderNotFoundException;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

/** Acquires unverified facts only. Every public read performs ownership authorization first. */
@Repository
public class JdbcCustomerOrderChangeEvidenceReader implements CustomerOrderChangeEvidenceReader {
    private final DataSource source;
    private final OrderChangeEvidenceQueries queries;
    private final OrderChangeEvidenceLimits limits;
    @Autowired public JdbcCustomerOrderChangeEvidenceReader(DataSource source){this(source,OrderChangeEvidenceLimits.DEFAULT);}
    public JdbcCustomerOrderChangeEvidenceReader(DataSource source,OrderChangeEvidenceLimits limits){
        this.source=Objects.requireNonNull(source);this.limits=Objects.requireNonNull(limits);queries=new OrderChangeEvidenceQueries(source);
    }
    @Override public OrderChangeEvidenceBundle readForCustomer(long orderId,long customerId){
        if(orderId<=0||customerId<=0)throw new OrderNotFoundException(orderId);
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!TransactionSynchronizationManager.hasResource(source))throw new IllegalStateException("Same-DataSource read-only transaction required");
        boolean autoCommit=Boolean.TRUE.equals(new JdbcTemplate(source).execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)java.sql.Connection::getAutoCommit));
        if(autoCommit)throw new IllegalStateException("Transactional connection required");
        var order=queries.owned(orderId,customerId);long rows=0,bytes=0;
        for(var spec:OrderChangeEvidenceQueries.SPECS){
            int cap=spec.parent()?limits.parents():limits.children();var sizes=queries.sizes(spec,orderId,cap);
            rows+=sizes.size();
            for(var size:sizes){
                bytes=Math.addExact(bytes,size.bytes());
                if(size.snapshotBytes()!=null&&size.snapshotBytes()>limits.snapshotBytes())return limited(order,rows,bytes,spec.set(),"SNAPSHOT_LIMIT");
            }
            if(sizes.size()>cap||rows>limits.totalRows()||bytes>limits.rawBytes())return limited(order,rows,bytes,spec.set(),"ACQUISITION_LIMIT");
        }
        var evidence=new EnumMap<EvidenceSet,List<EvidenceRow>>(EvidenceSet.class);
        for(var spec:OrderChangeEvidenceQueries.SPECS)evidence.put(spec.set(),queries.rows(spec,orderId,spec.parent()?limits.parents():limits.children()));
        var diagnostics=new ArrayList<>(OrderChangeEvidenceReferences.inspect(order,evidence,queries));
        for(var row:evidence.get(EvidenceSet.EPOCHS))if(row.binaries().get("start_snapshot")==null)
            diagnostics.add(new Diagnostic("MISSING_SNAPSHOT",EvidenceSet.EPOCHS,row.value("id"),"start_snapshot"));
        for(var row:evidence.get(EvidenceSet.HISTORIES))if(row.value("evidence_version")!=null&&row.binaries().get("after_snapshot")==null)
            diagnostics.add(new Diagnostic("MISSING_SNAPSHOT",EvidenceSet.HISTORIES,row.value("id"),"after_snapshot"));
        if(diagnostics.size()>limits.children())return limited(order,rows,bytes,null,"DIAGNOSTIC_LIMIT");
        return new OrderChangeEvidenceBundle(Status.COMPLETE,order,evidence,new Coverage(rows,bytes),diagnostics);
    }
    private OrderChangeEvidenceBundle limited(OrderEvidence order,long rows,long bytes,EvidenceSet set,String code){
        return new OrderChangeEvidenceBundle(Status.LIMIT_EXCEEDED,order,Map.of(),new Coverage(rows,bytes),
                List.of(new Diagnostic(code,set,null,null)));
    }
}
