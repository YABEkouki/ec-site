package com.example.ecsite.service.payment.safety.payload;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** RFC 4122 UUIDv5 addresses under a frozen V1 namespace, not security/content hashes.
 * All methods are clock-free. Event time/actor/outcome never enter an identity slot.
 * SHA-256 AuditEventDigestV1 separately binds business content for conflict checking.
 */
public final class ObservationEventIdsV1 {
    public static final int FORMAT_VERSION=1;
    public static final UUID NAMESPACE=UUID.fromString("ec12b9a7-42e5-5f57-95fa-d523ca5329a3");
    private ObservationEventIdsV1() {}
    public static UUID runStarted(UUID run){return id(run,"STARTED");}
    public static UUID runFinished(UUID run){return id(run,"FINISHED");}
    public static UUID orderStarted(UUID run,long target,int attempt){return order(run,target,attempt,"ORDER_STARTED");}
    public static UUID orderFinished(UUID run,long target,int attempt){return order(run,target,attempt,"ORDER_FINISHED");}
    public static UUID leaseTaken(UUID run,long newEpoch){if(newEpoch<2)throw PayloadEncodingV1.invalid();return id(run,"LEASE_TAKEN/"+newEpoch);}
    public static UUID paused(UUID run,UUID control){return control(run,control,"PAUSED");}
    public static UUID resumed(UUID run,UUID control){return control(run,control,"RESUMED");}
    private static UUID control(UUID run,UUID control,String type){if(control==null)throw PayloadEncodingV1.invalid();return id(run,type+"/"+control);}
    private static UUID order(UUID run,long target,int attempt,String type){if(target<=0 || attempt<=0)throw PayloadEncodingV1.invalid();return id(run,type+"/"+target+"/"+attempt);}
    private static UUID id(UUID run,String slot) {
        if(run==null)throw PayloadEncodingV1.invalid();
        try {
            var digest=MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(NAMESPACE.getMostSignificantBits()).putLong(NAMESPACE.getLeastSignificantBits()).array());
            byte[] bytes=digest.digest(("v1/run/"+run+"/"+slot).getBytes(StandardCharsets.UTF_8));
            bytes[6]=(byte)((bytes[6]&15)|0x50);bytes[8]=(byte)((bytes[8]&63)|0x80);
            var buffer=ByteBuffer.wrap(bytes);return new UUID(buffer.getLong(),buffer.getLong());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("UUID digest unavailable");}
    }
}
