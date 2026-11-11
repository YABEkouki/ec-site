package com.example.ecsite.service.order.evidence;

import com.example.ecsite.service.pricing.*;
import com.example.ecsite.entity.OrderChargeType;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed, canonical V1 JSON. Rejects missing/null/unknown fields via exact re-encoding.
 * All persisted IDs and snapshot strings are mandatory; no trimming or Unicode normalization.
 * Array order is item ID, and charge displayOrder/type/ID. Returned bytes are fresh copies. */
public final class OrderContentSnapshotCodec {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxDocumentLength(MAX_BYTES).maxTokenCount(100_000).maxStringLength(1000)
                    .maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final TaxCalculator taxCalculator = new TaxCalculator();

    public byte[] encode(OrderContentSnapshot snapshot) {
        try {
            validate(snapshot);
            var sorted = new OrderContentSnapshot(1, snapshot.orderId(), snapshot.customerId(), snapshot.contentRevision(),
                    snapshot.items().stream().sorted(Comparator.comparingLong(OrderContentSnapshot.Item::orderItemId)).toList(),
                    snapshot.charges().stream().sorted(Comparator.comparingInt(OrderContentSnapshot.Charge::displayOrder)
                            .thenComparing(OrderContentSnapshot.Charge::type).thenComparingLong(OrderContentSnapshot.Charge::orderChargeId)).toList(),
                    snapshot.itemSubtotal(), snapshot.chargeTotal(), snapshot.taxAmount(), snapshot.totalAmount());
            byte[] bytes = mapper.writeValueAsBytes(sorted);
            if (bytes.length > MAX_BYTES) throw invalid();
            return bytes;
        } catch (RuntimeException e) { throw invalid(); }
    }

    /** Accepts ONLY canonical UTF-8 bytes; even harmless whitespace/key order/escapes are rejected. */
    public OrderContentSnapshot decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > MAX_BYTES) throw invalid();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var value = mapper.readValue(json, OrderContentSnapshot.class);
            if (!Arrays.equals(bytes, encode(value))) throw invalid();
            return value;
        } catch (Exception e) { throw invalid(); }
    }

    public String fingerprint(OrderContentSnapshot snapshot) { return OrderEvidenceDigests.sha256(encode(snapshot)); }

    /** Exact conversion of the persisted NUMERIC(5,2) percentage, not a floating point rate. */
    public static int taxRateBasisPoints(BigDecimal rate) {
        try {
            int value = Objects.requireNonNull(rate).movePointRight(2).intValueExact();
            if (value < 0 || value > 99999) throw invalid();
            return value;
        } catch (RuntimeException e) { throw invalid(); }
    }

    private void validate(OrderContentSnapshot s) {
        if (s == null || s.schemaVersion() != 1 || s.orderId() <= 0 || s.customerId() <= 0 || s.contentRevision() < 0
                || s.items().isEmpty() || s.items().size() > 1000 || s.charges().size() != 1) throw invalid();
        var ids = new HashSet<Long>();
        var pricing = new OrderPricingContext();
        int subtotal = 0;
        for (var i : s.items()) {
            if (i.orderItemId() <= 0 || !ids.add(i.orderItemId()) || i.productId() <= 0 || i.categoryId() <= 0
                    || i.unitPrice() < 0 || i.quantity() <= 0 || Math.multiplyExact(i.unitPrice(), i.quantity()) != i.subtotal()) throw invalid();
            string(i.productName(), 100); string(i.categoryName(), 100);
            tax(i.taxCategoryId(), i.taxCategoryCode(), i.taxCategoryName(), i.taxRateBasisPoints());
            subtotal = Math.addExact(subtotal, i.subtotal());
            pricing.addItem(i.unitPrice(), i.quantity(), i.taxCategoryId(), i.taxCategoryCode(), i.taxCategoryName(), rate(i.taxRateBasisPoints()));
        }
        var c = s.charges().getFirst();
        if (c.orderChargeId() <= 0 || !"SHIPPING".equals(c.type()) || c.amount() < 0) throw invalid();
        string(c.name(), 100); tax(c.taxCategoryId(), c.taxCategoryCode(), c.taxCategoryName(), c.taxRateBasisPoints());
        int total = Math.addExact(subtotal, c.amount());
        int tax = taxCalculator.calculate(pricing, List.of(new OrderChargeAmount(OrderChargeType.SHIPPING, c.name(), c.amount(),
                c.taxCategoryId(), c.taxCategoryCode(), c.taxCategoryName(), rate(c.taxRateBasisPoints()), c.displayOrder())));
        if (s.itemSubtotal() != subtotal || s.chargeTotal() != c.amount() || s.totalAmount() != total || total <= 0
                || s.taxAmount() != tax) throw invalid();
    }

    private static BigDecimal rate(int basisPoints) { return BigDecimal.valueOf(basisPoints, 2); }
    private static void tax(long id, String code, String name, int rate) {
        if (id <= 0 || rate < 0 || rate > 99999) throw invalid();
        string(code, 30); string(name, 100);
    }
    private static void string(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) > maxCodePoints) throw invalid();
        for (int n = 0; n < value.length(); n++) {
            char c = value.charAt(n);
            if (c == 0 || Character.isLowSurrogate(c)) throw invalid();
            if (Character.isHighSurrogate(c) && (++n >= value.length() || !Character.isLowSurrogate(value.charAt(n)))) throw invalid();
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid canonical order content snapshot"); }
}
