package com.example.ecsite.util;

public final class AdminReturnUrlHelper {

    private static final String ADMIN_PRODUCTS_PATH = "/admin/products";

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
}
