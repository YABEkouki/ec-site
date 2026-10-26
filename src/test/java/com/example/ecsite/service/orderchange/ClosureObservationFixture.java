package com.example.ecsite.service.orderchange;

import java.util.Optional;
import com.example.ecsite.payment.payjp.NewFlowClosureObservation;
import com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence;

/** Test-only expected context construction; never on the production classpath. */
public final class ClosureObservationFixture {
    private ClosureObservationFixture(){}
    public static NewFlowClosureControl.Expected expected(long payment,long version,NewFlowTerminationEvidence.Expected flow,
            NewFlowClosureObservation.AbortIntent intent,NewFlowClosureObservation.RequestBinding create,
            Optional<NewFlowClosureObservation.RequestBinding> abort,NewFlowClosureObservation.FinancialSnapshot snapshot){
        return new NewFlowClosureControl.Expected(payment,version,flow,intent,create,abort,snapshot);
    }
}
