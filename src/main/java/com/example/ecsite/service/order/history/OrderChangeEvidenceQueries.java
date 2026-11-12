package com.example.ecsite.service.order.history;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

/** Fixed, SELECT-only query catalogue. No caller-controlled SQL identifiers or workflow functions. */
final class OrderChangeEvidenceQueries {
    record Spec(EvidenceSet set,String table,String scope,String keys,boolean parent,String snapshot) {}
    static final List<Spec> SPECS=List.of(
        new Spec(EvidenceSet.HISTORIES,"order_content_change_histories","t.order_id=:orderId","id",true,"after_snapshot"),
        new Spec(EvidenceSet.ITEM_CHANGES,"order_content_change_items","t.history_id in (select id from order_content_change_histories where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.CHARGE_CHANGES,"order_content_change_charges","t.history_id in (select id from order_content_change_histories where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.OPERATIONS,"order_change_operations","t.order_id=:orderId","id",true,""),
        new Spec(EvidenceSet.OPERATION_ITEMS,"order_change_items","t.order_id=:orderId","operation_id,order_item_id",false,""),
        new Spec(EvidenceSet.ADMISSIONS,"order_edit_admissions","t.order_id=:orderId","id",true,""),
        new Spec(EvidenceSet.ADMISSION_PROOFS,"order_edit_admission_evidences","t.admission_id in (select id from order_edit_admissions where order_id=:orderId)","admission_id",false,""),
        new Spec(EvidenceSet.CONTROLS,"order_edit_execution_controls","t.order_id=:orderId","admission_id",false,""),
        new Spec(EvidenceSet.ATTEMPTS,"order_edit_execution_attempts","t.order_id=:orderId","id",false,""),
        new Spec(EvidenceSet.RESULTS,"order_edit_execution_attempt_results","t.order_id=:orderId","id",false,""),
        new Spec(EvidenceSet.EVENTS,"order_edit_admission_events","t.admission_id in (select id from order_edit_admissions where order_id=:orderId)","admission_id,event_no",false,""),
        new Spec(EvidenceSet.EPOCHS,"order_payment_evidence_epochs","t.order_id=:orderId","id",true,"start_snapshot"),
        new Spec(EvidenceSet.PAYMENTS,"payments","t.order_id=:orderId","id",false,""),
        new Spec(EvidenceSet.TRANSACTIONS,"payment_transactions","t.payment_id in (select id from payments where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.CAPTURE_REQUESTS,"capture_requests","t.payment_id in (select id from payments where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.LEGACY_CLOSURE_ATTEMPTS,"order_change_closure_attempts","t.order_id=:orderId","id",false,""),
        new Spec(EvidenceSet.LEGACY_CLOSURE_ISSUES,"order_change_closure_issues","t.order_id=:orderId","id",false,""),
        new Spec(EvidenceSet.LEGACY_CLOSURE_EVENTS,"order_change_closure_issue_events","t.issue_id in (select id from order_change_closure_issues where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.FINANCIAL_REQUESTS,"order_change_financial_requests","t.order_id=:orderId","id",false,""),
        new Spec(EvidenceSet.DISPATCHES,"order_change_request_dispatches","t.request_id in (select id from order_change_financial_requests where order_id=:orderId)","request_id",false,""),
        new Spec(EvidenceSet.RECOVERY_AUDITS,"order_change_recovery_audits","t.order_id=:orderId","request_id",false,""),
        new Spec(EvidenceSet.UNAPPLIED,"order_edit_unapplied_evidences","t.order_id=:orderId","admission_id",false,""),
        new Spec(EvidenceSet.FAILURE_CONTROLS,"order_edit_failure_closure_controls","t.order_id=:orderId","id",false,""),
        new Spec(EvidenceSet.FAILURE_OBSERVATIONS,"order_edit_failure_closure_observations","t.control_id in (select id from order_edit_failure_closure_controls where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.FAILURE_UNKNOWN_RESOLUTIONS,"order_edit_failure_unknown_resolutions","t.control_id in (select id from order_edit_failure_closure_controls where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.FAILURE_BUSINESS_CLOSURES,"order_edit_failure_business_closures","t.order_id=:orderId","control_id",false,""),
        new Spec(EvidenceSet.FAILURE_WAITING_RESOLUTIONS,"order_edit_failure_waiting_resolutions","t.control_id in (select control_id from order_edit_failure_business_closures where order_id=:orderId)","unknown_result_id",false,""),
        new Spec(EvidenceSet.GENERATIONS,"order_edit_runtime_generations","t.id in (select db_generation_id from order_edit_admissions where order_id=:orderId union select db_generation_id from order_edit_execution_attempts where order_id=:orderId union select db_generation_id from order_edit_execution_controls where order_id=:orderId union select generation_id from order_edit_failure_closure_controls where order_id=:orderId union select db_generation_id from order_edit_unapplied_evidences where order_id=:orderId)","id",false,""),
        new Spec(EvidenceSet.INVENTORY_PLANS,"order_edit_inventory_plans","t.order_id=:orderId","admission_id,product_id",false,""),
        new Spec(EvidenceSet.RESERVATIONS,"inventory_reservations","t.operation_id in (select id from order_change_operations where order_id=:orderId)","operation_id,order_item_id",false,""));
    private final NamedParameterJdbcTemplate jdbc;
    OrderChangeEvidenceQueries(javax.sql.DataSource source){
        jdbc=new NamedParameterJdbcTemplate(source);jdbc.getJdbcTemplate().setQueryTimeout(5);
    }
    OrderEvidence owned(long order,long customer){
        var rows=jdbc.query("""
            select id,user_id,content_revision,current_evidence_epoch_id,evidence_head_history_id,
                current_setting('transaction_isolation') as isolation,
                current_setting('transaction_read_only') as readonly
            from orders where id=:orderId and user_id=:customerId
            """,Map.of("orderId",order,"customerId",customer),(r,n)->{
                if(!"repeatable read".equals(r.getString("isolation"))||!"on".equals(r.getString("readonly")))
                    throw new IllegalStateException("Evidence read requires database read-only REPEATABLE READ");
                return new OrderEvidence(r.getLong("id"),r.getLong("user_id"),r.getInt("content_revision"),
                    r.getString("current_evidence_epoch_id"),r.getObject("evidence_head_history_id",Long.class));
            });
        if(rows.size()!=1)throw new com.example.ecsite.exception.OrderNotFoundException(order);
        return rows.getFirst();
    }
    record Size(long bytes,Integer snapshotBytes) {}
    List<Size> sizes(Spec spec,long order,int cap){
        // A conservative UTF-8 JSON wire budget includes bytea's hex expansion. Bodies stay server-side.
        String snapshot=spec.snapshot().isEmpty()?"null::integer":"octet_length(t."+spec.snapshot()+")";
        return jdbc.query("select octet_length(to_jsonb(t)::text) as bytes,"+snapshot+" as snapshot_bytes from "+spec.table()+
            " t where "+spec.scope()+" order by "+spec.keys()+" limit :cap",
            Map.of("orderId",order,"cap",cap+1),(r,n)->new Size(r.getLong(1),r.getObject(2,Integer.class)));
    }
    List<EvidenceRow> rows(Spec spec,long order,int cap){
        return jdbc.query("select t.* from "+spec.table()+" t where "+spec.scope()+" order by "+spec.keys()+" limit :cap",
            Map.of("orderId",order,"cap",cap+1),(r,n)->row(r));
    }
    private EvidenceRow row(ResultSet r)throws SQLException {
        var values=new LinkedHashMap<String,String>();var binary=new LinkedHashMap<String,BinaryEvidence>();
        var meta=r.getMetaData();
        for(int col=1;col<=meta.getColumnCount();col++){
            String name=meta.getColumnLabel(col),type=meta.getColumnTypeName(col);
            if("bytea".equals(type)){byte[] bytes=r.getBytes(col);binary.put(name,bytes==null?null:new BinaryEvidence(bytes));}
            else if("timestamptz".equals(type)){var value=r.getObject(col,OffsetDateTime.class);values.put(name,value==null?null:value.toString());}
            else if("timestamp".equals(type)){var value=r.getObject(col,LocalDateTime.class);values.put(name,value==null?null:value.toString());}
            else values.put(name,r.getString(col));
        }
        return new EvidenceRow(values,binary);
    }
    Set<String> existingReferenceIds(EvidenceSet target,Set<String> ids){
        var spec=SPECS.stream().filter(s->s.set()==target).findFirst().orElseThrow();
        // Only existence metadata for out-of-scope IDs. Never load foreign order payloads.
        return new HashSet<>(jdbc.queryForList("select id::text from "+spec.table()+" where id::text in (:ids)",
                Map.of("ids",ids),String.class));
    }

}
