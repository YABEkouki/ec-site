package com.example.ecsite.service.payment.safety.payload;

import java.util.*;

/** Typed command binding, not authorization. Before values are NULL only where no owner/state exists. */
public record ObservationLifecycleContextV1(int lifecycleContextVersion,String commandDigest,
        UUID ownerBefore,UUID ownerAfter,Long epochBefore,long epochAfter,Long versionBefore,long versionAfter) {
    public ObservationLifecycleContextV1 {
        if(lifecycleContextVersion!=1 || commandDigest==null || !commandDigest.matches("[0-9a-f]{64}")
            || epochAfter<=0 || versionAfter<0 || (epochBefore!=null && epochBefore<=0) || (versionBefore!=null && versionBefore<0))throw PayloadEncodingV1.invalid();
    }
    void validateFor(String type,long eventEpoch) {
        if(eventEpoch!=epochAfter)throw PayloadEncodingV1.invalid();
        if("STARTED".equals(type)) {
            if(ownerBefore!=null || ownerAfter==null || epochBefore!=null || versionBefore!=null || epochAfter!=1 || versionAfter!=0)throw PayloadEncodingV1.invalid();
            return;
        }
        if(epochBefore==null || versionBefore==null)throw PayloadEncodingV1.invalid();
        try {
            if(versionAfter!=Math.addExact(versionBefore,1L))throw PayloadEncodingV1.invalid();
            switch(type) {
                case "PAUSED","FINISHED" -> {if(ownerBefore==null || ownerAfter!=null || epochAfter!=epochBefore)throw PayloadEncodingV1.invalid();}
                case "RESUMED" -> {if(ownerBefore!=null || ownerAfter==null || epochAfter!=Math.addExact(epochBefore,1L))throw PayloadEncodingV1.invalid();}
                case "LEASE_TAKEN" -> {if(ownerBefore==null || ownerAfter==null || epochAfter!=Math.addExact(epochBefore,1L))throw PayloadEncodingV1.invalid();}
                default -> throw PayloadEncodingV1.invalid();
            }
        }catch(ArithmeticException e){throw PayloadEncodingV1.invalid();}
    }
}
