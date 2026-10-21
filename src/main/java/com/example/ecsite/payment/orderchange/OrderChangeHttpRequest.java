package com.example.ecsite.payment.orderchange;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;

/** Exact non-secret request data. Neither construction nor restoration grants POST permission. */
public final class OrderChangeHttpRequest {
    private final CaptureConnectionIdentity connection;
    private final UUID operationId;
    private final long orderId;
    private final int amount;
    private final String key,flowId;
    private final PaymentTransactionPurpose purpose;
    private final byte[] body,fingerprint;
    private OrderChangeHttpRequest(CaptureConnectionIdentity connection,UUID operationId,long orderId,int amount,String key,
            PaymentTransactionPurpose purpose,String flowId){
        if(connection==null||operationId==null||orderId<=0||amount<50||amount>9999999||key==null||!key.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid order change request identity");
        boolean create=purpose==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION;
        if(!create&&purpose!=PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL&&purpose!=PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT)
            throw new IllegalArgumentException("Unsupported order change purpose");
        if(create ? flowId!=null : flowId==null||!flowId.matches("pfw_[A-Za-z0-9_-]{1,251}"))
            throw new IllegalArgumentException("Invalid order change Flow identity");
        this.connection=connection;this.operationId=operationId;this.orderId=orderId;this.amount=amount;this.key=key;this.purpose=purpose;this.flowId=flowId;
        body=(create ? "{\"amount\":"+amount+",\"currency\":\"jpy\",\"payment_method_types\":[\"card\"],\"capture_method\":\"manual\",\"confirm\":false,\"metadata\":{\"order_id\":\""+orderId+"\",\"order_change_operation_id\":\""+operationId+"\"}}"
            : "{\"cancellation_reason\":\"requested_by_customer\"}").getBytes(StandardCharsets.UTF_8);
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:List.of("ec-site/order-change-request/v1","1",connection.connectionId(),connection.providerAccountRef(),connection.mode(),connection.apiOrigin(),
                    operationId.toString(),Long.toString(orderId),purpose.name(),Integer.toString(amount),key,flowId==null?"":flowId,method(),path(),contentType()))
                add(digest,value.getBytes(StandardCharsets.UTF_8));
            add(digest,body);fingerprint=digest.digest();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
    }
    public static OrderChangeHttpRequest create(CaptureConnectionIdentity c,UUID op,long order,int amount,String key){
        return new OrderChangeHttpRequest(c,op,order,amount,key,PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION,null);
    }
    public static OrderChangeHttpRequest cancel(CaptureConnectionIdentity c,UUID op,long order,int amount,String key,String flow,boolean abortNew){
        return new OrderChangeHttpRequest(c,op,order,amount,key,abortNew?PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT:PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL,flow);
    }
    public static OrderChangeHttpRequest restore(CaptureConnectionIdentity c,UUID op,long order,int amount,String key,PaymentTransactionPurpose purpose,String flow,
            String method,String path,String contentType,int bodyVersion,byte[] body,int hashVersion,byte[] fingerprint){
        var expected=new OrderChangeHttpRequest(c,op,order,amount,key,purpose,flow);
        if(bodyVersion!=1||hashVersion!=1||!expected.method().equals(method)||!expected.path().equals(path)||!expected.contentType().equals(contentType)
                ||!Arrays.equals(body,expected.body)||fingerprint==null||!MessageDigest.isEqual(fingerprint,expected.fingerprint))
            throw new IllegalArgumentException("Stored order change request integrity check failed");
        return expected;
    }
    private static void add(MessageDigest digest,byte[] value){digest.update(ByteBuffer.allocate(4).putInt(value.length).array());digest.update(value);}
    public CaptureConnectionIdentity connection(){return connection;}
    public UUID operationId(){return operationId;}
    public long orderId(){return orderId;}
    public int amount(){return amount;}
    public String idempotencyKey(){return key;}
    public String flowId(){return flowId;}
    public PaymentTransactionPurpose purpose(){return purpose;}
    public String kind(){return purpose==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION?"CREATE":purpose==PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL?"OLD_CANCEL":"NEW_ABORT";}
    public String transactionType(){return flowId==null?"AUTHORIZE":"CANCEL";}
    public String method(){return "POST";}
    public String path(){return flowId==null?"/v2/payment_flows":"/v2/payment_flows/"+flowId+"/cancel";}
    public String contentType(){return "application/json";}
    public byte[] body(){return body.clone();}
    public byte[] fingerprint(){return fingerprint.clone();}
    @Override public String toString(){return "OrderChangeHttpRequest[redacted]";}
}
