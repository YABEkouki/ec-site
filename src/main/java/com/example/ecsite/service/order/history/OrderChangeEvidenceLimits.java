package com.example.ecsite.service.order.history;

/** Application acquisition bounds; not a promise about the number of confirmed changes. */
public record OrderChangeEvidenceLimits(int parents,int children,int totalRows,long rawBytes,int snapshotBytes) {
    public static final OrderChangeEvidenceLimits DEFAULT=new OrderChangeEvidenceLimits(1000,10000,20000,16L*1024*1024,2*1024*1024);
    public OrderChangeEvidenceLimits {
        if(parents<1||children<1||totalRows<1||rawBytes<1||snapshotBytes<1||parents==Integer.MAX_VALUE||children==Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid evidence bounds");
    }
}
