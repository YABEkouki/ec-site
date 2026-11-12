package com.example.ecsite.service.order.history;

import java.util.*;
import java.time.*;
import com.example.ecsite.service.order.evidence.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceChecks.*;

/** Independent synthetic saved receipts; no Spring, database or gateway. */
final class Feature125EvidenceFixture {
    static final UUID AD=UUID.fromString("10000000-0000-0000-0000-000000000001"), OP=UUID.fromString("20000000-0000-0000-0000-000000000001"),
        GEN=UUID.fromString("30000000-0000-0000-0000-000000000001"), EPOCH=UUID.fromString("40000000-0000-0000-0000-000000000001"),
        NEXT=UUID.fromString("40000000-0000-0000-0000-000000000002"), ATT=UUID.fromString("50000000-0000-0000-0000-000000000001");
    static final String TIME="2026-01-01T00:00:00Z", DEADLINE="2026-01-02T00:00:00Z";
    static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    static final OrderContentSnapshotCodec CODEC=new OrderContentSnapshotCodec();
    final EnumMap<EvidenceSet,List<EvidenceRow>> sets=new EnumMap<>(EvidenceSet.class);
    final OrderContentSnapshot before,after;
    final boolean reauth;
    Feature125EvidenceFixture(boolean reauth){this(reauth,false);}
    Feature125EvidenceFixture(boolean reauth,boolean replacement){this(reauth,replacement,false);}
    Feature125EvidenceFixture(boolean reauth,boolean replacement,boolean noOp){this(reauth,replacement,noOp,false,false);}
    Feature125EvidenceFixture(boolean reauth,boolean replacement,boolean noOp,boolean checkpoint,boolean corruptCheckpoint){this(reauth,replacement,noOp,checkpoint,corruptCheckpoint,11000);}
    Feature125EvidenceFixture(boolean reauth,boolean replacement,boolean noOp,boolean checkpoint,boolean corruptCheckpoint,int admittedAuth){
        this.reauth=reauth;for(var s:EvidenceSet.values())sets.put(s,new ArrayList<>());
        before=snapshot(0,10,5,5500,2);
        after=replacement?snapshot(1,20,6,11000,1):snapshot(1,10,5,5500,noOp?2:reauth?3:1);
        add(EvidenceSet.PAYMENTS,row("id",30,"order_id",1,"provider","PAYJP","payment_method","CARD","status","CAPTURED","amount",11000,"provider_payment_id","pfw_old"));
        add(EvidenceSet.TRANSACTIONS,authorization(31,30,11000,0,null,"pfw_old"));
        var receipt=checkpoint?checkpointReceipt(corruptCheckpoint):null;
        var base=new OrderPaymentEvidence.Epoch(1,EPOCH,1,30,31,11000,0,checkpoint?OrderPaymentEvidence.Origin.LEGACY_CURRENT_CHECKPOINT:OrderPaymentEvidence.Origin.INITIAL_AUTH,before,null,checkpoint?new OrderCheckpointReceiptCodec().checkpoint(receipt):null,null);
        String ed=OrderEvidenceDigests.epoch(base);var baseRow=epoch(EPOCH,30,31,before,checkpoint?"LEGACY_CURRENT_CHECKPOINT":"INITIAL_AUTH",null,null,null,ed);
        if(checkpoint){var raw=new OrderCheckpointReceiptCodec().encode(receipt);baseRow=binary(baseRow,"observation_payload",raw,"observation_fingerprint",hash(raw));}add(EvidenceSet.EPOCHS,baseRow);
        var preview=new OrderContentSnapshot(1,1,2,0,replacement?List.of(new OrderContentSnapshot.Item(1,6,"商品",1,"分類",11000,1,11000,1,"ZERO","非課税",0)):after.items(),after.charges(),after.itemSubtotal(),0,0,after.totalAmount());
        var request=new OrderEditCandidate.Request(1,0,List.of(new OrderEditCandidate.Line(replacement?null:10L,replacement?6:5,replacement?1:noOp?2:reauth?3:1)));
        var payloads=new OrderEditAdmissionPayloadCodec();byte[] req=payloads.request(2,request);
        var candidate=new OrderEditAdmissionPayloadCodec.CandidatePayload(1,"ORDER_EDIT_ADMISSION_CANDIDATE",1,2,0,EPOCH,null,CODEC.fingerprint(before),ed,ed,30,31,admittedAuth,0,11000,"pfw_old",preview,
            replacement?List.of(new OrderEditCandidate.NewLineReference(1,6)):List.of(),
            replacement?List.of(new OrderEditCandidate.Offer(6,"商品",1,"分類",11000,1,"ZERO","非課税",0,true)):List.of(),
            replacement?List.of(new OrderEditCandidate.StockDelta(5,2,0),new OrderEditCandidate.StockDelta(6,0,1)):List.of(new OrderEditCandidate.StockDelta(5,2,noOp?2:reauth?3:1)));
        byte[] cp=JSON.writeValueAsBytes(candidate);
        var adm=row("id",AD,"order_id",1,"customer_id",2,"actor_username","customer","submission_key",OP,"route",reauth?"REAUTHORIZE":"RETAIN_AUTH",
            "admission_state","ACCEPTED","execution_state","COMPLETED","closed_at",TIME,"version",4,"reason_code","APPLICATION_COMPLETED",
            "applied_operation_id",OP,"request_version",1,"candidate_version",1,"base_content_revision",0,"base_evidence_epoch_id",EPOCH,"base_evidence_history_id",null,
            "base_payment_id",30,"base_authorization_id",31,"authorized_amount",admittedAuth,"authorization_revision",0,"before_amount",11000,"after_amount",after.totalAmount(),
            "deadline_at",DEADLINE,"original_xid",10,"db_generation_id",GEN,"scope","ISOLATED_TEST","financial_snapshot_version",1,"unapplied_baseline","{}");
        adm=binary(adm,"request_payload",req,"request_digest",hash(req),"candidate_payload",cp,"candidate_digest",hash(cp),"base_fingerprint",hash(CODEC.encode(before)));
        var fc=new OrderAdmissionFinancialSnapshotCodec();
        var fs=new OrderAdmissionFinancialSnapshot(fc.DOMAIN,1,AD,1L,2L,GEN,"10",hex(hash(req)),hex(hash(cp)),0,"ORDERED",micros(Instant.parse(DEADLINE)),EPOCH,null,CODEC.fingerprint(before),ed,ed,30L,11000,30L,31L,admittedAuth,0,
            List.of(new OrderAdmissionFinancialSnapshot.Payment(30L,1L,"PAYJP","CARD","AUTHORIZED",admittedAuth,"pfw_old")),
            List.of(new OrderAdmissionFinancialSnapshot.Transaction(31L,30L,"AUTHORIZE","SUCCEEDED",admittedAuth,0,"INITIAL_ORDER_AUTHORIZATION",null,"pfw_old","2026-01-01T00:00:00.000000")),List.of(),List.of(),List.of(),List.of());
        adm=scalar(adm,"financial_snapshot",fc.audit(fs));adm=binary(adm,"financial_canonical",fc.canonical(fs),"financial_digest",fc.digest(fs));add(EvidenceSet.ADMISSIONS,adm);
        var binding=new LinkedHashMap<String,Object>();String[] names={"domain","version","admissionId","orderId","dbGeneration","originalXid","verificationXid","requestDigest","candidateDigest","snapshotVersion","snapshotDigest","originalCommit","deadline","statusBefore","statusAfter","decision","guardVersion"};
        Object[] vals={fc.BINDING_DOMAIN,1,AD,1,GEN,"10","11",hex(hash(req)),hex(hash(cp)),1,hex(fc.digest(fs)),micros(Instant.parse(TIME)),micros(Instant.parse(DEADLINE)),"committed","committed","ACCEPTED",1};
        for(int i=0;i<names.length;i++)binding.put(names[i],vals[i]);String bindingJson=JSON.writeValueAsString(binding);
        add(EvidenceSet.ADMISSION_PROOFS,binary(row("admission_id",AD,"original_xid",10,"verification_xid",11,"original_commit_at",TIME,"deadline_at",DEADLINE,"db_generation_id",GEN,"scope","ISOLATED_TEST","status_before","committed","status_after","committed","decision","ACCEPTED","guard_version",1,"financial_binding",bindingJson),"request_digest",hash(req),"candidate_digest",hash(cp),"financial_binding_canonical",fc.binding(bindingJson),"financial_binding_digest",fc.bindingDigest(bindingJson)));
        add(EvidenceSet.GENERATIONS,row("id",GEN,"scope","ISOLATED_TEST"));
        add(EvidenceSet.CONTROLS,binary(row("admission_id",AD,"order_id",1,"db_generation_id",GEN,"active_attempt_id",null,"lease_until",null,"version",2,"fencing_token",2,"get_attempt_count",1,"baseline","{}"),"baseline_digest",hash("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        add(EvidenceSet.ATTEMPTS,binary(row("id",ATT,"admission_id",AD,"order_id",1,"db_generation_id",GEN,"fencing_token",1,"control_version",1),"baseline_digest",hash("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        add(EvidenceSet.RESULTS,binary(row("id",UUID.fromString("60000000-0000-0000-0000-000000000001"),"attempt_id",ATT,"admission_id",AD,"order_id",1,"fencing_token",1,"control_version",1,"recovery",false,"disposition","APPLIED","reason_code","APPLICATION_COMPLETED","application_version",1,"application_operation_id",OP,"application_history_id",100,"application_before_revision",0,"application_after_revision",1,"recording_xid",12,"recorded_at",TIME),"application_fingerprint",hash(CODEC.encode(after)),"local_digest",hash(new byte[]{2})));
        String[] executions={"NOT_READY","QUEUED","OBSERVING","APPLYING","COMPLETED"};
        for(int i=0;i<5;i++)add(EvidenceSet.EVENTS,row("admission_id",AD,"event_no",i,"event_type",i==0?"RECORDED":i==1?"ADMISSION_DECIDED":"STATE_CHANGED","before_admission",i==0?null:i==1?"RECORDED_UNVERIFIED":"ACCEPTED","before_execution",i==0?null:executions[i-1],"after_admission",i==0?"RECORDED_UNVERIFIED":"ACCEPTED","after_execution",executions[i],"reason_code",i==4?"APPLICATION_COMPLETED":"RECORDED","recording_xid",i==0?10:i==1?11:12,"recorded_at",TIME));
        var op=row("id",OP,"order_id",1,"customer_id",2,"submission_key",OP,"request_digest",hex(hash(req)),"state","COMPLETED","execution_version",1,"execution_route",reauth?"REAUTHORIZE":"RETAIN_AUTH","application_xid",12,"content_change_history_id",100,"base_content_revision",0,"base_evidence_epoch_id",EPOCH,"base_evidence_history_id",null,"old_payment_id",30,"new_payment_id",reauth?40:null,"old_authorization_transaction_id",31,"old_authorized_amount",admittedAuth,"old_authorization_revision",0,"before_amount",11000,"after_amount",after.totalAmount(),"item_subtotal",after.itemSubtotal(),"charge_total",0,"tax_amount",0,"shipping_name","送料・梱包料","shipping_amount",0,"shipping_display_order",10,"shipping_tax_category_id",1,"shipping_tax_category_code","ZERO","shipping_tax_category_name","非課税","shipping_tax_rate","0.00");
        op=binary(op,"admission_observation_payload",observation("pfw_old",admittedAuth,null),"admission_observation_fingerprint",hash(observation("pfw_old",admittedAuth,null)));add(EvidenceSet.OPERATIONS,op);
        var h=row("id",100,"order_id",1,"change_source","CUSTOMER","changed_by_type","USER","changed_by_account_id",2,"changed_by_username","customer","reason",null,
            "old_item_subtotal",11000,"new_item_subtotal",after.itemSubtotal(),"old_charge_total",0,"new_charge_total",0,"old_tax_amount",0,"new_tax_amount",0,"old_total_amount",11000,"new_total_amount",after.totalAmount(),
            "evidence_version",reauth?null:1,"before_content_revision",reauth?null:0,"after_content_revision",reauth?null:1,"evidence_epoch_id",reauth?null:EPOCH,"previous_history_id",null);
        var change=new OrderPaymentEvidence.Change(1,100,1,OP,EPOCH,null,0,11000,CODEC.fingerprint(before),after,ed,null);
        h=binary(h,"before_fingerprint",reauth?null:hash(CODEC.encode(before)),"after_fingerprint",reauth?null:hash(CODEC.encode(after)),"after_snapshot",reauth?null:CODEC.encode(after),"evidence_digest",reauth?null:HexFormat.of().parseHex(OrderEvidenceDigests.change(change)));add(EvidenceSet.HISTORIES,h);
        if(replacement){add(EvidenceSet.ITEM_CHANGES,itemDiff(1,before.items().getFirst(),null));add(EvidenceSet.ITEM_CHANGES,itemDiff(2,null,after.items().getFirst()));}
        else add(EvidenceSet.ITEM_CHANGES,itemDiff(1,before.items().getFirst(),after.items().getFirst()));
        if(reauth){
            add(EvidenceSet.PAYMENTS,row("id",40,"order_id",1,"provider","PAYJP","payment_method","CARD","status","CAPTURED","amount",after.totalAmount(),"provider_payment_id","pfw_new"));
            add(EvidenceSet.TRANSACTIONS,authorization(41,40,after.totalAmount(),1,OP,"pfw_new"));
            add(EvidenceSet.TRANSACTIONS,row("id",32,"payment_id",30,"transaction_type","CANCEL","status","SUCCEEDED","amount",11000,"order_content_revision",0,"purpose","ORDER_CHANGE_OLD_AUTH_CANCEL","order_change_operation_id",OP,"order_change_order_id",1,"provider_transaction_id","pfw_old","completed_at","2026-01-01T00:00:00"));
            var rp=new OrderPaymentEvidence.Reauthorization(OP,1,30,40,41,100,0,after.totalAmount(),"COMPLETED",32,"SUCCEEDED",11000,0,"pfw_old");
            var next=new OrderPaymentEvidence.Epoch(1,NEXT,1,40,41,after.totalAmount(),1,OrderPaymentEvidence.Origin.REAUTH_COMPLETED,after,rp,null,null);
            var nextEpoch=epoch(NEXT,40,41,after,"REAUTH_COMPLETED",OP,100,EPOCH,OrderEvidenceDigests.epoch(next));
            nextEpoch=binary(nextEpoch,"observation_payload",observation("pfw_new",after.totalAmount(),OP),"observation_fingerprint",hash(observation("pfw_new",after.totalAmount(),OP)));add(EvidenceSet.EPOCHS,nextEpoch);
            financialRequest(71,41,40,after.totalAmount(),true);financialRequest(72,32,30,11000,false);
        }
    }
    OrderCheckpointReceiptCodec.Receipt checkpointReceipt(boolean corrupt){
        var order=new LinkedHashMap<String,Object>();order.put("id",1);order.put("user_id",2);order.put("content_revision",0);order.put("item_subtotal",11000);order.put("charge_total",0);order.put("tax_amount",0);order.put("total_amount",11000);order.put("active_payment_id",corrupt?99:30);order.put("current_evidence_epoch_id",null);
        var item=Map.<String,Object>ofEntries(Map.entry("id",10),Map.entry("product_id",5),Map.entry("product_name","商品"),Map.entry("category_id",1),Map.entry("category_name","分類"),Map.entry("price",5500),Map.entry("quantity",2),Map.entry("subtotal",11000),Map.entry("tax_category_id",1),Map.entry("tax_category_code","ZERO"),Map.entry("tax_category_name","非課税"),Map.entry("tax_rate",0));
        var charge=Map.<String,Object>ofEntries(Map.entry("id",1),Map.entry("charge_type","SHIPPING"),Map.entry("name","送料・梱包料"),Map.entry("amount",0),Map.entry("tax_category_id",1),Map.entry("tax_category_code","ZERO"),Map.entry("tax_category_name","非課税"),Map.entry("tax_rate",0),Map.entry("display_order",10));
        var auth=new LinkedHashMap<String,Object>();auth.put("id",31);auth.put("order_change_operation_id",null);
        String local=JSON.writeValueAsString(Map.of("orders",List.of(order),"items",List.of(item),"charges",List.of(charge),"transactions",List.of(auth)));
        String obs=new String(observation("pfw_old",11000,null),java.nio.charset.StandardCharsets.UTF_8);
        return new OrderCheckpointReceiptCodec.Receipt(1,1,30,31,0,CODEC.fingerprint(before),local,OrderCheckpointReceiptCodec.hash(local),obs,OrderCheckpointReceiptCodec.hash(obs));
    }
    static byte[] observation(String flow,int amount,UUID operation){
        var m=new LinkedHashMap<String,Object>();m.put("version",1);m.put("flowId",flow);m.put("amount",amount);m.put("amountCapturable",amount);m.put("amountReceived",0);m.put("status","requires_capture");m.put("currency","jpy");m.put("captureMethod","manual");m.put("connection",Map.of("connectionId","test","providerAccountRef","account","mode","TEST","apiOrigin","https://api.pay.jp"));m.put("liveMode",false);m.put("authorizationOperationId",operation==null?null:operation.toString());m.put("startedAt",TIME);m.put("finishedAt",TIME);m.put("expiredAt",null);return JSON.writeValueAsBytes(m);
    }
    void financialRequest(int id,int transaction,int payment,int amount,boolean create){
        var c=new com.example.ecsite.payment.capture.CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
        var r=create?com.example.ecsite.payment.orderchange.OrderChangeHttpRequest.create(c,OP,1,amount,"key"+id):com.example.ecsite.payment.orderchange.OrderChangeHttpRequest.cancel(c,OP,1,amount,"key"+id,"pfw_old",false);
        add(EvidenceSet.FINANCIAL_REQUESTS,binary(row("id",id,"operation_id",OP,"order_id",1,"payment_id",payment,"transaction_id",transaction,"amount",amount,"purpose",r.purpose(),"request_kind",r.kind(),"transaction_type",r.transactionType(),"provider_flow_id",r.flowId(),"connection_id",c.connectionId(),"provider_account_ref",c.providerAccountRef(),"mode",c.mode(),"api_origin",c.apiOrigin(),"idempotency_key",r.idempotencyKey(),"http_method",r.method(),"api_path",r.path(),"content_type",r.contentType(),"body_format_version",1,"fingerprint_version",1),"request_body",r.body(),"request_fingerprint",r.fingerprint()));
        add(EvidenceSet.DISPATCHES,row("request_id",id,"state","OBSERVED"));
        for(int i=0;i<sets.get(EvidenceSet.TRANSACTIONS).size();i++)if(sets.get(EvidenceSet.TRANSACTIONS).get(i).value("id").equals(""+transaction))edit(EvidenceSet.TRANSACTIONS,i,"idempotency_key",r.idempotencyKey());
    }
    static OrderContentSnapshot snapshot(int rev,long id,long product,int price,int qty){return new OrderContentSnapshot(1,1,2,rev,List.of(new OrderContentSnapshot.Item(id,product,"商品",1,"分類",price,qty,price*qty,1,"ZERO","非課税",0)),List.of(new OrderContentSnapshot.Charge(1,"SHIPPING","送料・梱包料",0,1,"ZERO","非課税",0,10)),price*qty,0,0,price*qty);}
    static EvidenceRow authorization(int id,int payment,int amount,int rev,UUID op,String flow){return row("id",id,"payment_id",payment,"transaction_type","AUTHORIZE","status","SUCCEEDED","amount",amount,"order_content_revision",rev,"purpose",op==null?"INITIAL_ORDER_AUTHORIZATION":"ORDER_CHANGE_AUTHORIZATION","order_change_operation_id",op,"order_change_order_id",op==null?null:1,"provider_transaction_id",flow,"completed_at","2026-01-01T00:00:00");}
    static EvidenceRow epoch(UUID id,int payment,int auth,OrderContentSnapshot s,String origin,UUID op,Integer history,UUID previous,String digest){return binary(row("id",id,"order_id",1,"payment_id",payment,"authorization_transaction_id",auth,"origin_kind",origin,"origin_operation_id",op,"origin_history_id",history,"previous_epoch_id",previous,"start_content_revision",s.contentRevision(),"start_total_amount",s.totalAmount(),"authorized_amount",s.totalAmount(),"authorization_revision",s.contentRevision(),"authorization_type","AUTHORIZE","authorization_status","SUCCEEDED","provider_flow_id",payment==30?"pfw_old":"pfw_new","evidence_version",1,"observation_started_at",TIME,"observation_finished_at",TIME),"start_snapshot",CODEC.encode(s),"start_fingerprint",hash(CODEC.encode(s)),"evidence_digest",HexFormat.of().parseHex(digest),"observation_payload",new byte[]{123,125},"observation_fingerprint",hash(new byte[]{123,125}));}
    static EvidenceRow itemDiff(int id,OrderContentSnapshot.Item a,OrderContentSnapshot.Item b){var i=a==null?b:a;return row("id",id,"history_id",100,"order_item_id",i.orderItemId(),"product_id",i.productId(),"product_name",i.productName(),"change_type",a==null?"ADDED":b==null?"REMOVED":"UPDATED","old_price",a==null?null:a.unitPrice(),"new_price",b==null?null:b.unitPrice(),"old_quantity",a==null?null:a.quantity(),"new_quantity",b==null?null:b.quantity(),"old_subtotal",a==null?null:a.subtotal(),"new_subtotal",b==null?null:b.subtotal(),"old_tax_category_id",a==null?null:a.taxCategoryId(),"new_tax_category_id",b==null?null:b.taxCategoryId(),"old_tax_category_code",a==null?null:a.taxCategoryCode(),"new_tax_category_code",b==null?null:b.taxCategoryCode(),"old_tax_category_name",a==null?null:a.taxCategoryName(),"new_tax_category_name",b==null?null:b.taxCategoryName(),"old_tax_rate",a==null?null:"0.00","new_tax_rate",b==null?null:"0.00");}
    void add(EvidenceSet set,EvidenceRow row){sets.get(set).add(row);}
    OrderChangeEvidenceBundle bundle(){return new OrderChangeEvidenceBundle(Status.COMPLETE,new OrderEvidence(1,2,1,(reauth?NEXT:EPOCH).toString(),reauth?null:100L),sets,new Coverage(sets.values().stream().mapToLong(List::size).sum(),10000),List.of());}
    void edit(EvidenceSet set,int index,String key,Object value){sets.get(set).set(index,scalar(sets.get(set).get(index),key,value));}
    static EvidenceRow row(Object... pairs){var m=new LinkedHashMap<String,String>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]==null?null:pairs[i+1].toString());return new EvidenceRow(m,Map.of());}
    static EvidenceRow scalar(EvidenceRow row,String key,Object value){var m=new LinkedHashMap<>(row.values());m.put(key,value==null?null:value.toString());return new EvidenceRow(m,row.binaries());}
    static EvidenceRow binary(EvidenceRow row,Object... pairs){var b=new LinkedHashMap<>(row.binaries());for(int i=0;i<pairs.length;i+=2)b.put(pairs[i].toString(),pairs[i+1]==null?null:new BinaryEvidence((byte[])pairs[i+1]));return new EvidenceRow(row.values(),b);}
}
