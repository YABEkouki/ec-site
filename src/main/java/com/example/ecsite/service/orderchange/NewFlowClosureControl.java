package com.example.ecsite.service.orderchange;

import java.util.Objects;
import java.util.UUID;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.payjp.NewFlowClosureObservation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal observation/writer helper. Deliberately not a bean, controller or automatic worker. */
public final class NewFlowClosureControl {
    /** Opaque DB context: only this service package can construct it; no public factory/restore API. */
    public static final class Expected {
        private final long paymentId,version;
        private final com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence.Expected flow;
        private final NewFlowClosureObservation.AbortIntent intent;
        private final NewFlowClosureObservation.RequestBinding create;
        private final java.util.Optional<NewFlowClosureObservation.RequestBinding> abort;
        private final NewFlowClosureObservation.FinancialSnapshot snapshot;
        Expected(long paymentId,long version,com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence.Expected flow,
                NewFlowClosureObservation.AbortIntent intent,NewFlowClosureObservation.RequestBinding create,
                java.util.Optional<NewFlowClosureObservation.RequestBinding> abort,NewFlowClosureObservation.FinancialSnapshot snapshot){
            if(paymentId<=0||version<0)throw new IllegalArgumentException("Invalid closure target");
            this.paymentId=paymentId;this.version=version;this.flow=Objects.requireNonNull(flow);this.intent=Objects.requireNonNull(intent);
            this.create=Objects.requireNonNull(create);this.abort=Objects.requireNonNull(abort);this.snapshot=Objects.requireNonNull(snapshot);
        }
        public long paymentId(){return paymentId;}
        public long version(){return version;}
        public com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence.Expected flow(){return flow;}
        public NewFlowClosureObservation.AbortIntent intent(){return intent;}
        public NewFlowClosureObservation.RequestBinding create(){return create;}
        public java.util.Optional<NewFlowClosureObservation.RequestBinding> abort(){return abort;}
        public NewFlowClosureObservation.FinancialSnapshot snapshot(){return snapshot;}
        public boolean matches(Expected other){return other!=null&&paymentId==other.paymentId&&version==other.version
            &&flow.equals(other.flow)&&intent.equals(other.intent)&&create.equals(other.create)&&abort.equals(other.abort)&&snapshot.equals(other.snapshot);}
        @Override public boolean equals(Object other){return other instanceof Expected expected&&matches(expected);}
        @Override public int hashCode(){return Objects.hash(paymentId,version,flow,intent,create,abort,snapshot);}
        @Override public String toString(){return "ClosureExpected[redacted]";}
    }

    private final OrderReauthorizationTransactions results;
    private final TransactionTemplate transaction;
    NewFlowClosureControl(OrderReauthorizationTransactions results,PlatformTransactionManager manager){
        this.results=Objects.requireNonNull(results);transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** Internal only. No HTTP endpoint, worker, financial sender or dispatch capability. */
    enum ReconcileResult { CLOSED_RECORDED, ALREADY_CLOSED, NOT_CLOSED, RETRY_LATER,
        MANUAL_REVIEW_REQUIRED, CONFLICT, NOT_APPLICABLE }
    private record Selection(Expected expected,ReconcileResult stopped){}
    ReconcileResult reconcile(UUID operationId,PaymentGateway gateway){return reconcile(operationId,gateway,()->{});}
    ReconcileResult reconcile(UUID operationId,PaymentGateway gateway,Runnable getFailure){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Closure reconciliation requires no ambient transaction");
        Objects.requireNonNull(operationId);Objects.requireNonNull(gateway);
        try{
            var selected=Objects.requireNonNull(transaction.execute(tx->{
                var target=results.newFlowClosureTarget(operationId);
                if(target.isEmpty())return new Selection(null,ReconcileResult.NOT_APPLICABLE);
                var row=target.orElseThrow();
                if("MANUAL_REVIEW".equals(row.get("state")))return new Selection(null,ReconcileResult.MANUAL_REVIEW_REQUIRED);
                if("ABORTED".equals(row.get("state"))||row.get("abort_requested_at")==null)
                    return new Selection(null,ReconcileResult.NOT_APPLICABLE);
                long orderId=((Number)row.get("order_id")).longValue();
                return new Selection(results.loadNewFlowClosureExpected(orderId,operationId),null);
            }));
            if(selected.stopped()!=null)return selected.stopped();
            var expected=selected.expected();
            // Exactly one GET, outside Tx. Nonterminal results cannot mint writer evidence.
            var assessed=Objects.requireNonNull(gateway.assessNewFlowClosure(expected));
            return switch(assessed.kind()){
                case NOT_CLOSED->ReconcileResult.NOT_CLOSED;
                case RETRY_LATER->ReconcileResult.RETRY_LATER;
                case MANUAL_REVIEW_REQUIRED->ReconcileResult.MANUAL_REVIEW_REQUIRED;
                case CLOSED->switch(record(expected.flow().orderId(),operationId,assessed.closure().orElseThrow())){
                    case RECORDED->ReconcileResult.CLOSED_RECORDED;
                    case REPLAYED->ReconcileResult.ALREADY_CLOSED;
                    case CONFLICT->ReconcileResult.CONFLICT;
                };
            };
        }catch(NewFlowClosureObservation.Expired failure){return ReconcileResult.RETRY_LATER;}
        catch(com.example.ecsite.payment.PaymentGatewayException failure){
            getFailure.run();
            return failure.category()==com.example.ecsite.payment.PaymentGatewayErrorCategory.INVALID_RESPONSE
                ?ReconcileResult.CONFLICT:ReconcileResult.RETRY_LATER;
        }catch(org.springframework.transaction.TransactionException|org.springframework.dao.TransientDataAccessException failure){
            // Includes uncertain commit: no success claim, retry can only GET again.
            return ReconcileResult.RETRY_LATER;
        }catch(IllegalArgumentException failure){return ReconcileResult.CONFLICT;}
        catch(IllegalStateException failure){return ReconcileResult.CONFLICT;}
    }
    enum RecordResult { RECORDED, REPLAYED, CONFLICT }
    RecordResult record(long orderId,UUID operationId,NewFlowClosureObservation observation){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Closure writer requires no ambient transaction");
        Objects.requireNonNull(observation);
        try{observation.requireTarget(orderId,operationId);}catch(IllegalArgumentException mismatch){return RecordResult.CONFLICT;}
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        var result=Objects.requireNonNull(transaction.execute(tx->{
            var recorded=results.recordNewFlowClosure(observation); // Writer consumes first; even direct internal calls cannot bypass it.
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCompletion(int status){committed.set(status==STATUS_COMMITTED);}
                });
            return recorded;
        }));
        if(!committed.get())throw new IllegalStateException("Closure commit not confirmed");
        return result;
    }
    NewFlowClosureObservation observe(PaymentGateway gateway,long orderId,UUID operationId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Closure GET requires no ambient transaction");
        var expected=Objects.requireNonNull(transaction.execute(tx->results.loadNewFlowClosureExpected(orderId,operationId)));
        var observation=Objects.requireNonNull(gateway.observeNewFlowClosure(expected));
        var current=Objects.requireNonNull(transaction.execute(tx->results.loadNewFlowClosureExpected(orderId,operationId)));
        observation.requireMatches(current);observation.requireFresh();return observation;
    }
}
