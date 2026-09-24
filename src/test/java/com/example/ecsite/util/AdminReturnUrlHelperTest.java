package com.example.ecsite.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminReturnUrlHelperTest {

    @Test
    void returnsProductListWhenReturnUrlIsNull() {
        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl(null))
                .isEqualTo("/admin/products");
    }

    @Test
    void returnsProductListWhenReturnUrlIsBlank() {
        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl("   "))
                .isEqualTo("/admin/products");
    }

    @Test
    void acceptsProductListUrl() {
        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl("/admin/products"))
                .isEqualTo("/admin/products");
    }

    @Test
    void acceptsProductListUrlWithQueryParameters() {
        String returnUrl = "/admin/products?keyword=coffee&categoryId=1&sort=priceAsc&page=2";

        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl(returnUrl))
                .isEqualTo(returnUrl);
    }

    @Test
    void rejectsProductEditUrl() {
        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl(
                "/admin/products/1/edit"))
                .isEqualTo("/admin/products");
    }

    @Test
    void rejectsOtherAdminUrl() {
        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl(
                "/admin/orders?page=2"))
                .isEqualTo("/admin/products");
    }

    @Test
    void rejectsAbsoluteExternalUrl() {
        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl(
                "https://example.com"))
                .isEqualTo("/admin/products");
    }

    @Test
    void rejectsSchemeRelativeExternalUrl() {
        assertThat(AdminReturnUrlHelper.resolveProductListReturnUrl(
                "//example.com"))
                .isEqualTo("/admin/products");
    }

    @Test
    void returnsOrderListWhenReturnUrlIsNull() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl(null))
                .isEqualTo("/admin/orders");
    }

    @Test
    void returnsOrderListWhenReturnUrlIsBlank() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl("   "))
                .isEqualTo("/admin/orders");
    }

    @Test
    void acceptsOrderListUrl() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl("/admin/orders"))
                .isEqualTo("/admin/orders");
    }

    @Test
    void acceptsOrderListUrlWithQueryParameters() {
        String returnUrl = "/admin/orders?status=PAID&handlingStatus=NEEDS_ACTION&page=2&size=10";

        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl(returnUrl))
                .isEqualTo(returnUrl);
    }

    @Test
    void rejectsOrderDetailUrl() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl(
                "/admin/orders/1"))
                .isEqualTo("/admin/orders");
    }

    @Test
    void rejectsActionRequiredOrderListUrl() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl(
                "/admin/orders/action-required"))
                .isEqualTo("/admin/orders");
    }

    @Test
    void rejectsOtherAdminUrlForOrderReturn() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl(
                "/admin/products?page=2"))
                .isEqualTo("/admin/orders");
    }

    @Test
    void rejectsAbsoluteExternalUrlForOrderReturn() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl(
                "https://example.com"))
                .isEqualTo("/admin/orders");
    }

    @Test
    void rejectsSchemeRelativeExternalUrlForOrderReturn() {
        assertThat(AdminReturnUrlHelper.resolveOrderListReturnUrl(
                "//example.com"))
                .isEqualTo("/admin/orders");
    }

}
