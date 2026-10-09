package com.example.ecsite.util;

public final class AdminReturnUrlHelper {

    private static final String ADMIN_PRODUCTS_PATH = "/admin/products";
    private static final String ADMIN_ORDERS_PATH = "/admin/orders";
    private static final String ADMIN_PAYMENT_DISCREPANCIES_PATH = "/admin/payment-discrepancies";

    private AdminReturnUrlHelper() {
    }

    public static String resolveProductListReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return ADMIN_PRODUCTS_PATH;
        }

        if (returnUrl.equals(ADMIN_PRODUCTS_PATH)
                || returnUrl.startsWith(ADMIN_PRODUCTS_PATH + "?")) {
            return returnUrl;
        }

        return ADMIN_PRODUCTS_PATH;
    }

    public static String resolveOrderListReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return ADMIN_ORDERS_PATH;
        }

        if (returnUrl.equals(ADMIN_ORDERS_PATH)
                || returnUrl.startsWith(ADMIN_ORDERS_PATH + "?")
                || returnUrl.equals(ADMIN_PAYMENT_DISCREPANCIES_PATH)
                || returnUrl.startsWith(ADMIN_PAYMENT_DISCREPANCIES_PATH + "?")) {
            return returnUrl;
        }

        return ADMIN_ORDERS_PATH;
    }

    public static String resolvePaymentDiscrepancyListReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return ADMIN_PAYMENT_DISCREPANCIES_PATH;
        }

        if (returnUrl.equals(ADMIN_PAYMENT_DISCREPANCIES_PATH)
                || returnUrl.startsWith(
                        ADMIN_PAYMENT_DISCREPANCIES_PATH + "?")) {
            return returnUrl;
        }

        return ADMIN_PAYMENT_DISCREPANCIES_PATH;
    }

    /** Exact local list path only; no authority, fragments, controls or ambiguous separators. */
    public static String resolvePaymentAuditNotificationListReturnUrl(String returnUrl) {
        String fallback = "/admin/payment-audit-notifications";
        if (returnUrl == null || returnUrl.isBlank()) return fallback;
        try {
            java.net.URI uri = new java.net.URI(returnUrl);
            String decoded = uri.getQuery();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                    || !fallback.equals(uri.getRawPath())
                    || returnUrl.chars().anyMatch(c -> Character.isISOControl(c) || c == '\\')
                    || decoded != null && decoded.chars().anyMatch(c -> Character.isISOControl(c) || c == '\\' || c == 0xfffd)) {
                return fallback;
            }
            return returnUrl;
        } catch (java.net.URISyntaxException e) {
            return fallback;
        }
    }

}
