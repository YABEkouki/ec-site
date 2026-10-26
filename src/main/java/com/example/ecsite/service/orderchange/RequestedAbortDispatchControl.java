package com.example.ecsite.service.orderchange;

import java.security.MessageDigest;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.ecsite.payment.orderchange.StoredOrderChangeRequest;
import tools.jackson.databind.JsonNode;

/** Internal orchestration; deliberately not a bean, controller or automatic worker.
 * Public visibility only lets the Gateway consume the opaque nested Dispatch. */
public final class RequestedAbortDispatchControl {
    static final Duration MAX_OBSERVATION_AGE=Duration.ofSeconds(5);
    record Context(StoredOrderChangeRequest request,long version) {
        Context { Objects.requireNonNull(request);if(version<0)throw new IllegalArgumentException("Invalid dispatch version"); }
        boolean matches(Context other){
            return other!=null&&version==other.version&&request.id()==other.request.id()
                &&request.paymentId()==other.request.paymentId()&&request.transactionId()==other.request.transactionId()
                &&request.createdAt().equals(other.request.createdAt())&&request.replayNotAfter().equals(other.request.replayNotAfter())
                &&MessageDigest.isEqual(request.httpRequest().fingerprint(),other.request.httpRequest().fingerprint());
        }
        @Override public String toString(){return "RequestedAbortContext[redacted]";}
    }
    static final class Observation {
        private final Context context;
        private final Instant startedAt,completedAt;
        private final OrderReauthorizationTransactions.RequestedAbortEvidence authorization;
        private Observation(Context context,Instant started,Instant completed,
                OrderReauthorizationTransactions.RequestedAbortEvidence authorization){
            this.context=context;startedAt=started;completedAt=completed;this.authorization=authorization;
        }
        void requireContext(Context expected){if(!context.matches(expected))throw new IllegalArgumentException("Observation target mismatch");}
        void requireFresh(Instant now){
            if(now==null||now.isBefore(completedAt)||Duration.between(startedAt,now).compareTo(MAX_OBSERVATION_AGE)>0)
                throw new IllegalStateException("Abort observation is stale or future dated");
        }
        OrderReauthorizationTransactions.RequestedAbortEvidence authorization(){return authorization;}
        @Override public String toString(){return "RequestedAbortObservation[redacted]";}
    }
    /** Pure internal adapter, currently used only by isolation fixtures; not proof of HTTP provenance. */
    static Observation observe(Context context,Instant startedAt,Instant completedAt,JsonNode body){
        if(context==null||startedAt==null||completedAt==null||completedAt.isBefore(startedAt))
            throw new IllegalArgumentException("Invalid observation interval");
        var saved=context.request();var request=saved.httpRequest();
        if(!"NEW_ABORT".equals(request.kind()))throw new IllegalArgumentException("Expected new abort context");
        var authorization=OrderReauthorizationTransactions.RequestedAbortEvidence.verify(request.orderId(),request.operationId(),
            saved.paymentId(),request.flowId(),request.amount(),request.connection(),body);
        return new Observation(context,startedAt,completedAt,authorization);
    }
    record Claimed(StoredOrderChangeRequest request,long version){}
    /** No Serializable/Cloneable contract, public constructor, restore method or unguarded request getter. */
    public static final class Dispatch {
        private final Claimed claimed;
        private final Observation observation;
        private final Clock clock;
        private final com.example.ecsite.payment.payjp.RequestedAbortObservation trusted;
        private final com.example.ecsite.payment.payjp.RequestedAbortObservation.Expected expected;
        private final AtomicBoolean consumed=new AtomicBoolean();
        private Dispatch(Claimed claimed,Observation observation,Clock clock){this.claimed=claimed;this.observation=observation;this.clock=clock;trusted=null;expected=null;}
        private Dispatch(Claimed claimed,com.example.ecsite.payment.payjp.RequestedAbortObservation observation,
                com.example.ecsite.payment.payjp.RequestedAbortObservation.Expected expected,Clock clock){
            this.claimed=claimed;trusted=observation;this.expected=expected;this.clock=clock;this.observation=null;
        }
        /** Only trusted transport-backed claims can cross the Gateway boundary. Burn before validation. */
        public StoredOrderChangeRequest consumeForGateway(){
            if(!consumed.compareAndSet(false,true))throw new IllegalStateException("Abort dispatch already consumed");
            requireNoTransaction();if(trusted==null)throw new IllegalStateException("Synthetic observations cannot authorize transport");
            trusted.requireConsumedFor(expected);var saved=claimed.request();var request=saved.httpRequest();
            if(saved.id()!=expected.requestId()||saved.paymentId()!=expected.paymentId()
                ||request.orderId()!=expected.flow().orderId()||!request.operationId().equals(expected.flow().operationId())
                ||!Objects.equals(request.flowId(),expected.flow().newFlowId())||request.amount()!=expected.flow().amount()
                ||!request.connection().equals(expected.flow().connection())
                ||!MessageDigest.isEqual(request.fingerprint(),expected.fingerprint())||!"NEW_ABORT".equals(request.kind())
                ||request.purpose()!=com.example.ecsite.entity.PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT
                ||!"CANCEL".equals(request.transactionType()))throw new IllegalStateException("Abort dispatch target mismatch");
            saved.requireReplayAt(clock.instant());return saved;
        }
        Claimed claimedForOutcome(){if(trusted==null)throw new IllegalStateException("Trusted dispatch required");return claimed;}
        StoredOrderChangeRequest consume(){
            if(!consumed.compareAndSet(false,true))throw new IllegalStateException("Abort dispatch already consumed");
            // Even an expired/invalid consumption attempt irrevocably burns this in-memory permit.
            requireNoTransaction();if(observation==null)throw new IllegalStateException("Trusted Dispatch requires Gateway consumption");var now=clock.instant();observation.requireFresh(now);claimed.request().requireReplayAt(now);
            return claimed.request();
        }
        long claimedVersion(){return claimed.version();}
        @Override public String toString(){return "RequestedAbortDispatch[redacted]";}
    }
    private final OrderReauthorizationTransactions results;
    private final TransactionTemplate transaction;
    private final Clock clock;
    RequestedAbortDispatchControl(OrderReauthorizationTransactions results,PlatformTransactionManager manager,Clock clock){
        this.results=Objects.requireNonNull(results);this.clock=Objects.requireNonNull(clock);
        transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    Context load(long requestId){
        requireNoTransaction();
        return Objects.requireNonNull(transaction.execute(status->results.loadRequestedAbortDispatchContext(requestId)));
    }
    /** Internal GET-only orchestration, deliberately disconnected from start()/Dispatch.
     * DB locks are released before transport and reacquired only to reject a stale snapshot. */
    com.example.ecsite.payment.payjp.RequestedAbortObservation observe(
            com.example.ecsite.payment.PaymentGateway gateway,long requestId){
        requireNoTransaction();
        var expected=Objects.requireNonNull(transaction.execute(status->results.loadRequestedAbortObservationExpected(requestId)));
        var observed=Objects.requireNonNull(gateway.observeRequestedAbort(expected));
        var current=Objects.requireNonNull(transaction.execute(status->results.loadRequestedAbortObservationExpected(requestId)));
        observed.requireMatches(current);observed.requireFresh();return observed;
    }
    enum SendResult { SENT_UNCONFIRMED, RESULT_UNKNOWN, MANUAL_REVIEW_REQUIRED }
    record SendOutcome(SendResult result,boolean recorded){}
    /** Tx A -> trusted GET -> Tx B -> one transport attempt. No result application or release. */
    SendOutcome send(com.example.ecsite.payment.PaymentGateway gateway,long requestId){
        requireNoTransaction();
        var expected=Objects.requireNonNull(transaction.execute(status->results.loadRequestedAbortObservationExpected(requestId)));
        var observation=Objects.requireNonNull(gateway.observeRequestedAbort(expected));
        observation.requireMatches(expected);
        var dispatch=startTrusted(observation);
        try{
            gateway.sendRequestedAbort(dispatch);return new SendOutcome(SendResult.SENT_UNCONFIRMED,false);
        }catch(RuntimeException failure){
            Integer status=failure instanceof com.example.ecsite.payment.PaymentGatewayException providerFailure?providerFailure.httpStatus():null;
            boolean manual=status!=null&&(status==404||status==422);
            boolean recorded;
            try{recorded=Boolean.TRUE.equals(transaction.execute(tx->results.recordRequestedAbortPostUnknown(dispatch,manual)));}
            catch(RuntimeException recordingFailure){recorded=false;} // STARTED remains irreversible; never retry transport.
            return new SendOutcome(manual?SendResult.MANUAL_REVIEW_REQUIRED:SendResult.RESULT_UNKNOWN,recorded);
        }
    }
    Dispatch startTrusted(com.example.ecsite.payment.payjp.RequestedAbortObservation observation){
        requireNoTransaction();Objects.requireNonNull(observation);
        var committed=new AtomicBoolean();
        var bound=new java.util.concurrent.atomic.AtomicReference<com.example.ecsite.payment.payjp.RequestedAbortObservation.Expected>();
        var claimed=Objects.requireNonNull(transaction.execute(status->{
            // Consume before DB validation: every Tx B rollback irrevocably invalidates this observation.
            bound.set(observation.consumeBound());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int completion){committed.set(completion==STATUS_COMMITTED);}
            });
            return results.claimTrustedRequestedAbortDispatch(bound.get(),observation);
        }));
        if(!committed.get())throw new IllegalStateException("Abort dispatch commit was not confirmed");
        return new Dispatch(claimed,observation,bound.get(),clock);
    }
    Dispatch start(Context context,Observation observation){
        requireNoTransaction();
        if(context==null||observation==null)throw new IllegalArgumentException("Validated abort observation required");
        observation.requireContext(context);observation.requireFresh(clock.instant());
        var committed=new AtomicBoolean();
        var claimed=Objects.requireNonNull(transaction.execute(status->{
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int completion){committed.set(completion==STATUS_COMMITTED);}
            });
            return results.claimRequestedAbortDispatch(context,observation);
        }));
        // execute() can return normally after a local rollback-only completion. Require both
        // normal return and the transaction manager's positive commit-completion notification.
        if(!committed.get())throw new IllegalStateException("Abort dispatch commit was not confirmed");
        return new Dispatch(claimed,observation,clock);
    }
    private static void requireNoTransaction(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Dispatch control requires no ambient transaction");
    }
}
