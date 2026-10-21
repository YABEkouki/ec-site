package com.example.ecsite.payment.orderchange;

import java.time.*;

/** Persisted request identity; not an initial/replay execution permit. */
public record StoredOrderChangeRequest(long id,long paymentId,long transactionId,OrderChangeHttpRequest httpRequest,
        Instant createdAt,Instant replayNotAfter){
    public StoredOrderChangeRequest{
        if(id<=0||paymentId<=0||transactionId<=0||httpRequest==null||createdAt==null||replayNotAfter==null
            ||createdAt.getNano()%1000!=0||replayNotAfter.getNano()%1000!=0||!replayNotAfter.isAfter(createdAt)
            ||Duration.between(createdAt,replayNotAfter).compareTo(Duration.ofHours(23))>0)
            throw new IllegalArgumentException("Invalid stored order change request");
    }
    public void requireReplayAt(Instant now){
        if(now==null||now.isBefore(createdAt)||!now.isBefore(replayNotAfter))
            throw new IllegalStateException("Order change POST window is closed; observation required");
    }
    @Override public String toString(){return "StoredOrderChangeRequest[id="+id+", redacted]";}
}
