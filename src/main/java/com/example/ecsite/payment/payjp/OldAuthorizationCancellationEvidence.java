package com.example.ecsite.payment.payjp;

import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import tools.jackson.databind.JsonNode;

/** Pure, old-authorization cancellation evidence. Never authorizes a POST or NEW_ABORT closure.
 * Keeps only checked scalars, never the response body/client secret. Null received is not normalized. */
public final class OldAuthorizationCancellationEvidence {
    private final String flowId;
    private final long amount;
    private final Long received;
    private final long capturable;
    private final CaptureConnectionIdentity connection;
    private final boolean valid;
    private OldAuthorizationCancellationEvidence(String flowId,long amount,Long received,long capturable,
            CaptureConnectionIdentity connection,boolean valid){
        this.flowId=flowId;this.amount=amount;this.received=received;this.capturable=capturable;this.connection=connection;this.valid=valid;
    }
    public static OldAuthorizationCancellationEvidence fromJson(JsonNode body,CaptureConnectionIdentity connection){
        boolean valid=body!=null&&body.isObject()&&connection!=null;
        for(String key:java.util.List.of("id","status","currency","capture_method"))
            valid=valid&&body.get(key)!=null&&body.get(key).isString();
        if(!valid)return new OldAuthorizationCancellationEvidence(null,0,null,0,connection,false);
        var amount=body.get("amount");var received=body.get("amount_received");var capturable=body.get("amount_capturable");var mode=body.get("livemode");
        valid=integer(amount)&&amount.longValue()>0&&integer(capturable)&&received!=null
            &&(received.isNull()||(integer(received)&&received.longValue()==0))
            &&mode!=null&&mode.isBoolean()&&mode.booleanValue()==connection.mode().equals("LIVE")
            &&"canceled".equals(body.get("status").stringValue())&&"jpy".equals(body.get("currency").stringValue())
            &&"manual".equals(body.get("capture_method").stringValue());
        if(!valid)return new OldAuthorizationCancellationEvidence(null,0,null,0,connection,false);
        return new OldAuthorizationCancellationEvidence(body.get("id").stringValue(),amount.longValue(),
            received.isNull()?null:received.longValue(),capturable.longValue(),connection,true);
    }
    private static boolean integer(JsonNode node){return node!=null&&node.isIntegralNumber()&&node.canConvertToLong();}
    public boolean matches(String expectedFlow,int expectedAmount,CaptureConnectionIdentity expectedConnection){
        return valid&&flowId.equals(expectedFlow)&&amount==expectedAmount&&connection.equals(expectedConnection)
            &&(capturable==0||capturable==expectedAmount)&&(received==null||received==0);
    }
    @Override public String toString(){return "OldAuthorizationCancellationEvidence[redacted]";}
}
