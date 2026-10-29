package com.example.ecsite.service.order.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class OrderContentSnapshotCodecTest {
    private final OrderContentSnapshotCodec codec = new OrderContentSnapshotCodec();
    static OrderContentSnapshot snapshot(int revision, int price, long product) {
        int shipping = price >= 5000 ? 0 : 550;
        int tax = java.math.BigDecimal.valueOf(price + shipping).multiply(java.math.BigDecimal.TEN)
                .divide(java.math.BigDecimal.valueOf(110), 0, java.math.RoundingMode.DOWN).intValueExact();
        return new OrderContentSnapshot(1, 1, 2, revision,
                List.of(new OrderContentSnapshot.Item(3, product, "商品", 4, "分類", price, 1, price, 5, "STANDARD", "標準", 1000)),
                List.of(new OrderContentSnapshot.Charge(6, "SHIPPING", "送料", shipping, 5, "STANDARD", "標準", 1000, 10)),
                price, shipping, tax, price + shipping);
    }
    static final String JSON = "{\"schemaVersion\":1,\"orderId\":1,\"customerId\":2,\"contentRevision\":0,\"items\":[{\"orderItemId\":3,\"productId\":7,\"productName\":\"商品\",\"categoryId\":4,\"categoryName\":\"分類\",\"unitPrice\":6000,\"quantity\":1,\"subtotal\":6000,\"taxCategoryId\":5,\"taxCategoryCode\":\"STANDARD\",\"taxCategoryName\":\"標準\",\"taxRateBasisPoints\":1000}],\"charges\":[{\"orderChargeId\":6,\"type\":\"SHIPPING\",\"name\":\"送料\",\"amount\":0,\"taxCategoryId\":5,\"taxCategoryCode\":\"STANDARD\",\"taxCategoryName\":\"標準\",\"taxRateBasisPoints\":1000,\"displayOrder\":10}],\"itemSubtotal\":6000,\"chargeTotal\":0,\"taxAmount\":545,\"totalAmount\":6000}";

    @Test void canonicalBytesAndRoundTripAreFixed() {
        var bytes = codec.encode(snapshot(0, 6000, 7));
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(JSON);
        assertThat(codec.encode(codec.decode(bytes))).isEqualTo(bytes);
        assertThat(codec.encode(snapshot(0, 6000, 7))).isEqualTo(bytes);
    }
    @ParameterizedTest @ValueSource(strings = {"space", "unknown", "duplicate", "version", "float", "exponent", "null", "trailing", "escape", "amount", "quantity", "subtotal", "tax", "shipping"})
    void rejectsNonCanonicalOrInvalidFacts(String mutation) {
        String json = switch (mutation) {
            case "space" -> " " + JSON;
            case "unknown" -> JSON.replace("\"schemaVersion\":1", "\"extra\":1,\"schemaVersion\":1");
            case "duplicate" -> JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
            case "version" -> JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            case "float" -> JSON.replace("\"orderId\":1", "\"orderId\":1.0");
            case "exponent" -> JSON.replace("\"orderId\":1", "\"orderId\":1e0");
            case "null" -> JSON.replace("\"productName\":\"商品\"", "\"productName\":null");
            case "trailing" -> JSON + "{}";
            case "escape" -> JSON.replace("商品", "\\u5546品");
            case "amount" -> JSON.replace("\"totalAmount\":6000", "\"totalAmount\":5999");
            case "quantity" -> JSON.replace("\"quantity\":1", "\"quantity\":0");
            case "subtotal" -> JSON.replace("\"subtotal\":6000", "\"subtotal\":6001");
            case "tax" -> JSON.replace("\"taxAmount\":545", "\"taxAmount\":546");
            default -> JSON.replace("\"amount\":0", "\"amount\":550");
        };
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void preservesWhitespaceAndUnicodeWithoutNormalizing() {
        var json = JSON.replace("商品", " 商品😀é ");
        assertThat(codec.encode(codec.decode(json.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(json.getBytes(StandardCharsets.UTF_8));
        assertThat(codec.fingerprint(codec.decode(json.getBytes(StandardCharsets.UTF_8))))
                .isNotEqualTo(codec.fingerprint(codec.decode(json.replace("é", "é").getBytes(StandardCharsets.UTF_8))));
    }
    @Test void malformedUtf8AndSurrogatesAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(new byte[]{(byte) 0xc3, 0x28}));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(JSON.replace("商品", "\\ud800").getBytes(StandardCharsets.UTF_8)));
    }
    @Test void sortsItemsAndKeepsDifferentPriceAndTaxSnapshots() {
        var a = new OrderContentSnapshot.Item(9, 7, "商品", 4, "分類", 3000, 1, 3000, 5, "REDUCED", "軽減", 800);
        var b = new OrderContentSnapshot.Item(3, 7, "商品", 4, "分類", 3000, 1, 3000, 5, "STANDARD", "標準", 1000);
        var base = snapshot(0, 6000, 7);
        var s = new OrderContentSnapshot(1, 1, 2, 0, List.of(a, b), base.charges(), 6000, 0, 494, 6000);
        assertThat(codec.decode(codec.encode(s)).items()).containsExactly(b, a);
    }
    @Test void duplicateIdsOverflowAndUnsupportedRatesAreRejected() {
        var base = snapshot(0, 6000, 7);
        var duplicate = new OrderContentSnapshot(1, 1, 2, 0, List.of(base.items().getFirst(), base.items().getFirst()), base.charges(), 12000, 0, 1090, 12000);
        assertThatIllegalArgumentException().isThrownBy(() -> codec.encode(duplicate));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderContentSnapshotCodec.taxRateBasisPoints(new java.math.BigDecimal("8.001")));
        assertThat(OrderContentSnapshotCodec.taxRateBasisPoints(new java.math.BigDecimal("8.00"))).isEqualTo(800);
    }
    @Test void preservesConsistentStoredShippingRatherThanApplyingCurrentTariffToHistory() {
        var base=snapshot(0,6000,7);var c=base.charges().getFirst();
        var historical=new OrderContentSnapshot(1,1,2,0,base.items(),
                List.of(new OrderContentSnapshot.Charge(c.orderChargeId(),c.type(),c.name(),550,c.taxCategoryId(),c.taxCategoryCode(),c.taxCategoryName(),c.taxRateBasisPoints(),c.displayOrder())),
                6000,550,595,6550);
        assertThat(codec.decode(codec.encode(historical))).isEqualTo(historical);
    }
    @ParameterizedTest @ValueSource(strings={"missing","nestedUnknown","nestedDuplicate","negativePrice","negativeRate","zeroId","negativeRevision","nullCategory","nul","oversized"})
    void rejectsMissingFieldsAndPersistentlyImpossibleValues(String mutation) {
        String json=switch(mutation) {
            case "missing" -> JSON.replace("\"displayOrder\":10", "\"unused\":10");
            case "nestedUnknown" -> JSON.replace("\"quantity\":1", "\"quantity\":1,\"stock\":99");
            case "nestedDuplicate" -> JSON.replace("\"quantity\":1", "\"quantity\":1,\"quantity\":1");
            case "negativePrice" -> JSON.replace("\"unitPrice\":6000", "\"unitPrice\":-1");
            case "negativeRate" -> JSON.replace("\"taxRateBasisPoints\":1000", "\"taxRateBasisPoints\":-1");
            case "zeroId" -> JSON.replace("\"orderItemId\":3", "\"orderItemId\":0");
            case "negativeRevision" -> JSON.replace("\"contentRevision\":0", "\"contentRevision\":-1");
            case "nullCategory" -> JSON.replace("\"categoryName\":\"分類\"", "\"categoryName\":null");
            case "nul" -> JSON.replace("商品", "\\u0000");
            default -> JSON.replace("商品", "あ".repeat(101));
        };
        assertThatIllegalArgumentException().isThrownBy(()->codec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void rejectsOverflowAndArrayReorderingButAllowsCanonicalSortingOnEncode() {
        var base=snapshot(0,6000,7);
        var huge=new OrderContentSnapshot.Item(3,7,"商品",4,"分類",Integer.MAX_VALUE,2,-2,5,"STANDARD","標準",1000);
        assertThatIllegalArgumentException().isThrownBy(()->codec.encode(new OrderContentSnapshot(1,1,2,0,List.of(huge),base.charges(),6000,0,545,6000)));
        var first=new OrderContentSnapshot.Item(3,7,"商品",4,"分類",3000,1,3000,5,"STANDARD","標準",1000);
        var second=new OrderContentSnapshot.Item(9,7,"商品",4,"分類",3000,1,3000,5,"STANDARD","標準",1000);
        var s=new OrderContentSnapshot(1,1,2,0,List.of(second,first),base.charges(),6000,0,545,6000);
        var canonical=codec.encode(s);
        var text=new String(canonical,StandardCharsets.UTF_8).replace("\"orderItemId\":3", "\"orderItemId\":99");
        assertThatIllegalArgumentException().isThrownBy(()->codec.decode(text.getBytes(StandardCharsets.UTF_8)));
        assertThat(codec.decode(canonical).items()).containsExactly(first,second);
    }
}
