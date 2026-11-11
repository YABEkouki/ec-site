package com.example.ecsite.payment.orderchange;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.service.order.evidence.OrderUnappliedClosureCodec;
import static com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.*;
import static org.assertj.core.api.Assertions.*;

class ThreeDsFailureEvidenceVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Instant NOW=Instant.parse("2026-11-10T01:00:00Z");
    static final UUID CONTROL=UUID.fromString("00000000-0000-0000-0000-000000000001"), ADMISSION=UUID.fromString("00000000-0000-0000-0000-000000000002"), OP=UUID.fromString("00000000-0000-0000-0000-000000000003"), GEN=UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final CaptureConnectionIdentity CONNECTION=new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
    final ThreeDsFailureEvidenceVerifier verifier=new ThreeDsFailureEvidenceVerifier();
    final OrderUnappliedClosureCodec codec=new OrderUnappliedClosureCodec();
    Ownership owner(){return new Ownership(CONTROL,ADMISSION,OP,1,GEN,10,11,12,13,"pfw_fixture",2190,0,CONNECTION);}
    Context context(State state){return new Context(owner(),state.ordinal(),2,state,new byte[32],new byte[32],1,null);}
    LocalFacts facts(){return new LocalFacts(owner(),2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(),new byte[32],null);}
    Window window(){return new Window(NOW.minusSeconds(1),NOW);}
    String response(boolean terminal){
        var n=(ObjectNode)JSON.readTree("""
          {"id":"pfw_fixture","amount":2190,"currency":"jpy","capture_method":"manual","livemode":false,
           "amount_received":null,"amount_capturable":null,"next_action":{},"canceled_at":null,
           "last_payment_error":{"code":"three_d_secure_failed"},"metadata":{"order_id":"1","order_change_operation_id":"00000000-0000-0000-0000-000000000003"}}
          """);
        n.put("status",terminal?"canceled":"requires_payment_method");
        if(terminal)n.put("canceled_at","2026-11-10T00:59:59Z");
        return n.toString();
    }
    Observation failure(){return verifier.failure(context(State.REQUESTED),facts(),CONNECTION,response(false),window(),NOW);}
    Observation terminal(){return verifier.terminal(context(State.FAILURE_OBSERVED),facts(),CONNECTION,response(true),new Window(NOW,NOW.plusSeconds(1)),NOW.plusSeconds(1),failure());}
    void reject(Runnable action){assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid 3DS failure evidence");}
    String changed(boolean terminal,String key,String json){var n=(ObjectNode)JSON.readTree(response(terminal));n.set(key,JSON.readTree(json));return n.toString();}
    Observation verify(String body){return verifier.failure(context(State.REQUESTED),facts(),CONNECTION,body,window(),NOW);}
    StoredObservation stored(Observation value){
        var raw=new StoredObservation(UUID.randomUUID(),CONTROL,value.context().version(),value.kind(),value.document(),value.window(),new byte[32],1,"123",value.window().finishedAt(),null,null);
        byte[] canonical=codec.canonicalValue(verifier.observationEnvelope(raw));
        return new StoredObservation(raw.id(),CONTROL,raw.controlVersion(),raw.kind(),raw.document(),raw.window(),raw.historyDigest(),1,"123",value.window().finishedAt(),canonical,codec.sha256(canonical));
    }
    @Test void validFailureAndTerminalRetainOnlyAllowlistedFacts(){
        String raw=changed(false,"client_secret","\"synthetic-secret\"");
        var first=verify(raw);var end=terminal();
        assertThat(first.kind()).isEqualTo(Kind.THREE_DS_FAILURE);
        assertThat(end.kind()).isEqualTo(Kind.FLOW_TERMINAL);
        assertThat(first.document()).doesNotContain("synthetic-secret","client_secret");
        assertThat(first.toString()+end.toString()).doesNotContain("pfw_fixture");
        assertThat(JSON.readTree(end.document()).get("amount_received").isNull()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"0","1","\"0\"","0.5","true","{}","[]"})
    void moneyMustBeExplicitJsonNull(String value){
        for(boolean terminal:List.of(false,true))for(String key:List.of("amount_received","amount_capturable")) {
            String raw=changed(terminal,key,value);
            reject(()->{if(terminal)verifier.terminal(context(State.FAILURE_OBSERVED),facts(),CONNECTION,raw,window(),NOW,failure());else verify(raw);});
        }
    }
    @ParameterizedTest @ValueSource(strings={"amount_received","amount_capturable","metadata","next_action","last_payment_error","canceled_at","id","status"})
    void missingRequiredFieldsFail(String field){var raw=(ObjectNode)JSON.readTree(response(false));raw.remove(field);reject(()->verify(raw.toString()));}
    @ParameterizedTest @ValueSource(strings={"null","[]","{\"type\":\"redirect_to_url\"}"})
    void actionMustBeEmptyObject(String value){reject(()->verify(changed(false,"next_action",value)));}
    @ParameterizedTest @ValueSource(strings={"2190.0","\"2190\"","2191","true"})
    void amountIsStrictIntegralAndExact(String value){reject(()->verify(changed(false,"amount",value)));}
    @ParameterizedTest @ValueSource(strings={"now","yesterday","2026-11-10","infinity","2026-02-30T00:00:00Z","2026-11-10T01:00:01Z"})
    void canceledTimestampIsAbsoluteValidAndNotFuture(String value){reject(()->verifier.terminal(context(State.FAILURE_OBSERVED),facts(),CONNECTION,changed(true,"canceled_at",JSON.writeValueAsString(value)),window(),NOW,failure()));}
    @Test void rejectsIdentityModeCurrencyAndMetadataConflicts(){
        for(var pair:List.of(new String[]{"id","\"other\""},new String[]{"currency","\"JPY\""},new String[]{"capture_method","\"automatic\""},new String[]{"livemode","true"},new String[]{"metadata","{}"}))reject(()->verify(changed(false,pair[0],pair[1])));
        reject(()->verifier.failure(context(State.REQUESTED),facts(),new CaptureConnectionIdentity("other","account","TEST","https://api.pay.jp"),response(false),window(),NOW));
        var foreign=new LocalFacts(new Ownership(CONTROL,ADMISSION,UUID.randomUUID(),1,GEN,10,11,12,13,"pfw_fixture",2190,0,CONNECTION),2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(),new byte[32],null);
        reject(()->verifier.failure(context(State.REQUESTED),foreign,CONNECTION,response(false),window(),NOW));
    }
    @Test void successfulHistoryOrConcurrentWorkCannotBecomeFailure(){
        for(String type:List.of("AUTHORIZE","CAPTURE")) for(UUID operation:Arrays.asList(OP,UUID.randomUUID(),null)) {
            var local=new LocalFacts(owner(),2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(new FinancialResult(operation,11,type,"SUCCEEDED")),new byte[32],null);
            reject(()->verifier.failure(context(State.REQUESTED),local,CONNECTION,response(false),window(),NOW));
        }
        var local=new LocalFacts(owner(),2,"AUTHORIZED","PENDING","PENDING",false,true,false,false,false,false,List.of(),new byte[32],null);
        reject(()->verifier.failure(context(State.REQUESTED),local,CONNECTION,response(false),window(),NOW));
        reject(()->verify(changed(false,"status","\"requires_capture\"")));
    }
    @Test void windowsVersionsAndHistoryCannotBeReplaced(){
        reject(()->verifier.failure(context(State.REQUESTED),facts(),CONNECTION,response(false),new Window(NOW.minusSeconds(6),NOW),NOW));
        reject(()->new Context(owner(),0,2,State.REQUESTED,new byte[32],new byte[31],1,null));
        reject(()->new Context(owner(),0,2,State.REQUESTED,new byte[32],new byte[32],2,null));
        reject(()->verifier.terminal(context(State.REQUESTED),facts(),CONNECTION,response(true),window(),NOW,failure()));
    }
    @Test void duplicateKeysAndTrailingJsonDoNotLeakProviderPayload(){
        reject(()->verify(response(false).replace("\"amount\":2190","\"amount\":2190,\"amount\":2190")));
        reject(()->verify(response(false)+" {}"));
        assertThatThrownBy(()->verify("{\"client_secret\":\"synthetic-secret\""))
            .hasMessage("Invalid 3DS failure evidence").hasNoCause();
    }
    @Test void storedCanonicalAndDigestAreVerifiedAndDefensivelyCopied(){
        var row=stored(failure());
        var proof=verifier.storedObservation(context(State.REQUESTED),facts(),row,null);
        assertThat(proof.id()).isEqualTo(row.id());
        var bad=row.digest();bad[0]^=1;
        var changed=new StoredObservation(row.id(),CONTROL,0,row.kind(),row.document(),row.window(),row.historyDigest(),1,row.recordingXid(),row.recordedAt(),row.canonical(),bad);
        reject(()->verifier.storedObservation(context(State.REQUESTED),facts(),changed,null));
        row.digest()[0]^=1;assertThat(row.digest()).isNotEqualTo(bad);
    }
    ObjectNode vector() {
        try(var input=getClass().getResourceAsStream("/evidence/three-ds-failure-v78.json")) {
            return (ObjectNode)JSON.readTree(input);
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    StoredObservation vectorObservation(String name) {
        var n=vector().get(name);
        return new StoredObservation(UUID.fromString(n.get("id").stringValue()),CONTROL,n.get("control_version").longValue(),Kind.valueOf(n.get("kind").stringValue()),
            n.get("document").toString(),new Window(Instant.parse(n.get("observed_started_at").stringValue()),Instant.parse(n.get("observed_finished_at").stringValue())),
            new byte[32],1,n.get("recording_xid").asString(),Instant.parse(n.get("recorded_at").stringValue()),
            HexFormat.of().parseHex(n.get("canonical_hex").stringValue()),HexFormat.of().parseHex(n.get("digest_hex").stringValue()));
    }
    Observation vectorTerminal() {
        var first=verifier.storedObservation(context(State.REQUESTED),facts(),vectorObservation("failure"),null);
        return verifier.storedObservation(context(State.FAILURE_OBSERVED),facts(),vectorObservation("terminal"),first);
    }
    UnknownSource source(){var v=vector();return new UnknownSource(UUID.fromString(v.get("attempt").get("id").stringValue()),
        UUID.fromString(v.get("unknown").get("id").stringValue()),v.get("attempt").toString(),v.get("unknown").toString(),false);}
    StoredResolution resolution(){var n=vector().get("resolution");return new StoredResolution(UUID.fromString(n.get("id").stringValue()),CONTROL,
        UUID.fromString(n.get("unknown_result_id").stringValue()),UUID.fromString(n.get("terminal_observation_id").stringValue()),2,"TERMINATED_UNAPPLIED",
        n.get("document").toString(),1,n.get("recording_xid").asString(),Instant.parse(n.get("recorded_at").stringValue()),
        HexFormat.of().parseHex(n.get("canonical_hex").stringValue()),HexFormat.of().parseHex(n.get("digest_hex").stringValue()));}
    @Test void postgresV78GoldenObservationAndResolutionAreCompatible() {
        var t=vectorTerminal();var proof=verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),source(),t,resolution());
        assertThat(proof.unknownResultId()).isEqualTo(source().resultId());
        assertThat(proof.digest()).isEqualTo(resolution().digest());
        assertThat(proof.toString()).doesNotContain("pfw_fixture","UNKNOWN");
    }
    @ParameterizedTest @ValueSource(strings={"id","attempt_id","admission_id","order_id","fencing_token","control_version","disposition"})
    void unknownSourceIdentityTokenAndDispositionMustMatch(String key) {
        var v=vector();var u=(ObjectNode)v.get("unknown");
        if(List.of("order_id","fencing_token","control_version").contains(key))u.put(key,999L);else u.put(key,"foreign");
        var bad=new UnknownSource(source().attemptId(),source().resultId(),source().attemptAudit(),u.toString(),false);
        reject(()->verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),bad,vectorTerminal(),resolution()));
    }
    @Test void previouslyResolvedUnknownAndUnstoredTerminalCannotResolve() {
        var bad=new UnknownSource(source().attemptId(),source().resultId(),source().attemptAudit(),source().resultAudit(),true);
        reject(()->verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),bad,vectorTerminal(),resolution()));
        reject(()->verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),source(),terminal(),resolution()));
    }
    @Test void resolutionDigestDocumentVersionAndTerminalCannotBeReplaced() {
        var r=resolution();byte[] bad=r.digest();bad[0]^=1;
        reject(()->verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),source(),vectorTerminal(),
            new StoredResolution(r.id(),CONTROL,r.unknownResultId(),r.terminalObservationId(),2,r.disposition(),r.document(),1,r.recordingXid(),r.recordedAt(),r.canonical(),bad)));
        reject(()->verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),source(),vectorTerminal(),
            new StoredResolution(r.id(),CONTROL,r.unknownResultId(),r.terminalObservationId(),2,r.disposition(),"{}",1,r.recordingXid(),r.recordedAt(),r.canonical(),r.digest())));
        reject(()->verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),source(),vectorTerminal(),
            new StoredResolution(r.id(),CONTROL,r.unknownResultId(),r.terminalObservationId(),2,r.disposition(),r.document(),2,r.recordingXid(),r.recordedAt(),r.canonical(),r.digest())));
        reject(()->verifier.storedResolution(context(State.TERMINAL_OBSERVED),facts(),source(),vectorTerminal(),
            new StoredResolution(r.id(),CONTROL,r.unknownResultId(),UUID.randomUUID(),2,r.disposition(),r.document(),1,r.recordingXid(),r.recordedAt(),r.canonical(),r.digest())));
    }
    @Test void optionalCancelRequestIsBoundToOnlyNewPaymentAndSameConnection() {
        var c=new Context(owner(),1,2,State.FAILURE_OBSERVED,new byte[32],new byte[32],1,14L);
        var wrong=new CancelBinding(14,OP,1,10,"pfw_fixture",2190,"NEW_ABORT",CONNECTION);
        var local=new LocalFacts(owner(),2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(),new byte[32],wrong);
        reject(()->verifier.terminal(c,local,CONNECTION,response(true),new Window(NOW,NOW.plusSeconds(1)),NOW.plusSeconds(1),failure()));
        var right=new CancelBinding(14,OP,1,11,"pfw_fixture",2190,"NEW_ABORT",CONNECTION);
        var valid=new LocalFacts(owner(),2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(),new byte[32],right);
        assertThat(verifier.terminal(c,valid,CONNECTION,response(true),new Window(NOW,NOW.plusSeconds(1)),NOW.plusSeconds(1),failure()).kind()).isEqualTo(Kind.FLOW_TERMINAL);
    }

    @Test void intentHistoryIsImmutableWhileCurrentHistoryMustMatchSavedFacts() {
        byte[] changed=new byte[32];changed[0]=1;
        var wrongIntent=new Context(owner(),1,2,State.FAILURE_OBSERVED,changed,new byte[32],1,null);
        reject(()->verifier.terminal(wrongIntent,facts(),CONNECTION,response(true),new Window(NOW,NOW.plusSeconds(1)),NOW.plusSeconds(1),failure()));
        var newHistory=new Context(owner(),1,2,State.FAILURE_OBSERVED,new byte[32],changed,1,null);
        reject(()->verifier.terminal(newHistory,facts(),CONNECTION,response(true),new Window(NOW,NOW.plusSeconds(1)),NOW.plusSeconds(1),failure()));
        var saved=new LocalFacts(owner(),2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(),changed,null);
        assertThat(verifier.terminal(newHistory,saved,CONNECTION,response(true),new Window(NOW,NOW.plusSeconds(1)),NOW.plusSeconds(1),failure()).kind()).isEqualTo(Kind.FLOW_TERMINAL);
    }

    @Test void liveModeRequiresMatchingTrustedConnectionAndStoredOwnership() {
        var connection=new CaptureConnectionIdentity("live","live-account","LIVE","https://api.pay.jp");
        var owner=new Ownership(CONTROL,ADMISSION,OP,1,GEN,10,11,12,13,"pfw_fixture",2190,0,connection);
        var c=new Context(owner,0,2,State.REQUESTED,new byte[32],new byte[32],1,null);
        var local=new LocalFacts(owner,2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(),new byte[32],null);
        assertThat(verifier.failure(c,local,connection,changed(false,"livemode","true"),window(),NOW).kind()).isEqualTo(Kind.THREE_DS_FAILURE);
        reject(()->verifier.failure(c,local,connection,response(false),window(),NOW));
    }

}
