package com.example.ecsite.util;

public final class AdminReturnUrlHelper {

    private static final String ADMIN_PRODUCTS_PATH = "/admin/products";
    private static final String ADMIN_ORDERS_PATH = "/admin/orders";

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
                || returnUrl.startsWith(ADMIN_ORDERS_PATH + "?")) {
            return returnUrl;
        }

        return ADMIN_ORDERS_PATH;
    }

}
