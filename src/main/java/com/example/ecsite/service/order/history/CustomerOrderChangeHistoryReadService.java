package com.example.ecsite.service.order.history;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import com.example.ecsite.security.CustomUserDetails;

@Service
public class CustomerOrderChangeHistoryReadService {
    private final CustomerOrderChangeEvidenceReader reader;
    public CustomerOrderChangeHistoryReadService(CustomerOrderChangeEvidenceReader reader){this.reader=reader;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public OrderChangeEvidenceBundle readForCustomer(long orderId){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof CustomUserDetails user)
                ||user.getId()==null||!auth.getAuthorities().stream().anyMatch(a->"ROLE_USER".equals(a.getAuthority())))
            throw new AccessDeniedException("Customer authentication required");
        return reader.readForCustomer(orderId,user.getId());
    }
}
