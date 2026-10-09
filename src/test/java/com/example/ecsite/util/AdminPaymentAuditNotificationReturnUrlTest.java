package com.example.ecsite.util;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class AdminPaymentAuditNotificationReturnUrlTest {
    private static final String PATH="/admin/payment-audit-notifications";
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t"})
    void absentUrlUsesList(String url) {
        assertThat(AdminReturnUrlHelper.resolvePaymentAuditNotificationListReturnUrl(url)).isEqualTo(PATH);
    }
    @Test void acceptsOnlyCanonicalPathAndPreservesQuery() {
        String url=PATH+"?notificationId=42&status=SENT&warningType=LATEST_FAILURE&from=2026-10-08&to=2026-10-09&page=1&size=50";
        assertThat(AdminReturnUrlHelper.resolvePaymentAuditNotificationListReturnUrl(PATH)).isEqualTo(PATH);
        assertThat(AdminReturnUrlHelper.resolvePaymentAuditNotificationListReturnUrl(url)).isEqualTo(url);
    }
    @ParameterizedTest @ValueSource(strings={"https://evil.test/admin/payment-audit-notifications","//evil.test/admin/payment-audit-notifications","/admin/orders","/admin/payment-audit-notifications/","/admin/payment-audit-notifications-extra","/admin/payment-audit-notifications/../orders","/admin/payment-audit-notifications%2f..%2forders","/admin/%70ayment-audit-notifications","/admin/payment-audit-notifications#x","/admin/payment-audit-notifications?x=%ZZ","/admin/payment-audit-notifications?x=%0d","/admin/payment-audit-notifications?x=%0a","/admin/payment-audit-notifications?x=%00","/admin/payment-audit-notifications?x=%5c","/admin/payment-audit-notifications?x=%ff","/admin/payment-audit-notifications?x=\\evil","/admin/payment-audit-notifications?x=bad value","/admin/payment-audit-notifications?x=\n"})
    void rejectsMalformedOrNonListDestinations(String url) {
        assertThat(AdminReturnUrlHelper.resolvePaymentAuditNotificationListReturnUrl(url)).isEqualTo(PATH);
    }
}
