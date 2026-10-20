package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.*;
import com.example.ecsite.service.payment.safety.payload.ObservationRunRequest.Actor;

/** Immutable replay envelopes. Production callers obtain occurredAt from the JDBC lifecycle API's
 * prepare methods (database time), retain this object, and never regenerate it after an unknown commit.
 * No financial capabilities, generic finish or arbitrary event/payload can be supplied.
 */
public final class ObservationLifecycleCommands {
    private ObservationLifecycleCommands() {}
    public enum Action { CREATE, HEARTBEAT, TAKEOVER, PAUSE, RESUME, EMPTY_SUCCESS, BEFORE_OBSERVATION_FAILED }
    public record Intent(UUID controlRequestId,ObservationRunRequest request,Action action,UUID ownerBefore,
            UUID ownerAfter,long epochBefore,long expectedVersion,Actor actor,Instant occurredAt) {}
    public static final class Command {
        private final Intent intent;
        private final String digest;
        private final ObservationLifecycleContextV1 context;
        private final ObservationEventFactory.Event event;
        private Command(Intent i) {
            if(i==null || i.controlRequestId()==null || i.request()==null || i.action()==null || i.actor()==null || i.occurredAt()==null
                || i.occurredAt().getNano()%1000!=0)throw PayloadEncodingV1.invalid();
            long nextEpoch,nextVersion;
            try {
                if(i.action()==Action.CREATE) {
                    if(!i.controlRequestId().equals(i.request().requestId()) || i.ownerBefore()!=null || i.ownerAfter()==null || i.epochBefore()!=0 || i.expectedVersion()!=-1 || !i.actor().equals(i.request().requester()))throw PayloadEncodingV1.invalid();
                    nextEpoch=1;nextVersion=0;
                }else {
                    if(i.epochBefore()<=0 || i.expectedVersion()<0)throw PayloadEncodingV1.invalid();
                    nextEpoch=(i.action()==Action.TAKEOVER || i.action()==Action.RESUME)?Math.addExact(i.epochBefore(),1):i.epochBefore();
                    nextVersion=Math.addExact(i.expectedVersion(),1);
                    switch(i.action()) {
                        case HEARTBEAT -> {if(i.ownerBefore()==null || !i.ownerBefore().equals(i.ownerAfter()))throw PayloadEncodingV1.invalid();}
                        case TAKEOVER -> {if(i.ownerBefore()==null || i.ownerAfter()==null)throw PayloadEncodingV1.invalid();}
                        case RESUME -> {if(i.ownerBefore()!=null || i.ownerAfter()==null)throw PayloadEncodingV1.invalid();}
                        case PAUSE,EMPTY_SUCCESS,BEFORE_OBSERVATION_FAILED -> {if(i.ownerBefore()==null || i.ownerAfter()!=null)throw PayloadEncodingV1.invalid();}
                        default -> throw PayloadEncodingV1.invalid();
                    }
                }
            }catch(ArithmeticException e){throw PayloadEncodingV1.invalid();}
            intent=i;
            ObservationRunDigestsV1.requestDigest(i.request()); // size/version validation before hashing the envelope
            digest=PayloadEncodingV1.digest("ec-site/payment-safety/observation-lifecycle-command",i);
            if(i.action()==Action.HEARTBEAT){context=null;event=null;return;}
            context=new ObservationLifecycleContextV1(1,digest,i.ownerBefore(),i.ownerAfter(),i.action()==Action.CREATE?null:i.epochBefore(),nextEpoch,
                i.action()==Action.CREATE?null:i.expectedVersion(),nextVersion);
            var factory=new ObservationEventFactory(i.request());
            var base=switch(i.action()) {
                case CREATE -> factory.started(i.occurredAt());
                case TAKEOVER -> factory.leaseTaken(nextEpoch,i.actor(),i.occurredAt());
                case PAUSE -> factory.paused(i.controlRequestId(),nextEpoch,i.actor(),i.occurredAt());
                case RESUME -> factory.resumed(i.controlRequestId(),nextEpoch,i.actor(),i.occurredAt());
                case EMPTY_SUCCESS -> factory.finished(ObservationEventFactory.TerminalStatus.SUCCESS,nextEpoch,i.actor(),i.occurredAt());
                case BEFORE_OBSERVATION_FAILED -> factory.runFailed(nextEpoch,i.actor(),i.occurredAt());
                default -> throw PayloadEncodingV1.invalid();
            };
            event=factory.withLifecycleContext(base,context);
        }
        public Intent intent(){return intent;}
        public ObservationRunRequest request(){return intent.request();}
        public Action action(){return intent.action();}
        public String commandDigest(){return digest;}
        public Optional<ObservationLifecycleContextV1> context(){return Optional.ofNullable(context);}
        public Optional<ObservationEventFactory.Event> event(){return Optional.ofNullable(event);}
        @Override public String toString(){return "PreparedObservationCommand[redacted]";}
    }
    public static Command create(ObservationRunRequest request,UUID owner,Instant databaseTime) {
        if(request==null)throw PayloadEncodingV1.invalid();
        return new Command(new Intent(request.requestId(),request,Action.CREATE,null,owner,0,-1,request.requester(),databaseTime));
    }
    public static Command prepare(ObservationRunRequest request,Action action,UUID control,UUID before,UUID after,
            long epoch,long expectedVersion,Actor actor,Instant databaseTime) {
        if(action==Action.CREATE)throw PayloadEncodingV1.invalid();
        return new Command(new Intent(control,request,action,before,after,epoch,expectedVersion,actor,databaseTime));
    }
}
