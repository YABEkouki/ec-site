package com.example.ecsite.service.order.evidence;

import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;

/** Closed canonical V1 receipt payloads, distinct from content snapshots and financial requests.
 * Preview IDs remain candidate-local and are explicitly bound by newLineReferences. */
public final class OrderEditAdmissionPayloadCodec {
    public record RequestPayload(int version,String kind,long customerId,long orderId,int revision,List<Line> lines) {
        public RequestPayload { lines=List.copyOf(lines); }
    }
    public record CandidatePayload(int version,String kind,long orderId,long customerId,int revision,
            UUID epochId,Long headHistoryId,String baseFingerprint,String epochDigest,String headDigest,
            long paymentId,long authorizationId,int authorizedAmount,int authorizationRevision,int beforeAmount,
            String providerFlowId,OrderContentSnapshot preview,List<NewLineReference> newLineReferences,
            List<Offer> newOffers,List<StockDelta> stockDeltas) {
        public CandidatePayload {newLineReferences=List.copyOf(newLineReferences);newOffers=List.copyOf(newOffers);stockDeltas=List.copyOf(stockDeltas);}
        @Override public String toString(){return "AdmissionCandidate[redacted]";}
    }
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(OrderContentSnapshotCodec.MAX_BYTES)
            .maxStringLength(2000).maxNestingDepth(16).maxTokenCount(200000).maxNumberLength(20).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final OrderContentSnapshotCodec snapshots=new OrderContentSnapshotCodec();

    public Request normalize(Request r) {
        try {
            if(r==null||r.orderId()<=0||r.expectedRevision()<0||r.lines().isEmpty()||r.lines().size()>1000)throw invalid();
            var existing=new TreeMap<Long,Line>();var additions=new TreeMap<Long,Integer>();var seen=new HashSet<Long>();
            for(var l:r.lines()) {
                if(l==null||l.productId()<=0||l.quantity()<0||l.existingItemId()!=null&&(l.existingItemId()<=0||!seen.add(l.existingItemId())))throw invalid();
                if(l.quantity()==0)continue;
                if(l.existingItemId()!=null)existing.put(l.existingItemId(),l);
                else additions.merge(l.productId(),l.quantity(),Math::addExact);
            }
            var lines=new ArrayList<>(existing.values());additions.forEach((id,q)->lines.add(new Line(null,id,q)));
            if(lines.isEmpty())throw invalid();return new Request(r.orderId(),r.expectedRevision(),lines);
        } catch(RuntimeException e) {throw invalid();}
    }
    public byte[] request(long customerId,Request request) {
        if(customerId<=0)throw invalid();var r=normalize(request);
        return bytes(new RequestPayload(1,"ORDER_EDIT_ADMISSION_REQUEST",customerId,r.orderId(),r.expectedRevision(),r.lines()));
    }
    public RequestPayload decodeRequest(byte[] data) {
        try {
            var p=read(data,RequestPayload.class);
            if(p.version()!=1||!"ORDER_EDIT_ADMISSION_REQUEST".equals(p.kind())
                ||!Arrays.equals(data,request(p.customerId(),new Request(p.orderId(),p.revision(),p.lines()))))throw invalid();return p;
        } catch(RuntimeException e) {throw invalid();}
    }
    public byte[] candidate(Candidate c) {
        try {
            var f=Objects.requireNonNull(c).sourceEvidence();var d=new OrderPaymentEvidenceValidator().validate(f);
            if(!d.locallyVerified()||f.epoch()==null)throw invalid();
            var a=f.authorizations().stream().filter(x->x.id()==d.authorizationId()).findFirst().orElseThrow();
            String head=f.headHistoryId()==null?f.epoch().digest():f.histories().stream().filter(h->h.id()==f.headHistoryId()).findFirst().orElseThrow().digest();
            var p=new CandidatePayload(1,"ORDER_EDIT_ADMISSION_CANDIDATE",f.current().orderId(),f.current().customerId(),f.current().contentRevision(),
                f.epoch().id(),f.headHistoryId(),snapshots.fingerprint(f.current()),f.epoch().digest(),head,a.paymentId(),a.id(),a.amount(),a.revision(),f.current().totalAmount(),
                a.flowId(),c.previewSnapshot(),c.newLineReferences(),c.newOffers(),c.stockDeltas());
            return bytes(canonical(p));
        }catch(RuntimeException e){throw invalid();}
    }
    public CandidatePayload decodeCandidate(byte[] data) {
        try {var p=canonical(read(data,CandidatePayload.class));if(!Arrays.equals(data,bytes(p)))throw invalid();return p;}
        catch(RuntimeException e){throw invalid();}
    }
    /** Semantic receipt binding from immutable saved before-content, never from today's catalog.
     * This verifies the recorded proposal, not historic catalog availability or external authorization. */
    public void validateSaved(RequestPayload request,CandidatePayload candidate,OrderContentSnapshot before) {
        try {
            var p=canonical(candidate);before=snapshots.decode(snapshots.encode(before));
            if(request.customerId()!=p.customerId()||request.orderId()!=p.orderId()||request.revision()!=p.revision()
                ||before.orderId()!=p.orderId()||before.customerId()!=p.customerId()||before.contentRevision()!=p.revision()
                ||before.totalAmount()!=p.beforeAmount()||!snapshots.fingerprint(before).equals(p.baseFingerprint()))throw invalid();
            var saved=new TreeMap<Long,OrderContentSnapshot.Item>();before.items().forEach(i->saved.put(i.orderItemId(),i));
            var expected=new ArrayList<OrderContentSnapshot.Item>();var additions=new TreeMap<Long,Integer>();
            for(var l:normalize(new Request(request.orderId(),request.revision(),request.lines())).lines()) {
                var old=l.existingItemId()==null?null:saved.get(l.existingItemId());
                if(l.existingItemId()!=null&&old==null)throw invalid();
                if(old!=null&&old.productId()==l.productId())expected.add(new OrderContentSnapshot.Item(old.orderItemId(),old.productId(),old.productName(),old.categoryId(),old.categoryName(),old.unitPrice(),l.quantity(),Math.multiplyExact(old.unitPrice(),l.quantity()),old.taxCategoryId(),old.taxCategoryCode(),old.taxCategoryName(),old.taxRateBasisPoints()));
                else additions.merge(l.productId(),l.quantity(),Math::addExact);
            }
            var offers=new TreeMap<Long,Offer>();for(var o:p.newOffers())if(!o.active()||offers.put(o.productId(),o)!=null)throw invalid();
            if(!offers.keySet().equals(additions.keySet()))throw invalid();
            var references=new ArrayList<NewLineReference>();long key=1;
            for(var entry:additions.entrySet()) {
                while(saved.containsKey(key))key=Math.incrementExact(key);
                var o=offers.get(entry.getKey());int q=entry.getValue();
                expected.add(new OrderContentSnapshot.Item(key,o.productId(),o.productName(),o.categoryId(),o.categoryName(),o.unitPrice(),q,Math.multiplyExact(o.unitPrice(),q),o.taxCategoryId(),o.taxCategoryCode(),o.taxCategoryName(),o.taxRateBasisPoints()));
                references.add(new NewLineReference(key,o.productId()));key=Math.incrementExact(key);
            }
            expected.sort(Comparator.comparingLong(OrderContentSnapshot.Item::orderItemId));
            if(!expected.equals(p.preview().items())||!references.equals(p.newLineReferences())||expected.equals(before.items()))throw invalid();
            var b=new TreeMap<Long,Integer>();var a=new TreeMap<Long,Integer>();
            before.items().forEach(i->b.merge(i.productId(),i.quantity(),Math::addExact));
            expected.forEach(i->a.merge(i.productId(),i.quantity(),Math::addExact));
            var ids=new TreeSet<Long>(b.keySet());ids.addAll(a.keySet());
            var deltas=ids.stream().map(id->new StockDelta(id,b.getOrDefault(id,0),a.getOrDefault(id,0))).filter(d->d.delta()!=0).toList();
            if(!deltas.equals(p.stockDeltas()))throw invalid();
            var old=before.charges().getFirst();var charge=p.preview().charges().getFirst();
            // V1 proposal tariff is frozen: future tariff changes need a new receipt format,
            // never reinterpret a saved V1 receipt using a future calculator's current rates.
            int v1Shipping=p.preview().itemSubtotal()>=5000?0:550;
            if(charge.orderChargeId()!=old.orderChargeId()||!charge.type().equals(old.type())||charge.taxCategoryId()!=old.taxCategoryId()
                ||!charge.taxCategoryCode().equals(old.taxCategoryCode())||!charge.taxCategoryName().equals(old.taxCategoryName())||charge.taxRateBasisPoints()!=old.taxRateBasisPoints()
                ||charge.amount()!=v1Shipping||!charge.name().equals("送料・梱包料")||charge.displayOrder()!=10)throw invalid();
        }catch(RuntimeException invalid){throw invalid();}
    }
    private CandidatePayload canonical(CandidatePayload p) {
        if(p.version()!=1||!"ORDER_EDIT_ADMISSION_CANDIDATE".equals(p.kind())||p.orderId()<=0||p.customerId()<=0||p.revision()<0
            ||p.epochId()==null||p.headHistoryId()!=null&&p.headHistoryId()<=0||p.paymentId()<=0||p.authorizationId()<=0
            ||p.authorizedAmount()<=0||p.beforeAmount()<=0||p.beforeAmount()>p.authorizedAmount()||p.authorizationRevision()<0||p.authorizationRevision()>p.revision()
            ||p.providerFlowId()==null||p.providerFlowId().isBlank()||!OrderEvidenceDigests.validHash(p.baseFingerprint())
            ||!OrderEvidenceDigests.validHash(p.epochDigest())||!OrderEvidenceDigests.validHash(p.headDigest()))throw invalid();
        var snapshot=snapshots.decode(snapshots.encode(p.preview()));
        if(snapshot.orderId()!=p.orderId()||snapshot.customerId()!=p.customerId()||snapshot.contentRevision()!=p.revision())throw invalid();
        var references=p.newLineReferences().stream().sorted(Comparator.comparingLong(NewLineReference::previewItemId)).toList();
        var offers=p.newOffers().stream().sorted(Comparator.comparingLong(Offer::productId)).toList();
        var deltas=p.stockDeltas().stream().sorted(Comparator.comparingLong(StockDelta::productId)).toList();
        var ids=new HashSet<Long>();for(var r:references)if(r.previewItemId()<=0||r.productId()<=0||!ids.add(r.previewItemId())
            ||snapshot.items().stream().noneMatch(i->i.orderItemId()==r.previewItemId()&&i.productId()==r.productId()))throw invalid();
        ids.clear();for(var o:offers)if(o.productId()<=0||!ids.add(o.productId()))throw invalid();
        ids.clear();for(var d:deltas)if(d.productId()<=0||d.beforeQuantity()<0||d.afterQuantity()<0||!ids.add(d.productId())||d.delta()==0)throw invalid();
        return new CandidatePayload(p.version(),p.kind(),p.orderId(),p.customerId(),p.revision(),p.epochId(),p.headHistoryId(),p.baseFingerprint(),p.epochDigest(),p.headDigest(),
            p.paymentId(),p.authorizationId(),p.authorizedAmount(),p.authorizationRevision(),p.beforeAmount(),p.providerFlowId(),snapshot,references,offers,deltas);
    }
    private byte[] bytes(Object value) {var b=json.writeValueAsBytes(value);if(b.length>OrderContentSnapshotCodec.MAX_BYTES)throw invalid();return b;}
    private <T>T read(byte[] data,Class<T> type) {if(data==null||data.length==0||data.length>OrderContentSnapshotCodec.MAX_BYTES)throw invalid();return json.readValue(data,type);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid admission payload");}
}
