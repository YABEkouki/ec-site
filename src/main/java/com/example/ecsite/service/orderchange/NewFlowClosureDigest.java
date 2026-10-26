package com.example.ecsite.service.orderchange;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Fixed v1: 24 ordered fields, signed big-endian int32 byte length followed by UTF-8.
 * Null is length -1; empty string is length 0. Fingerprints are lowercase 64-character hex.
 * Numbers use decimal, booleans lowercase; version/times/dispatch and mutable audit data are excluded. */
final class NewFlowClosureDigest {
    private NewFlowClosureDigest(){}
    static byte[] compute(NewFlowClosureControl.Expected expected){
        var flow=expected.flow();var c=flow.connection();var abort=expected.abort().orElse(null);
        return hash(Arrays.asList("ec-site/order-change/flow-closed/v1",Long.toString(flow.orderId()),flow.operationId().toString(),
            expected.intent().requestId().toString(),Long.toString(expected.paymentId()),flow.newFlowId(),Integer.toString(flow.amount()),
            c.connectionId(),c.providerAccountRef(),c.mode(),c.apiOrigin(),Long.toString(expected.create().id()),expected.create().fingerprint(),
            Boolean.toString(abort!=null),abort==null?null:Long.toString(abort.id()),abort==null?null:abort.fingerprint(),
            "jpy","manual",Boolean.toString("LIVE".equals(c.mode())),Long.toString(flow.orderId()),flow.operationId().toString(),"canceled","0","0"));
    }
    static byte[] hash(java.util.List<String> fields){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(var field:fields){if(field==null){out.writeInt(-1);continue;}byte[] value=field.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);}
            out.flush();return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("Closure digest unavailable",failure);}
    }
}
