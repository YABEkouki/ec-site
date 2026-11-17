package com.example.ecsite.service.notification;

import java.util.LinkedHashMap;
import java.util.Map;

/** Feature133-C: Japanese labels for administrator screens. */
public final class CustomerOrderNotificationLabels {
    private CustomerOrderNotificationLabels() {}

    public static final Map<String, String> EVENTS;
    static {
        var events = new LinkedHashMap<String, String>();
        events.put("ORDER_ACCEPTED", "注文完了");
        events.put("ITEMS_CHANGED", "注文内容の変更");
        events.put("ADDRESS_CHANGED", "お届け先の変更");
        events.put("CHANGE_NOT_ADOPTED", "変更不成立");
        events.put("CHANGE_LATE_ADOPTION", "変更手続きの継続（遅延成立）");
        events.put("ORDER_CANCELLED", "注文取消");
        events.put("ORDER_SHIPPED", "発送完了");
        EVENTS = java.util.Collections.unmodifiableMap(events);
    }

    public static boolean isEvent(String type) {
        return EVENTS.containsKey(type);
    }

    public static String event(String type) {
        return EVENTS.getOrDefault(type, type);
    }

    public static String status(String status, String closeReason) {
        if (status == null) return "";
        return switch (status) {
            case "PENDING" -> "送信待ち";
            case "CLAIMED", "SENDING" -> "送信中";
            case "RETRY_WAIT" -> "再試行待ち";
            case "SENT" -> "送信済み";
            case "EXHAUSTED" -> "DELIVERY_UNCERTAIN".equals(closeReason) ? "送信結果不明" : "送信失敗";
            case "SKIPPED" -> "送信対象外（メール未確認）";
            case "EXPIRED" -> "期限切れ（未送信）";
            default -> status;
        };
    }

    public static String attempt(String result) {
        return switch (result) {
            case "IN_PROGRESS" -> "送信中";
            case "SUCCESS" -> "成功";
            case "FAILURE" -> "失敗（未送信）";
            case "UNKNOWN" -> "結果不明";
            default -> result;
        };
    }
}
