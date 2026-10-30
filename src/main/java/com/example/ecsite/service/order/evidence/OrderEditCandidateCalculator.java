package com.example.ecsite.service.order.evidence;

import java.math.BigDecimal;
import java.util.*;
import com.example.ecsite.service.pricing.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static com.example.ecsite.service.order.evidence.OrderChangeRoutePolicy.Route;

/** Pure proposal only, deliberately not a Spring bean. No repository, transaction, lock or gateway.
 * Caller supplies complete consistent saved evidence and current catalog facts. Availability of stock
 * is not promised here: 2E/2F must lock and revalidate admission, evidence, catalog and positive deltas.
 * Preview snapshots retain current revision; new line keys MUST be replaced by real IDs at commit. */
public final class OrderEditCandidateCalculator {
    private final OrderContentSnapshotCodec codec=new OrderContentSnapshotCodec();
    private final OrderChangeRoutePolicy routes=new OrderChangeRoutePolicy();
    private final OrderAmountCalculator amounts=new OrderAmountCalculator(new ShippingChargeCalculator(),new TaxCalculator());

    public Result calculate(Context context, Request request, List<Offer> catalog) {
        return calculate(context,request,catalog,true);
    }
    /** Candidate validation only: V71's original commit evidence decides formal timeliness.
     * The caller supplies the real context time; no fabricated time or modified deadline. */
    public Result calculateForAdmission(Context context, Request request, List<Offer> catalog) {
        return calculate(context,request,catalog,false);
    }
    private Result calculate(Context context, Request request, List<Offer> catalog,boolean previewDeadline) {
        try {
            Objects.requireNonNull(context);Objects.requireNonNull(request);
            var facts=Objects.requireNonNull(context.facts());var before=Objects.requireNonNull(facts.current());
            if(!admitted(context,request,before,previewDeadline))return blocked(Reason.ADMISSION_DENIED);
            if(!new OrderPaymentEvidenceValidator().validate(facts).locallyVerified())return blocked(Reason.INVALID_EVIDENCE);
            if(request.lines().size()>1000) return blocked(Reason.INVALID_INPUT);
            var offers=new TreeMap<Long,Offer>();
            for(var offer:catalog)if(offer.productId()<=0||offers.putIfAbsent(offer.productId(),offer)!=null) return blocked(Reason.INVALID_INPUT);
            var saved=new TreeMap<Long,OrderContentSnapshot.Item>();
            before.items().forEach(item->saved.put(item.orderItemId(),item));
            var used=new HashSet<Long>();var retained=new ArrayList<OrderContentSnapshot.Item>();
            var additions=new TreeMap<Long,Integer>();var increases=new HashSet<Long>();
            for(var line:request.lines()) {
                if(line.productId()<=0||line.quantity()<0) return blocked(Reason.INVALID_INPUT);
                var old=line.existingItemId()==null?null:saved.get(line.existingItemId());
                if(line.existingItemId()!=null&&(old==null||!used.add(line.existingItemId())))return blocked(Reason.INVALID_INPUT);
                if(line.quantity()==0)continue;
                if(old!=null&&old.productId()==line.productId()) {
                    retained.add(withQuantity(old,line.quantity()));
                    if(line.quantity()>old.quantity())increases.add(line.productId());
                } else additions.merge(line.productId(),line.quantity(),Math::addExact);
            }
            for(long id:increases)if(!available(offers.get(id)))return blocked(Reason.UNAVAILABLE_PRODUCT);
            var newRefs=new ArrayList<NewLineReference>();var bindings=new ArrayList<Offer>();
            long previewId=1;
            for(var entry:additions.entrySet()) {
                var offer=offers.get(entry.getKey());if(!available(offer))return blocked(Reason.UNAVAILABLE_PRODUCT);
                while(saved.containsKey(previewId))previewId=Math.incrementExact(previewId);
                retained.add(item(previewId,offer,entry.getValue()));newRefs.add(new NewLineReference(previewId,offer.productId()));
                bindings.add(offer);previewId=Math.incrementExact(previewId);
            }
            if(retained.isEmpty()||retained.size()>1000)return blocked(Reason.INVALID_INPUT);
            retained.sort(Comparator.comparingLong(OrderContentSnapshot.Item::orderItemId));
            var pricing=new OrderPricingContext();int subtotal=0;
            for(var i:retained) {
                subtotal=Math.addExact(subtotal,i.subtotal());
                pricing.addItem(i.unitPrice(),i.quantity(),i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),rate(i.taxRateBasisPoints()));
            }
            var shipping=before.charges().getFirst();
            // Detect true no-op before applying today's shipping tariff or charge presentation.
            var historicalCharges=List.of(new OrderChargeAmount(com.example.ecsite.entity.OrderChargeType.SHIPPING,
                shipping.name(),shipping.amount(),shipping.taxCategoryId(),shipping.taxCategoryCode(),shipping.taxCategoryName(),rate(shipping.taxRateBasisPoints()),shipping.displayOrder()));
            // Validate aggregate quantities even for no-op (zero-priced lines can be very large).
            quantities(before.items());quantities(retained);
            if(subtotal==before.itemSubtotal()) {
                var unchangedProbe=snapshot(before,retained,before.charges(),subtotal,before.chargeTotal(),new TaxCalculator().calculate(pricing,historicalCharges));
                if(routes.decide(facts,unchangedProbe).route()==Route.NO_CHANGE)return new Result(Route.NO_CHANGE,Reason.CALCULATED,
                    new Candidate(facts,before,List.of(),List.of(),List.of()));
            }
            var charge= new ShippingChargeCalculator().calculate(pricing,new ChargeTaxSnapshot(shipping.taxCategoryId(),shipping.taxCategoryCode(),shipping.taxCategoryName(),rate(shipping.taxRateBasisPoints())));
            Math.addExact(subtotal,charge.amount()); // guard calculators' int sums before invoking
            var amount=amounts.calculate(pricing,new ChargeTaxSnapshot(shipping.taxCategoryId(),shipping.taxCategoryCode(),shipping.taxCategoryName(),rate(shipping.taxRateBasisPoints())));
            var calculatedCharge=amount.charges().getFirst();
            var charges=List.of(new OrderContentSnapshot.Charge(shipping.orderChargeId(),"SHIPPING",calculatedCharge.name(),calculatedCharge.amount(),shipping.taxCategoryId(),shipping.taxCategoryCode(),shipping.taxCategoryName(),shipping.taxRateBasisPoints(),calculatedCharge.displayOrder()));
            var preview=snapshot(before,retained,charges,amount.itemSubtotal(),amount.chargeTotal(),amount.taxAmount());
            preview=codec.decode(codec.encode(preview));
            var route=routes.decide(facts,preview).route();
            if(route==Route.BLOCKED)return blocked(Reason.INVALID_EVIDENCE);
            return new Result(route,Reason.CALCULATED,new Candidate(facts,preview,newRefs,bindings,deltas(before.items(),retained)));
        } catch(RuntimeException invalid) { return blocked(Reason.INVALID_INPUT); }
    }

    /** Rebuild from freshly read inputs; never accept a stale preview merely because its total agrees.
     * Stock and deadline admission must additionally be rechecked UNDER LOCKS in the future writer. */
    public Result revalidate(Candidate confirmed, Context fresh, Request request, List<Offer> catalog) {
        var result=calculate(fresh,request,catalog);
        if(result.route()==Route.BLOCKED)return result;
        return confirmed!=null&&confirmed.equals(result.candidate())?result:blocked(Reason.CONFIRMATION_CHANGED);
    }
    private static boolean admitted(Context c,Request r,OrderContentSnapshot s,boolean previewDeadline) {
        return r.orderId()==s.orderId()&&r.expectedRevision()==s.contentRevision()&&c.customerId()==s.customerId()
            &&"ORDERED".equals(c.status())&&c.orderedAt()!=null&&c.deadline()!=null&&c.now()!=null
            &&c.deadline().isAfter(c.orderedAt())&&(!previewDeadline||c.now().isBefore(c.deadline()))
            &&c.preparationStartedAt()==null&&c.preparationAccountId()==null&&c.preparationUsername()==null;
    }
    private static boolean available(Offer o) { return o!=null&&o.active(); }
    private static BigDecimal rate(int basisPoints) { return BigDecimal.valueOf(basisPoints,2); }
    private static OrderContentSnapshot.Item withQuantity(OrderContentSnapshot.Item i,int q) {
        return new OrderContentSnapshot.Item(i.orderItemId(),i.productId(),i.productName(),i.categoryId(),i.categoryName(),i.unitPrice(),q,Math.multiplyExact(i.unitPrice(),q),i.taxCategoryId(),i.taxCategoryCode(),i.taxCategoryName(),i.taxRateBasisPoints());
    }
    private static OrderContentSnapshot.Item item(long id,Offer o,int q) {
        return new OrderContentSnapshot.Item(id,o.productId(),o.productName(),o.categoryId(),o.categoryName(),o.unitPrice(),q,Math.multiplyExact(o.unitPrice(),q),o.taxCategoryId(),o.taxCategoryCode(),o.taxCategoryName(),o.taxRateBasisPoints());
    }
    private static OrderContentSnapshot snapshot(OrderContentSnapshot b,List<OrderContentSnapshot.Item> items,List<OrderContentSnapshot.Charge> charges,int subtotal,int charge,int tax) {
        return new OrderContentSnapshot(1,b.orderId(),b.customerId(),b.contentRevision(),items,charges,subtotal,charge,tax,Math.addExact(subtotal,charge));
    }
    private static List<StockDelta> deltas(List<OrderContentSnapshot.Item> before,List<OrderContentSnapshot.Item> after) {
        var b=quantities(before);var a=quantities(after);var ids=new TreeSet<>(b.keySet());ids.addAll(a.keySet());
        return ids.stream().map(id->new StockDelta(id,b.getOrDefault(id,0),a.getOrDefault(id,0))).filter(d->d.delta()!=0).toList();
    }
    private static Map<Long,Integer> quantities(List<OrderContentSnapshot.Item> items) {
        var result=new TreeMap<Long,Integer>();for(var i:items)result.merge(i.productId(),i.quantity(),Math::addExact);return result;
    }
    private static Result blocked(Reason reason) { return new Result(Route.BLOCKED,reason,null); }
}
