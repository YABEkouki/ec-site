package com.example.ecsite.service.order.evidence;

import java.util.*;

/** Internal closure identity and immutable evidence. These are not financial execution permits. */
public final class OrderUnappliedClosure {
    private OrderUnappliedClosure(){}
    public record Command(UUID admissionId,UUID requestId,long expectedVersion,String terminal,String reason){
        public Command {if(admissionId==null||requestId==null||expectedVersion<0||terminal==null||!Set.of("CLOSED_UNAPPLIED","FAILED_UNAPPLIED").contains(terminal)
            ||reason==null||reason.isBlank()||reason.length()>80)throw new IllegalArgumentException("Invalid closure command");}
    }
    public record Evidence(UUID admissionId,UUID requestId,long requestVersion,String terminal,String reason,String audit,byte[] canonical,byte[] digest){
        public Evidence{canonical=canonical.clone();digest=digest.clone();}
        @Override public byte[] canonical(){return canonical.clone();}
        @Override public byte[] digest(){return digest.clone();}
        public boolean matches(Command c){return admissionId.equals(c.admissionId())&&requestId.equals(c.requestId())&&requestVersion==c.expectedVersion()&&terminal.equals(c.terminal())&&reason.equals(c.reason());}
    }
}
