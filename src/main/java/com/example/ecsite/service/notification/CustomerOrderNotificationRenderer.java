package com.example.ecsite.service.notification;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.ecsite.service.warehouse.OrderShipmentTrackingReader.Tracking;

import tools.jackson.databind.JsonNode;

/**
 * Feature133-B: turns a recorded notification snapshot (V91 payload) into a plain-text mail. Pure: it reads no
 * database, clock or mail state, so the same payload always renders the same text.
 */
@Component
public class CustomerOrderNotificationRenderer {

    public record Rendered(String subject, String body) {}

    private static final String SHOP = "ECサイト";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm", Locale.JAPAN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN);

    private final String baseUrl;

    public CustomerOrderNotificationRenderer(@Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public Rendered render(String eventType, JsonNode payload, Optional<Tracking> tracking) {
        JsonNode order = payload.path("order");
        long orderId = order.path("orderId").asLong();
        var text = new StringBuilder();
        String subject;
        switch (eventType) {
            case "ORDER_ACCEPTED" -> {
                subject = "ご注文を承りました";
                lead(text, "ご注文を承りました。ご注文内容は以下のとおりです。");
                orderHeader(text, order);
                items(text, "ご注文内容", order);
                shipping(text, "お届け先", order.path("shipping"));
            }
            case "ITEMS_CHANGED" -> {
                subject = "ご注文内容の変更を承りました";
                lead(text, "ご注文内容の変更を承りました。変更後のご注文内容は以下のとおりです。");
                orderHeader(text, order);
                changes(text, payload.path("change"));
                items(text, "変更後のご注文内容", order);
            }
            case "ADDRESS_CHANGED" -> {
                subject = "お届け先の変更を承りました";
                lead(text, "お届け先の変更を承りました。");
                orderHeader(text, order);
                shipping(text, "変更前のお届け先", payload.path("oldShipping"));
                shipping(text, "変更後のお届け先", payload.path("newShipping"));
            }
            case "CHANGE_NOT_ADOPTED" -> {
                subject = "ご注文内容の変更は成立しませんでした";
                if ("HOLD".equals(payload.path("shippingDisposition").asString())) {
                    lead(text, "ご依頼いただいた注文内容の変更は、受付期限までに決済手続きが完了しなかったため成立しませんでした。",
                            "現在、決済の状況を確認しております。確認が終わるまで、発送の準備をお待ちいただく場合があります。",
                            "状況が変わりましたら、改めてお知らせいたします。");
                } else {
                    lead(text, "ご依頼いただいた注文内容の変更は、受付期限までに決済手続きが完了しなかったため成立しませんでした。",
                            "ご注文は変更前の内容で発送準備を進めます。");
                }
                orderHeader(text, order);
                items(text, "現在のご注文内容", order);
            }
            case "CHANGE_LATE_ADOPTION" -> {
                subject = "ご注文内容の変更手続きを続けています";
                lead(text, "ご依頼いただいた注文内容の変更は、受付期限の時点で決済手続きの途中でしたが、変更後の内容で手続きを続けています。",
                        "手続きが完了しましたら、変更後のご注文内容を改めてお知らせいたします。",
                        "それまでの間、発送の準備をお待ちいただく場合があります。");
                orderHeader(text, order);
            }
            case "ORDER_CANCELLED" -> {
                String actor = payload.path("changedByType").asString();
                if ("SYSTEM".equals(actor) && !payload.path("accepted").asBoolean()) {
                    subject = "ご注文は成立しませんでした";
                    lead(text, "決済が完了しなかったため、ご注文は成立しませんでした。",
                            "カードへのご請求はありません。お手数ですが、改めてご注文ください。");
                } else if ("USER".equals(actor)) {
                    subject = "ご注文のキャンセルを承りました";
                    lead(text, "ご注文のキャンセルを承りました。カードへのご請求はありません。");
                } else {
                    subject = "ご注文をキャンセルいたしました";
                    lead(text, "誠に勝手ながら、ご注文をキャンセルいたしました。カードへのご請求はありません。",
                            "ご不明な点がございましたら、お問い合わせください。");
                }
                orderHeader(text, order);
                items(text, "キャンセルしたご注文の内容", order);
            }
            case "ORDER_SHIPPED" -> {
                subject = "ご注文の商品を発送しました";
                lead(text, "ご注文の商品を発送しました。到着まで今しばらくお待ちください。");
                orderHeader(text, order);
                if (tracking.isPresent()) {
                    var t = tracking.get();
                    section(text, "配送情報");
                    text.append("配送業者：").append(t.carrier()).append('\n');
                    text.append("お問い合わせ番号（追跡番号）：").append(t.trackingNumber()).append('\n');
                    text.append("出荷日：").append(date(t.csvShippedOn())).append('\n');
                }
                items(text, "発送した商品", order);
                shipping(text, "お届け先", order.path("shipping"));
            }
            default -> throw new IllegalArgumentException("Unknown customer notification type");
        }
        footer(text, orderId);
        return new Rendered("【" + SHOP + "】" + subject + "（注文番号 " + orderId + "）", text.toString());
    }

    private static void lead(StringBuilder text, String... lines) {
        text.append("このたびは").append(SHOP).append("をご利用いただき、誠にありがとうございます。\n\n");
        for (String line : lines) text.append(line).append('\n');
    }

    private static void orderHeader(StringBuilder text, JsonNode order) {
        section(text, "ご注文情報");
        text.append("ご注文番号：").append(order.path("orderId").asLong()).append('\n');
        text.append("ご注文日時：").append(dateTime(order.path("orderedAt"))).append('\n');
    }

    private static void items(StringBuilder text, String title, JsonNode order) {
        section(text, title);
        for (JsonNode item : order.path("items")) {
            text.append(item.path("productName").asString()).append('\n');
            text.append("  ").append(yen(item.path("price"))).append(" × ").append(item.path("quantity").asInt())
                    .append("点　小計 ").append(yen(item.path("subtotal"))).append('\n');
        }
        text.append("商品小計（税込）：").append(yen(order.path("itemSubtotal"))).append('\n');
        for (JsonNode charge : order.path("charges"))
            text.append(charge.path("name").asString()).append("（税込）：").append(yen(charge.path("amount"))).append('\n');
        text.append("ご注文合計（税込）：").append(yen(order.path("totalAmount")))
                .append("（うち消費税 ").append(yen(order.path("taxAmount"))).append("）\n");
    }

    private static void changes(StringBuilder text, JsonNode change) {
        section(text, "変更内容");
        for (JsonNode item : change.path("items")) {
            String name = item.path("productName").asString();
            switch (item.path("changeType").asString()) {
                case "ADDED" -> text.append(name).append("：追加（").append(item.path("newQuantity").asInt()).append("点）\n");
                case "REMOVED" -> text.append(name).append("：削除（").append(item.path("oldQuantity").asInt()).append("点）\n");
                default -> text.append(name).append("：").append(item.path("oldQuantity").asInt()).append("点 → ")
                        .append(item.path("newQuantity").asInt()).append("点\n");
            }
        }
        for (JsonNode charge : change.path("charges")) {
            if (charge.path("oldAmount").asInt() == charge.path("newAmount").asInt()) continue;
            String name = charge.path("newName").isNull() || charge.path("newName").isMissingNode()
                    ? charge.path("oldName").asString() : charge.path("newName").asString();
            text.append(name).append("（税込）：").append(yen(charge.path("oldAmount"))).append(" → ")
                    .append(yen(charge.path("newAmount"))).append('\n');
        }
        text.append("ご注文合計（税込）：").append(yen(change.path("oldTotalAmount"))).append(" → ")
                .append(yen(change.path("newTotalAmount"))).append('\n');
    }

    private static void shipping(StringBuilder text, String title, JsonNode shipping) {
        section(text, title);
        text.append('〒').append(shipping.path("postalCode").asString()).append(' ')
                .append(shipping.path("prefecture").asString()).append(shipping.path("city").asString())
                .append(shipping.path("addressLine").asString()).append('\n');
        text.append(shipping.path("name").asString()).append(" 様\n");
    }

    private void footer(StringBuilder text, long orderId) {
        text.append("\nご注文の詳細は、以下のページからご確認いただけます（ログインが必要です）。\n");
        text.append(baseUrl).append("/orders/").append(orderId).append('\n');
        text.append("\n――――――――――――――――――――\n");
        text.append("このメールは送信専用のアドレスからお送りしています。ご返信いただいてもお答えできませんので、ご了承ください。\n");
        text.append(SHOP).append('\n');
    }

    private static void section(StringBuilder text, String title) {
        text.append("\n■").append(title).append('\n');
    }

    private static String yen(JsonNode amount) {
        return NumberFormat.getIntegerInstance(Locale.JAPAN).format(amount.asLong()) + "円";
    }

    private static String dateTime(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "-";
        return LocalDateTime.parse(value.asString()).format(DATE_TIME);
    }

    private static String date(LocalDate value) {
        return value == null ? "-" : value.format(DATE);
    }
}
