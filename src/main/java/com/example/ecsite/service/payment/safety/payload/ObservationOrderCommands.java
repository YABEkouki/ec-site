package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.UUID;
import com.example.ecsite.service.payment.safety.payload.ObservationEventFactory.*;
import com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;

/** Pure immutable envelopes only. No clock, persistence, trial allocation or financial authority.
 * Retain the envelope/time on replay. CLASSIFIED and NOT_FOUND are descriptions, not save APIs.
 */
public final class ObservationOrderCommands {
    private ObservationOrderCommands() {}
    public static final class Command {
        private final ObservationRunRequest request;
        private final ObservationOrderContextV1 context;
        private final Event event;
        private Command(ObservationRunRequest r,ObservationOrderContextV1 c,Event e){request=r;context=c;event=e;}
        public ObservationRunRequest request(){return request;}
        public ObservationOrderContextV1 context(){return context;}
        public Event event(){return event;}
        @Override public String toString(){return "ObservationOrderCommand[redacted]";}
    }
    public static Command started(ObservationRunRequest request,long target,int attempt,long epoch,UUID owner,
            ObservationRunRequest.Actor actor,Instant time) {
        if(request==null || attempt<1 || attempt>3 || owner==null)throw PayloadEncodingV1.invalid();
        var factory=new ObservationEventFactory(request);
        var base=factory.orderStarted(target,attempt,epoch,actor,time);
        var context=new ObservationOrderContextV1(1,ObservationRunDigestsV1.manifestDigest(request.manifest()),request.manifest().classificationContractVersion(),null,null,epoch,epoch,owner,null,null,null);
        return new Command(request,context,factory.withOrderContext(base,context));
    }
    public static Command finished(Command started,long epoch,UUID owner,ObservationRunRequest.Actor actor,
            Instant time,Outcome outcome,AssessmentRef assessment,Failure failure,TakeoverRef takeover) {
        if(started==null || !started.event.record().eventType().equals("ORDER_STARTED") || outcome==null || owner==null)throw PayloadEncodingV1.invalid();
        var r=started.event.record();var request=started.request;var before=started.context;
        if((outcome==Outcome.CLASSIFIED)!=(assessment!=null))throw PayloadEncodingV1.invalid();
        boolean interrupted=outcome==Outcome.INTERRUPTED;
        if(interrupted) {
            if(takeover==null || epoch<=before.startEpoch() || epoch!=takeover.epochAfter()
                || takeover.epochBefore()<before.startEpoch() || !owner.equals(takeover.ownerAfter())
                || !takeover.runId().equals(request.requestId()) || !takeover.manifestDigest().equals(before.manifestDigest())
                || (takeover.epochBefore()==before.startEpoch() && !takeover.ownerBefore().equals(before.saveOwner())))throw PayloadEncodingV1.invalid();
        }else if(takeover!=null || epoch!=before.startEpoch() || !owner.equals(before.saveOwner()))throw PayloadEncodingV1.invalid();
        boolean validError=switch(outcome) {
            case CLASSIFIED,NOT_FOUND -> failure==null;
            case ACQUISITION_FAILED -> failure!=null && failure.stage()==Stage.ACQUISITION;
            case ASSEMBLY_FAILED -> failure!=null && (failure.stage()==Stage.ASSEMBLY || failure.stage()==Stage.CLASSIFICATION);
            case PERSISTENCE_FAILED -> failure!=null && failure.stage()==Stage.PERSISTENCE;
            case INTERRUPTED -> failure!=null && failure.stage()==Stage.RECOVERY;
        };
        if(!validError)throw PayloadEncodingV1.invalid();
        var factory=new ObservationEventFactory(request);
        var base=factory.orderFinished(r.targetOrderId(),r.attemptNo(),epoch,actor,time,outcome,assessment);
        var context=new ObservationOrderContextV1(1,before.manifestDigest(),before.contractVersion(),started.event.eventUuid(),started.event.recordDigest(),before.startEpoch(),epoch,owner,takeover,failure,assessment==null?null:assessment.recordDigest());
        return new Command(request,context,factory.withOrderContext(base,context));
    }
}
