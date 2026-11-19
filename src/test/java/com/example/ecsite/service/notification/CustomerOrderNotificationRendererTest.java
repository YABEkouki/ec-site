package com.example.ecsite.service.notification;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.ecsite.service.warehouse.OrderShipmentTrackingReader.Tracking;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportLineState;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CustomerOrderNotificationRendererTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CustomerOrderNotificationRenderer renderer = new CustomerOrderNotificationRenderer("http://shop.test/");

    private static final String ORDER = """
        {"orderId":22,"orderedAt":"2026-11-16T10:05:30.123456","acceptedAt":"2026-11-16T10:06:00","status":"ORDERED",
         "itemSubtotal":3000,"chargeTotal":550,"taxAmount":322,"totalAmount":3550,
         "items":[{"productName":"珈琲豆","price":1000,"quantity":3,"subtotal":3000,"taxRate":8.00}],
         "charges":[{"chargeType":"SHIPPING","name":"送料","amount":550}],
         "shipping":{"name":"山田 太郎","postalCode":"100-0001","prefecture":"東京都","city":"千代田区","addressLine":"1-1"}}
        """;

    private JsonNode payload(String type, String extra) {
        return JSON.readTree("{\"version\":1,\"eventType\":\"" + type + "\",\"order\":" + ORDER + (extra.isEmpty() ? "" : "," + extra) + "}");
    }

    private CustomerOrderNotificationRenderer.Rendered render(String type, String extra) {
        return renderer.render(type, payload(type, extra), Optional.empty());
    }

    @Test void acceptedMailListsItemsAmountsAddressAndLink() {
        var mail = render("ORDER_ACCEPTED", "\"acceptedAt\":\"2026-11-16T10:06:00\"");
        assertThat(mail.subject()).isEqualTo("【ECサイト】ご注文を承りました（注文番号 22）");
        assertThat(mail.body()).contains("ご注文番号：22", "ご注文日時：2026年11月16日 10:05", "珈琲豆",
                "1,000円 × 3点　小計 3,000円", "送料（税込）：550円", "ご注文合計（税込）：3,550円（うち消費税 322円）",
                "〒100-0001 東京都千代田区1-1", "山田 太郎 様", "http://shop.test/orders/22");
        assertThat(mail.body()).doesNotContain("phone").doesNotContain("null");
    }

    @Test void itemChangeShowsEachLineAndTheTotalDifference() {
        var mail = render("ITEMS_CHANGED", """
            "change":{"oldTotalAmount":4550,"newTotalAmount":3550,
              "items":[{"productName":"珈琲豆","changeType":"UPDATED","oldQuantity":4,"newQuantity":3},
                       {"productName":"紅茶","changeType":"ADDED","newQuantity":2},
                       {"productName":"緑茶","changeType":"REMOVED","oldQuantity":1}],
              "charges":[{"chargeType":"SHIPPING","changeType":"UPDATED","oldName":"送料","newName":"送料","oldAmount":0,"newAmount":550}]}
            """);
        assertThat(mail.subject()).contains("ご注文内容の変更を承りました");
        assertThat(mail.body()).contains("珈琲豆：4点 → 3点", "紅茶：追加（2点）", "緑茶：削除（1点）",
                "送料（税込）：0円 → 550円", "ご注文合計（税込）：4,550円 → 3,550円", "■変更後のご注文内容");
    }

    @Test void addressChangeShowsBeforeAndAfterWithoutPhone() {
        var mail = render("ADDRESS_CHANGED", """
            "oldShipping":{"name":"山田 太郎","postalCode":"100-0001","prefecture":"東京都","city":"千代田区","addressLine":"1-1"},
            "newShipping":{"name":"山田 花子","postalCode":"150-0001","prefecture":"東京都","city":"渋谷区","addressLine":"2-2"}
            """);
        assertThat(mail.body()).contains("■変更前のお届け先\n〒100-0001 東京都千代田区1-1\n山田 太郎 様",
                "■変更後のお届け先\n〒150-0001 東京都渋谷区2-2\n山田 花子 様");
    }

    @Test void notAdoptedWordingDependsOnWhetherShippingContinues() {
        var original = render("CHANGE_NOT_ADOPTED", "\"shippingDisposition\":\"ORIGINAL\"");
        assertThat(original.subject()).contains("ご注文内容の変更は成立しませんでした");
        assertThat(original.body()).contains("ご注文は変更前の内容で発送準備を進めます。");
        var hold = render("CHANGE_NOT_ADOPTED", "\"shippingDisposition\":\"HOLD\"");
        assertThat(hold.body()).contains("決済の状況を確認しております").doesNotContain("変更前の内容で発送");
        assertThat(render("CHANGE_LATE_ADOPTION", "\"shippingDisposition\":\"HOLD\"").body())
                .contains("変更後の内容で手続きを続けています").doesNotContain("■ご注文内容");
    }

    @Test void cancellationWordingFollowsTheActorAndAcceptance() {
        assertThat(render("ORDER_CANCELLED", "\"changedByType\":\"USER\",\"accepted\":true").subject())
                .contains("ご注文のキャンセルを承りました");
        assertThat(render("ORDER_CANCELLED", "\"changedByType\":\"ADMIN\",\"accepted\":true").subject())
                .contains("ご注文をキャンセルいたしました");
        var failed = render("ORDER_CANCELLED", "\"changedByType\":\"SYSTEM\",\"accepted\":false");
        assertThat(failed.subject()).contains("ご注文は成立しませんでした");
        assertThat(failed.body()).contains("決済が完了しなかったため、ご注文は成立しませんでした。");
        assertThat(render("ORDER_CANCELLED", "\"changedByType\":\"SYSTEM\",\"accepted\":true").subject())
                .contains("ご注文をキャンセルいたしました");
    }

    /** Feature135-C2 (V96): an expired-authorization cancellation asks for a new order; older payloads lack the key. */
    @Test void anExpiredAuthorizationCancellationAsksForANewOrder() {
        var expired = render("ORDER_CANCELLED", "\"changedByType\":\"ADMIN\",\"accepted\":true,\"authorizationExpired\":true");
        assertThat(expired.subject()).contains("ご注文をキャンセルいたしました（お支払い期限切れ）");
        assertThat(expired.body()).contains("有効期限（与信期限）が切れたため", "カードへのご請求はありません", "改めてご注文ください",
                "キャンセルしたご注文の内容").doesNotContain("誠に勝手ながら");
        var regular = render("ORDER_CANCELLED", "\"changedByType\":\"ADMIN\",\"accepted\":true,\"authorizationExpired\":false");
        assertThat(regular.subject()).doesNotContain("お支払い期限切れ");
        assertThat(regular.body()).contains("誠に勝手ながら");
    }

    @Test void shipmentShowsTrackingOnlyWhenTheWarehouseShippedIt() {
        var tracking = new Tracking("ヤマト運輸", "1234-5678-9012", LocalDate.of(2026, 11, 17), 1L,
                WarehouseShipmentImportLineState.SHIPPED);
        var warehouse = renderer.render("ORDER_SHIPPED", payload("ORDER_SHIPPED", "\"warehouseImport\":true"), Optional.of(tracking));
        assertThat(warehouse.subject()).isEqualTo("【ECサイト】ご注文の商品を発送しました（注文番号 22）");
        assertThat(warehouse.body()).contains("■配送情報", "配送業者：ヤマト運輸", "お問い合わせ番号（追跡番号）：1234-5678-9012",
                "出荷日：2026年11月17日", "■発送した商品", "■お届け先").doesNotContain("SHIPPED");
        var admin = render("ORDER_SHIPPED", "\"warehouseImport\":false");
        assertThat(admin.body()).doesNotContain("■配送情報").contains("ご注文の商品を発送しました。");
    }

    @Test void unknownTypesAreRejected() {
        assertThatThrownBy(() -> render("SOMETHING", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
