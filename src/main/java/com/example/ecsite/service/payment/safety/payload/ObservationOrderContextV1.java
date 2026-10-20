package com.example.ecsite.service.payment.safety.payload;

import java.util.*;

/** Closed audit metadata. Shape/content evidence only: not a persisted STARTED, takeover or lease grant. */
public record ObservationOrderContextV1(int orderContextVersion,String manifestDigest,String contractVersion,
        UUID startedUuid,String startedDigest,long startEpoch,long saveEpoch,UUID saveOwner,
        TakeoverRef takeover,Failure failure,String assessmentDigest) {
    public ObservationOrderContextV1 {
        hash(manifestDigest);ObservationRunRequest.text(contractVersion,64);
        if(orderContextVersion!=1 || startEpoch<=0 || saveEpoch<=0 || saveOwner==null)throw PayloadEncodingV1.invalid();
        if((startedUuid==null)!=(startedDigest==null))throw PayloadEncodingV1.invalid();
        if(startedDigest!=null)hash(startedDigest);
        if(assessmentDigest!=null)hash(assessmentDigest);
    }
    public enum Stage { ACQUISITION, ASSEMBLY, CLASSIFICATION, PERSISTENCE, RECOVERY }
    public enum Code {
        READ_FAILED(Stage.ACQUISITION), MAPPING_FAILED(Stage.ACQUISITION), LIMIT_EXCEEDED(Stage.ACQUISITION),
        UNREPRESENTABLE_INPUT(Stage.ASSEMBLY), CLASSIFICATION_FAILED(Stage.CLASSIFICATION),
        CODEC_FAILED(Stage.PERSISTENCE), STORAGE_FAILED(Stage.PERSISTENCE), WORKER_INTERRUPTED(Stage.RECOVERY);
        private final Stage stage;
        Code(Stage stage){this.stage=stage;}
    }
    public record Failure(Stage stage,Code code) {
        public Failure {if(stage==null || code==null || code.stage!=stage)throw PayloadEncodingV1.invalid();}
    }
    /** Reference identity is checked here; actual commit/ownership history must be checked by a future repository. */
    public record TakeoverRef(UUID runId,UUID eventUuid,String recordDigest,String manifestDigest,
            long epochBefore,long epochAfter,UUID ownerBefore,UUID ownerAfter) {
        public TakeoverRef {
            hash(recordDigest);hash(manifestDigest);
            if(runId==null || eventUuid==null || ownerBefore==null || ownerAfter==null || epochBefore<=0
                || epochBefore==Long.MAX_VALUE || epochAfter!=epochBefore+1
                || !eventUuid.equals(ObservationEventIdsV1.leaseTaken(runId,epochAfter)))throw PayloadEncodingV1.invalid();
        }
        public static TakeoverRef from(ObservationRunRequest request,ObservationLifecycleCommands.Command command) {
            if(request==null || command==null || command.action()!=ObservationLifecycleCommands.Action.TAKEOVER || !request.equals(command.request()))throw PayloadEncodingV1.invalid();
            var event=command.event().orElseThrow();event.verify(event.eventUuid(),event.recordDigest());
            var i=command.intent();return new TakeoverRef(request.requestId(),event.eventUuid(),event.recordDigest(),ObservationRunDigestsV1.manifestDigest(request.manifest()),i.epochBefore(),i.epochBefore()+1,i.ownerBefore(),i.ownerAfter());
        }
    }
    void validateFinished(UUID runId,String outcome,String expectedAssessmentDigest) {
        var result=ObservationEventFactory.Outcome.valueOf(outcome);
        if(result==ObservationEventFactory.Outcome.CLASSIFIED) {
            if(assessmentDigest==null || !assessmentDigest.equals(expectedAssessmentDigest))throw PayloadEncodingV1.invalid();
        }else if(assessmentDigest!=null)throw PayloadEncodingV1.invalid();
        boolean validError=switch(result) {
            case CLASSIFIED,NOT_FOUND -> failure==null;
            case ACQUISITION_FAILED -> failure!=null && failure.stage()==Stage.ACQUISITION;
            case ASSEMBLY_FAILED -> failure!=null && (failure.stage()==Stage.ASSEMBLY || failure.stage()==Stage.CLASSIFICATION);
            case PERSISTENCE_FAILED -> failure!=null && failure.stage()==Stage.PERSISTENCE;
            case INTERRUPTED -> failure!=null && failure.stage()==Stage.RECOVERY;
        };
        if(!validError)throw PayloadEncodingV1.invalid();
        if(result==ObservationEventFactory.Outcome.INTERRUPTED) {
            if(takeover==null || saveEpoch<=startEpoch || saveEpoch!=takeover.epochAfter() || takeover.epochBefore()<startEpoch
                || !saveOwner.equals(takeover.ownerAfter()) || !runId.equals(takeover.runId()) || !manifestDigest.equals(takeover.manifestDigest()))throw PayloadEncodingV1.invalid();
        }else if(takeover!=null || startEpoch!=saveEpoch)throw PayloadEncodingV1.invalid();
    }
    static void hash(String value){if(value==null || !value.matches("[0-9a-f]{64}"))throw PayloadEncodingV1.invalid();}
}
