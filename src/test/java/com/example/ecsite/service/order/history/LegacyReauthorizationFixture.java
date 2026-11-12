package com.example.ecsite.service.order.history;

import java.util.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.OrderChangeHttpRequest;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.Feature125EvidenceFixture.*;

final class LegacyReauthorizationFixture {
    static final UUID OP=UUID.fromString("90000000-0000-0000-0000-000000000001");
    final LegacyOrderChangeFixture legacy=new LegacyOrderChangeFixture(false,false,false);
    LegacyReauthorizationFixture(){
        legacy.edit(EvidenceSet.HISTORIES,0,"reason","数量増加・再与信");
        for(var p:Map.<String,Object>of("old_item_subtotal",6000,"new_item_subtotal",12000,"old_total_amount",6000,"new_total_amount",12000,"old_tax_amount",545,"new_tax_amount",1090).entrySet())legacy.edit(EvidenceSet.HISTORIES,0,p.getKey(),p.getValue());
        for(var p:Map.<String,Object>of("old_quantity",1,"new_quantity",2,"old_subtotal",6000,"new_subtotal",12000).entrySet())legacy.edit(EvidenceSet.ITEM_CHANGES,0,p.getKey(),p.getValue());
        var op=row("id",OP,"order_id",1,"customer_id",2,"submission_key",OP,"request_digest","a".repeat(64),"content_change_history_id",100,"state","COMPLETED","version",10,"base_content_revision",0,"old_payment_id",1000,"new_payment_id",1001,"before_amount",6000,"after_amount",12000,"item_subtotal",12000,"charge_total",0,"tax_amount",1090,
            "shipping_name","送料・梱包料","shipping_amount",0,"shipping_display_order",10,"shipping_tax_category_id",1,"shipping_tax_category_code","STANDARD","shipping_tax_category_name","標準税率","shipping_tax_rate","10.00",
            "accepted_at","2026-01-01T08:00:00","deadline_at","2026-01-02T14:00:00","grace_until","2026-01-02T14:10:00","switch_decided_at","2026-01-01T08:05:00",
            "execution_version",null,"execution_route",null,"base_evidence_epoch_id",null,"base_evidence_history_id",null,"old_authorization_transaction_id",null,"old_authorized_amount",null,"old_authorization_revision",null,"application_xid",null);
        legacy.add(EvidenceSet.OPERATIONS,binary(op,"admission_observation_payload",null,"admission_observation_fingerprint",null));
        legacy.add(EvidenceSet.OPERATION_ITEMS,row("operation_id",OP,"order_id",1,"order_item_id",10,"product_id",5,"before_quantity",1,"after_quantity",2,"increase_quantity",1,"unit_price",6000,"before_subtotal",6000,"after_subtotal",12000,"tax_category_id",1,"tax_category_code","STANDARD","tax_category_name","標準税率","tax_rate","10.00"));
        legacy.add(EvidenceSet.RESERVATIONS,row("operation_id",OP,"order_item_id",10,"product_id",5,"quantity",1,"state","CONSUMED","hold_movement_id",1,"consumed_at","2026-01-01T09:00:00"));
        legacy.add(EvidenceSet.PAYMENTS,row("id",1000,"order_id",1,"provider","PAYJP","payment_method","CARD","status","CAPTURED","amount",6000,"provider_payment_id","pfw_legacy_old"));
        legacy.add(EvidenceSet.PAYMENTS,row("id",1001,"order_id",1,"provider","PAYJP","payment_method","CARD","status","CANCELLED","amount",12000,"provider_payment_id","pfw_legacy_new"));
        legacy.add(EvidenceSet.TRANSACTIONS,transaction(2000,1000,6000,0,"AUTHORIZE","INITIAL_ORDER_AUTHORIZATION",null,"pfw_legacy_old","initial"));
        legacy.add(EvidenceSet.TRANSACTIONS,transaction(2001,1001,12000,1,"AUTHORIZE","ORDER_CHANGE_AUTHORIZATION",OP,"pfw_legacy_new","create"));
        legacy.add(EvidenceSet.TRANSACTIONS,transaction(2002,1000,6000,0,"CANCEL","ORDER_CHANGE_OLD_AUTH_CANCEL",OP,"pfw_legacy_old","cancel"));
        var c=new CaptureConnectionIdentity("saved-connection","saved-account","TEST","https://api.pay.jp");
        request(3000,1001,2001,OrderChangeHttpRequest.create(c,OP,1,12000,"create"));request(3001,1000,2002,OrderChangeHttpRequest.cancel(c,OP,1,6000,"cancel","pfw_legacy_old",false));
    }
    static EvidenceRow transaction(int id,int payment,int amount,int rev,String type,String purpose,UUID operation,String flow,String key){return row("id",id,"payment_id",payment,"transaction_type",type,"status","SUCCEEDED","amount",amount,"order_content_revision",rev,"purpose",purpose,"order_change_operation_id",operation,"order_change_order_id",operation==null?null:1,"provider_transaction_id",flow,"idempotency_key",key,"completed_at","2026-01-01T08:05:00");}
    void request(int id,int payment,int tx,OrderChangeHttpRequest r){
        var c=r.connection();legacy.add(EvidenceSet.FINANCIAL_REQUESTS,binary(row("id",id,"operation_id",OP,"order_id",1,"payment_id",payment,"transaction_id",tx,"amount",r.amount(),"purpose",r.purpose(),"request_kind",r.kind(),"transaction_type",r.transactionType(),"provider_flow_id",r.flowId(),"connection_id",c.connectionId(),"provider_account_ref",c.providerAccountRef(),"mode",c.mode(),"api_origin",c.apiOrigin(),"idempotency_key",r.idempotencyKey(),"http_method",r.method(),"api_path",r.path(),"content_type",r.contentType(),"body_format_version",1,"fingerprint_version",1,"created_at",TIME,"replay_not_after","2026-01-01T22:00:00Z"),"request_body",r.body(),"request_fingerprint",r.fingerprint()));
        legacy.add(EvidenceSet.DISPATCHES,row("request_id",id,"state","OBSERVED","created_at",TIME,"started_at",TIME,"observed_at",TIME));
    }
    void recovery(String result){
        boolean applied=result.equals("COMPLETED")||result.equals("COMMIT_PENDING");
        legacy.add(EvidenceSet.RECOVERY_AUDITS,binary(row("request_id",UUID.fromString("90000000-0000-0000-0000-000000000002"),"order_id",1,"operation_id",OP,"old_cancel_request_id",3001,"actor_id",9,"actor_username","saved-admin","reason_code","VERIFY_OLD_CANCEL","original_reason","CANCEL_FLOW_EVIDENCE_MISMATCH","expected_version",8,"applied_version",applied?9:null,"completed_version",result.equals("COMPLETED")?10:null,"old_flow_id","pfw_legacy_old","new_flow_id","pfw_legacy_new","evidence_format",1,"result_code",result,"content_change_history_id",result.equals("COMPLETED")?100:null,"started_at",TIME,"updated_at",TIME,"finished_at",result.equals("STARTED")?null:TIME),"context_digest",new byte[32],"evidence_digest",applied?new byte[32]:null));
    }
    OrderChangeEvidenceBundle bundle(){return legacy.bundle();}
}
