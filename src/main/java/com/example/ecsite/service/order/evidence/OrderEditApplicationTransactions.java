package com.example.ecsite.service.order.evidence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.payjp.OrderAuthorizationObservation;
import static com.example.ecsite.service.order.evidence.OrderContentSnapshot.*;
import static com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.*;

/** Internal independent RC transactions; not a Bean. No gateway or financial POST dependency. */
public final class OrderEditApplicationTransactions {
    public record Prepared(Ownership ownership,OrderAuthorizationObservation.Expected expected,UUID reauthorizationOperationId) {
        public Prepared(Ownership owner,OrderAuthorizationObservation.Expected expected){this(owner,expected,null);}
    }
    public record Applied(UUID admissionId,UUID operationId,long historyId,OrderContentSnapshot snapshot) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcOrderPaymentEvidenceReader reader;
    private final OrderEditApplicationPlanRepository plans;
    private final OrderContentSnapshotCodec snapshots=new OrderContentSnapshotCodec();
    private final OrderEditAdmissionPayloadCodec payloads=new OrderEditAdmissionPayloadCodec();
    private final CaptureConnectionIdentity connection;
    public OrderEditApplicationTransactions(DataSource source,CaptureConnectionIdentity connection){this(source,connection,new DataSourceTransactionManager(source));}
    public OrderEditApplicationTransactions(DataSource source,CaptureConnectionIdentity connection,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));jdbc.setQueryTimeout(5);this.connection=Objects.requireNonNull(connection);
        reader=new JdbcOrderPaymentEvidenceReader(jdbc);plans=new OrderEditApplicationPlanRepository(jdbc);
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Prepared prepare(Ownership owner){outside();return tx.execute(s->{plans.applicationContext(owner);var p=plans.prepare(owner.admissionId());
        var r=reader.readLocked(p.candidate().orderId());verified(r);return new Prepared(owner,expected(r));});}
    public Applied apply(Prepared prepared,OrderAuthorizationObservation observation){
        outside();Objects.requireNonNull(prepared);Objects.requireNonNull(observation);
        if(prepared.reauthorizationOperationId()!=null)return applyReauthorized(prepared,observation);
        return tx.execute(s->{
            var owner=prepared.ownership();plans.applicationContext(owner);var plan=plans.prepare(owner.admissionId());var c=plan.candidate();
            var before=reader.readLocked(c.orderId());verified(before);
            var target=expected(before);if(!target.equals(prepared.expected()))throw new IllegalStateException("Observation target changed");
            // Locks all affected product rows in a deterministic order, including zero net deltas.
            var products=new TreeSet<Long>();before.facts().current().items().forEach(i->products.add(i.productId()));c.preview().items().forEach(i->products.add(i.productId()));
            for(long p:products)jdbc.queryForObject("select id from products where id=? for update",Long.class,p);
            var categoryIds=new TreeSet<Long>();var taxIds=new TreeSet<Long>();
            for(long p:products){var row=jdbc.queryForMap("select category_id,tax_category_id from products where id=?",p);categoryIds.add(((Number)row.get("category_id")).longValue());taxIds.add(((Number)row.get("tax_category_id")).longValue());}
            for(long id:categoryIds)jdbc.queryForObject("select id from categories where id=? for share",Long.class,id);
            for(long id:taxIds)jdbc.queryForObject("select id from tax_categories where id=? for share",Long.class,id);
            var offers=offers(products);var request=payloads.decodeRequest(jdbc.queryForObject("select request_payload from order_edit_admissions where id=?",byte[].class,owner.admissionId()));
            var context=OrderEditCandidateSources.context(before,c.customerId(),jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())",java.time.LocalDateTime.class));
            var recalculated=new OrderEditCandidateCalculator().calculateForAdmission(context,new OrderEditCandidate.Request(request.orderId(),request.revision(),request.lines()),offers);
            if(recalculated.route()!=OrderChangeRoutePolicy.Route.RETAIN_AUTH||!Arrays.equals(payloads.candidate(recalculated.candidate()),jdbc.queryForObject("select candidate_payload from order_edit_admissions where id=?",byte[].class,owner.admissionId())))throw new IllegalStateException("Candidate changed");
            if(c.preview().totalAmount()>before.decision().authorizedAmount())throw new IllegalStateException("Authorization ceiling");
            observation.consume(target);
            UUID operation=UUID.randomUUID();
            jdbc.queryForObject("select f125_edit_application_begin(?,?,?,?,?,?)",Object.class,owner.admissionId(),owner.attemptId(),owner.token(),owner.controlVersion(),operation,observation.payload().getBytes(StandardCharsets.UTF_8));
            var old=before.facts().current();var after=writeContent(plan,operation,false);
            long history=writeHistory(old,after,c,operation,true,owner.admissionId());
            jdbc.update("update orders set item_subtotal=?,charge_total=?,tax_amount=?,total_amount=?,content_revision=?,evidence_head_history_id=? where id=?",after.itemSubtotal(),after.chargeTotal(),after.taxAmount(),after.totalAmount(),after.contentRevision(),history,c.orderId());
            jdbc.update("update order_change_operations set state='COMPLETED',content_change_history_id=?,updated_at=localtimestamp,version=version+1 where id=?",history,operation);
            finish(owner,operation,history,after);
            var saved=reader.readLocked(c.orderId());verified(saved);if(!saved.facts().current().equals(after))throw new IllegalStateException("Persisted content differs");
            freshBeforeCommit(observation,target);
            return new Applied(owner.admissionId(),operation,history,after);
        });
    }
    public UUID initializeReauthorization(Prepared prepared,OrderAuthorizationObservation observation){
        outside();Objects.requireNonNull(prepared);Objects.requireNonNull(observation);
        return tx.execute(s->{
            var owner=prepared.ownership();plans.applicationContext(owner);var plan=plans.prepare(owner.admissionId());var c=plan.candidate();
            var read=reader.readLocked(c.orderId());verified(read);var target=expected(read);
            if(!target.equals(prepared.expected())||c.preview().totalAmount()<=c.authorizedAmount())throw new IllegalStateException("Reauthorization basis changed");
            validateCatalog(plan);observation.consume(target);UUID operation=UUID.randomUUID();
            jdbc.queryForObject("select f125_reauth_initialize(?,?,?,?,?,?)",Object.class,owner.admissionId(),owner.attemptId(),owner.token(),owner.controlVersion(),operation,observation.payload().getBytes(StandardCharsets.UTF_8));
            new OrderEditReauthorizationBridge(jdbc).hold(c.orderId(),operation);freshBeforeCommit(observation,target);return operation;
        });
    }
    public UUID reauthorizationOperation(UUID admission){outside();return tx.execute(s->{
        var ids=jdbc.queryForList("select o.id from order_edit_admissions a join order_change_operations o on o.order_id=a.order_id and o.submission_key=a.submission_key where a.id=? and a.route='REAUTHORIZE' and o.execution_version=1 and o.execution_route='REAUTHORIZE'",UUID.class,admission);
        if(ids.size()>1)throw new IllegalStateException("Ambiguous reauthorization");return ids.isEmpty()?null:ids.getFirst();});}
    public Prepared prepareReauthorized(Ownership owner,UUID operation){
        outside();return tx.execute(s->{
            jdbc.queryForObject("select f125_reauth_application_context(?,?,?,?)",Object.class,owner.admissionId(),owner.attemptId(),owner.token(),owner.controlVersion());
            var plan=plans.read(owner.admissionId());new OrderEditReauthorizationBridge(jdbc).requireOwner(plan.candidate().orderId(),operation,owner);
            new OrderEditReauthorizationBridge(jdbc).requireHeld(plan.candidate().orderId(),operation);
            validateCatalog(plan);return new Prepared(owner,expectedReauthorized(owner.admissionId(),operation),operation);
        });
    }
    public void resumeReauthorization(UUID admission,long version){outside();tx.executeWithoutResult(s->jdbc.queryForObject("select f125_reauth_resume(?,?,?)",Object.class,admission,UUID.randomUUID(),version));}
    private Applied applyReauthorized(Prepared prepared,OrderAuthorizationObservation observation){
        return tx.execute(s->{
            var owner=prepared.ownership();UUID operation=prepared.reauthorizationOperationId();
            jdbc.queryForObject("select f125_reauth_application_context(?,?,?,?)",Object.class,owner.admissionId(),owner.attemptId(),owner.token(),owner.controlVersion());
            var plan=plans.read(owner.admissionId());var c=plan.candidate();
            new OrderEditReauthorizationBridge(jdbc).requireOwner(c.orderId(),operation,owner);new OrderEditReauthorizationBridge(jdbc).requireHeld(c.orderId(),operation);
            var before=currentContent(c.orderId());if(!snapshots.fingerprint(before).equals(c.baseFingerprint()))throw new IllegalStateException("Original content changed");
            validateCatalog(plan);var target=expectedReauthorized(owner.admissionId(),operation);
            if(!target.equals(prepared.expected()))throw new IllegalStateException("Reauthorization changed during observation");
            observation.consume(target);
            jdbc.queryForObject("select f125_reauth_application_begin(?,?,?,?,?,?)",Object.class,owner.admissionId(),owner.attemptId(),owner.token(),owner.controlVersion(),operation,observation.payload().getBytes(StandardCharsets.UTF_8));
            var after=writeContent(plan,operation,true);long history=writeHistory(before,after,c,operation,false,owner.admissionId());
            if(jdbc.update("update order_change_operations set state='COMPLETED',content_change_history_id=?,updated_at=localtimestamp,version=version+1 where id=? and state='COMMIT_PENDING'",history,operation)!=1)throw new IllegalStateException("Reauthorization not ready");
            var cancel=jdbc.queryForMap("select t.id,t.amount,t.order_content_revision,t.provider_transaction_id,t.status from payment_transactions t where t.order_change_operation_id=? and t.purpose='ORDER_CHANGE_OLD_AUTH_CANCEL'",operation);
            var proof=new OrderPaymentEvidence.Reauthorization(operation,c.orderId(),c.paymentId(),target.paymentId(),target.authorizationId(),history,c.revision(),after.totalAmount(),"COMPLETED",
                ((Number)cancel.get("id")).longValue(),(String)cancel.get("status"),((Number)cancel.get("amount")).intValue(),((Number)cancel.get("order_content_revision")).intValue(),(String)cancel.get("provider_transaction_id"));
            UUID epoch=UUID.randomUUID();var root=new OrderPaymentEvidence.Epoch(1,epoch,c.orderId(),target.paymentId(),target.authorizationId(),target.amount(),target.revision(),OrderPaymentEvidence.Origin.REAUTH_COMPLETED,after,proof,null,null);
            byte[] body=snapshots.encode(after),observed=observation.payload().getBytes(StandardCharsets.UTF_8);
            jdbc.update("""
              insert into order_payment_evidence_epochs(id,order_id,payment_id,authorization_transaction_id,origin_kind,
                start_content_revision,start_total_amount,authorized_amount,authorization_revision,provider_flow_id,
                start_snapshot,start_fingerprint,observation_payload,observation_fingerprint,observation_started_at,observation_finished_at,
                evidence_version,evidence_digest,previous_epoch_id,origin_operation_id,origin_history_id)
              values (?,?,?,?,'REAUTH_COMPLETED',?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?)
              """,epoch,c.orderId(),target.paymentId(),target.authorizationId(),after.contentRevision(),after.totalAmount(),target.amount(),target.revision(),target.flowId(),body,hex(snapshots.fingerprint(after)),observed,hex(OrderEvidenceDigests.sha256(observed)),java.time.OffsetDateTime.ofInstant(observation.startedAt(),java.time.ZoneOffset.UTC),java.time.OffsetDateTime.ofInstant(observation.finishedAt(),java.time.ZoneOffset.UTC),hex(OrderEvidenceDigests.epoch(root)),c.epochId(),operation,history);
            jdbc.update("update orders set item_subtotal=?,charge_total=?,tax_amount=?,total_amount=?,content_revision=?,current_evidence_epoch_id=?,evidence_head_history_id=null,active_payment_id=? where id=?",after.itemSubtotal(),after.chargeTotal(),after.taxAmount(),after.totalAmount(),after.contentRevision(),epoch,target.paymentId(),c.orderId());
            finish(owner,operation,history,after);var saved=reader.readLocked(c.orderId());verified(saved);
            if(!saved.facts().current().equals(after))throw new IllegalStateException("Reauthorized content differs");
            freshBeforeCommit(observation,target);return new Applied(owner.admissionId(),operation,history,after);
        });
    }
    private OrderAuthorizationObservation.Expected expectedReauthorized(UUID admission,UUID operation){
        return jdbc.queryForObject("""
          select a.order_id,p.id,t.id,p.provider_payment_id,p.amount,t.order_content_revision,encode(sha256(convert_to(f125_execution_local(a.order_id)::text,'UTF8')),'hex')
          from order_edit_admissions a join order_change_operations o on o.order_id=a.order_id and o.submission_key=a.submission_key
          join payments p on p.id=o.new_payment_id and p.order_id=a.order_id
          join payment_transactions t on t.payment_id=p.id and t.order_change_operation_id=o.id and t.purpose='ORDER_CHANGE_AUTHORIZATION'
          where a.id=? and o.id=? and o.state='COMMIT_PENDING' and p.status='AUTHORIZED' and t.status='SUCCEEDED'
          """,(r,n)->new OrderAuthorizationObservation.Expected(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getInt(5),r.getInt(6),r.getString(7),connection,operation),admission,operation);
    }
    private OrderContentSnapshot currentContent(long order){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var row=json.readTree(jdbc.queryForObject("select to_jsonb(o)::text from orders o where id=?",String.class,order));
        var items=jdbc.query("select to_jsonb(i)::text from order_items i where order_id=? order by id",(r,n)->json.readTree(r.getString(1)),order);
        var charges=jdbc.query("select to_jsonb(c)::text from order_charges c where order_id=? order by id",(r,n)->json.readTree(r.getString(1)),order);
        return new OrderContentSnapshotFactory().fromRows(row,items,charges);
    }
    private void validateCatalog(Plan plan){
        var c=plan.candidate();var before=currentContent(c.orderId());
        if(!snapshots.fingerprint(before).equals(c.baseFingerprint()))throw new IllegalStateException("Content basis changed");
        var request=payloads.decodeRequest(jdbc.queryForObject("select request_payload from order_edit_admissions where id=?",byte[].class,plan.admissionId()));payloads.validateSaved(request,c,before);
        var products=new TreeSet<Long>();before.items().forEach(i->products.add(i.productId()));c.preview().items().forEach(i->products.add(i.productId()));
        for(long id:products)jdbc.queryForObject("select id from products where id=? for update",Long.class,id);
        var categories=new TreeSet<Long>();var taxes=new TreeSet<Long>();for(long id:products){var row=jdbc.queryForMap("select category_id,tax_category_id from products where id=?",id);categories.add(((Number)row.get("category_id")).longValue());taxes.add(((Number)row.get("tax_category_id")).longValue());}
        for(long id:categories)jdbc.queryForObject("select id from categories where id=? for share",Long.class,id);
        for(long id:taxes)jdbc.queryForObject("select id from tax_categories where id=? for share",Long.class,id);
        var offers=new HashMap<Long,OrderEditCandidate.Offer>();offers(products).forEach(o->offers.put(o.productId(),o));
        for(var o:c.newOffers())if(!o.equals(offers.get(o.productId())))throw new IllegalStateException("New offer changed");
        for(var l:plan.lines())if(l.before()!=null&&l.after()!=null&&l.after().quantity()>l.before().quantity()&&!offers.get(l.after().productId()).active())throw new IllegalStateException("Product unavailable");
    }
    private OrderContentSnapshot writeContent(Plan plan,UUID operation,boolean reserved){
        var c=plan.candidate();var finalItems=new ArrayList<Item>();
        for(var line:plan.lines()){
            if(line.key().kind()==KeyKind.NEW){var i=line.after();long id=insertItem(c.orderId(),i);finalItems.add(withId(i,id));}
            else if(line.after()==null)jdbc.update("delete from order_items where id=? and order_id=?",line.persistedItemId(),c.orderId());
            else {var i=line.after();if(jdbc.update("update order_items set quantity=?,subtotal=? where id=? and order_id=?",i.quantity(),i.subtotal(),i.orderItemId(),c.orderId())!=1)throw new IllegalStateException("Missing item");finalItems.add(i);}
        }
        for(var d:c.stockDeltas()){
            if(reserved&&d.additionalRequired()>0)continue;
            int stock=jdbc.queryForObject("select stock from products where id=?",Integer.class,d.productId());int delta=Math.subtractExact(d.beforeQuantity(),d.afterQuantity());
            int after=Math.addExact(stock,delta);if(after<0)throw new IllegalStateException("Insufficient stock");jdbc.update("update products set stock=? where id=?",after,d.productId());
            jdbc.update("insert into stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,changed_by_type,changed_by_username,order_id,reason) select id,name,?,?,?,?, 'SYSTEM','SYSTEM',?,? from products where id=?",delta<0?"ORDER_CHANGE_RESERVATION":"ORDER_CHANGE_RELEASE",delta,stock,after,c.orderId(),"order_change="+operation,d.productId());
        }
        if(reserved){int expected=(int)c.stockDeltas().stream().filter(d->d.additionalRequired()>0).count();
            if(jdbc.update("update order_edit_inventory_plans set reservation_state='CONSUMED',consumed_at=timezone('Asia/Tokyo',clock_timestamp()) where admission_id=? and operation_id=? and reservation_state='HELD'",plan.admissionId(),operation)!=expected)throw new IllegalStateException("Hold consumption mismatch");}
        var charge=c.preview().charges().getFirst();if(jdbc.update("update order_charges set name=?,amount=?,display_order=? where id=? and order_id=?",charge.name(),charge.amount(),charge.displayOrder(),charge.orderChargeId(),c.orderId())!=1)throw new IllegalStateException("Missing charge");
        finalItems.sort(Comparator.comparingLong(Item::orderItemId));var p=c.preview();return snapshots.decode(snapshots.encode(new OrderContentSnapshot(1,c.orderId(),c.customerId(),Math.incrementExact(c.revision()),finalItems,p.charges(),p.itemSubtotal(),p.chargeTotal(),p.taxAmount(),p.totalAmount())));
    }
    private long writeHistory(OrderContentSnapshot old,OrderContentSnapshot after,OrderEditAdmissionPayloadCodec.CandidatePayload c,UUID operation,boolean retained,UUID admission){
        long history=jdbc.queryForObject("select nextval(pg_get_serial_sequence('order_content_change_histories','id'))",Long.class);
        if(retained){var change=new OrderPaymentEvidence.Change(1,history,c.orderId(),operation,c.epochId(),c.headHistoryId(),c.revision(),c.beforeAmount(),c.baseFingerprint(),after,c.headDigest(),null);
            jdbc.update("""
              insert into order_content_change_histories(id,order_id,change_source,changed_by_type,changed_by_account_id,changed_by_username,
                old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount,
                before_content_revision,after_content_revision,evidence_epoch_id,previous_history_id,before_fingerprint,after_fingerprint,after_snapshot,evidence_version,evidence_digest)
              select ?,?,'CUSTOMER','USER',customer_id,actor_username,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,? from order_edit_admissions where id=?
              """,history,c.orderId(),old.itemSubtotal(),after.itemSubtotal(),old.chargeTotal(),after.chargeTotal(),old.taxAmount(),after.taxAmount(),old.totalAmount(),after.totalAmount(),c.revision(),after.contentRevision(),c.epochId(),c.headHistoryId(),hex(c.baseFingerprint()),hex(snapshots.fingerprint(after)),snapshots.encode(after),hex(OrderEvidenceDigests.change(change)),admission);
        }else jdbc.update("""
              insert into order_content_change_histories(id,order_id,change_source,changed_by_type,changed_by_account_id,changed_by_username,
                old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,old_total_amount,new_total_amount)
              select ?,?,'CUSTOMER','USER',customer_id,actor_username,?,?,?,?,?,?,?,? from order_edit_admissions where id=?
              """,history,c.orderId(),old.itemSubtotal(),after.itemSubtotal(),old.chargeTotal(),after.chargeTotal(),old.taxAmount(),after.taxAmount(),old.totalAmount(),after.totalAmount(),admission);
        historyItems(history,old,after);historyCharge(history,old.charges().getFirst(),after.charges().getFirst());return history;
    }
    private void finish(Ownership owner,UUID operation,long history,OrderContentSnapshot after){jdbc.queryForObject("select f125_edit_application_finish(?,?,?,?,?,?,?,?)",Object.class,owner.admissionId(),owner.attemptId(),owner.token(),owner.controlVersion(),UUID.randomUUID(),operation,history,hex(snapshots.fingerprint(after)));}
    private static void freshBeforeCommit(OrderAuthorizationObservation observation,OrderAuthorizationObservation.Expected target){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){observation.requireConsumed(target);}});}
    private List<OrderEditCandidate.Offer> offers(Set<Long> ids){
        var result=new ArrayList<OrderEditCandidate.Offer>();for(long id:ids)result.add(jdbc.queryForObject("""
          select p.id,p.name,c.id,c.name,p.price,t.id,t.code,t.name,t.tax_rate,(p.active and c.active and t.active)
          from products p join categories c on c.id=p.category_id join tax_categories t on t.id=p.tax_category_id where p.id=?
          """,(r,n)->new OrderEditCandidate.Offer(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getInt(5),r.getLong(6),r.getString(7),r.getString(8),OrderContentSnapshotCodec.taxRateBasisPoints(r.getBigDecimal(9)),r.getBoolean(10)),id));return result;
    }
    private long insertItem(long order,Item i){return jdbc.queryForObject("""
        insert into order_items(order_id,product_id,product_name,category_id,category_name,price,quantity,subtotal,tax_category_id,tax_category_code,tax_category_name,tax_rate)
        values (?,?,?,?,?,?,?,?,?,?,?,?) returning id
        """,Long.class,order,i.productId(),i.productName(),i.categoryId(),i.categoryName(),i.unitPrice(),i.quantity(),i.subtotal(),i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),rate(i.taxRateBasisPoints()));}
    private void historyItems(long history,OrderContentSnapshot before,OrderContentSnapshot after){
        var old=new TreeMap<Long,Item>();var now=new TreeMap<Long,Item>();before.items().forEach(i->old.put(i.orderItemId(),i));after.items().forEach(i->now.put(i.orderItemId(),i));
        var ids=new TreeSet<Long>(old.keySet());ids.addAll(now.keySet());for(long id:ids){var a=old.get(id);var b=now.get(id);if(Objects.equals(a,b))continue;var i=a==null?b:a;
            jdbc.update("""
                insert into order_content_change_items(history_id,order_item_id,product_id,product_name,change_type,old_price,new_price,old_quantity,new_quantity,old_subtotal,new_subtotal,
                  old_tax_category_id,new_tax_category_id,old_tax_category_code,new_tax_category_code,old_tax_category_name,new_tax_category_name,old_tax_rate,new_tax_rate)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,history,id,i.productId(),i.productName(),a==null?"ADDED":b==null?"REMOVED":"UPDATED",a==null?null:a.unitPrice(),b==null?null:b.unitPrice(),a==null?null:a.quantity(),b==null?null:b.quantity(),a==null?null:a.subtotal(),b==null?null:b.subtotal(),a==null?null:a.taxCategoryId(),b==null?null:b.taxCategoryId(),a==null?null:a.taxCategoryCode(),b==null?null:b.taxCategoryCode(),a==null?null:a.taxCategoryName(),b==null?null:b.taxCategoryName(),a==null?null:rate(a.taxRateBasisPoints()),b==null?null:rate(b.taxRateBasisPoints()));}
    }
    private void historyCharge(long history,Charge a,Charge b){if(a.equals(b))return;jdbc.update("""
        insert into order_content_change_charges(history_id,order_charge_id,charge_type,change_type,old_name,new_name,old_amount,new_amount,
          old_tax_category_id,new_tax_category_id,old_tax_category_code,new_tax_category_code,old_tax_category_name,new_tax_category_name,old_tax_rate,new_tax_rate,old_display_order,new_display_order)
        values (?,?,'SHIPPING','UPDATED',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,history,a.orderChargeId(),a.name(),b.name(),a.amount(),b.amount(),a.taxCategoryId(),b.taxCategoryId(),a.taxCategoryCode(),b.taxCategoryCode(),a.taxCategoryName(),b.taxCategoryName(),rate(a.taxRateBasisPoints()),rate(b.taxRateBasisPoints()),a.displayOrder(),b.displayOrder());}
    private OrderAuthorizationObservation.Expected expected(JdbcOrderPaymentEvidenceReader.Read r){var a=r.facts().authorizations().stream().filter(x->x.id()==r.decision().authorizationId()).findFirst().orElseThrow();return new OrderAuthorizationObservation.Expected(r.facts().current().orderId(),a.paymentId(),a.id(),a.flowId(),a.amount(),a.revision(),r.localFingerprint(),connection,a.operationId());}
    private static Item withId(Item i,long id){return new Item(id,i.productId(),i.productName(),i.categoryId(),i.categoryName(),i.unitPrice(),i.quantity(),i.subtotal(),i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),i.taxRateBasisPoints());}
    private static BigDecimal rate(int basis){return BigDecimal.valueOf(basis,2);}
    private static byte[] hex(String s){return HexFormat.of().parseHex(s);}
    private static void verified(JdbcOrderPaymentEvidenceReader.Read r){if(r==null||r.state()!=JdbcOrderPaymentEvidenceReader.State.READ||!r.decision().locallyVerified())throw new IllegalStateException("Invalid saved evidence");}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Must run outside caller transaction");}
}
