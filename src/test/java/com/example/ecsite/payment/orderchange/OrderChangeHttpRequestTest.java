package com.example.ecsite.payment.orderchange;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.UUID;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import org.junit.jupiter.api.Test;

class OrderChangeHttpRequestTest {
    final CaptureConnectionIdentity connection = new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp");
    final UUID operation = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Test void createsExactManualUnconfirmedRequestAndDefendsBytes() {
        var request=OrderChangeHttpRequest.create(connection,operation,10,5500,"stable-key");
        assertThat(new String(request.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
            "{\"amount\":5500,\"currency\":\"jpy\",\"payment_method_types\":[\"card\"],\"capture_method\":\"manual\",\"confirm\":false,\"metadata\":{\"order_id\":\"10\",\"order_change_operation_id\":\""+operation+"\"}}");
        byte[] body=request.body();body[0]=0;
        assertThat(request.body()[0]).isEqualTo((byte)'{');
        assertThat(request.purpose()).isEqualTo(PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        assertThat(request.fingerprint()).hasSize(32);
        assertThat(request.toString()).doesNotContain("stable-key");
    }
    @Test void restoreRejectsAnyAlteredBytesAndFingerprint() {
        var request=OrderChangeHttpRequest.create(connection,operation,10,5500,"stable-key");
        assertThatThrownBy(()->OrderChangeHttpRequest.restore(connection,operation,10,5500,"stable-key",request.purpose(),null,
            request.method(),request.path(),request.contentType(),1,new byte[]{1},1,request.fingerprint())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancelHasFixedPurposeAndPathAndCannotBecomeCreate() {
        var request=OrderChangeHttpRequest.cancel(connection,operation,10,5500,"same-cancel", "pfw_old",false);
        assertThat(request.path()).isEqualTo("/v2/payment_flows/pfw_old/cancel");
        assertThat(request.purpose()).isEqualTo(PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL);
        assertThat(new String(request.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{\"cancellation_reason\":\"requested_by_customer\"}");
    }
    @Test void replayWindowIsStrictAndNeverRefreshes() {
        var now=Instant.parse("2026-10-21T00:00:00Z");
        var saved=new StoredOrderChangeRequest(1,2,3,OrderChangeHttpRequest.create(connection,operation,10,5500,"stable-key"),now,now.plusSeconds(82800));
        saved.requireReplayAt(now.plusSeconds(82799));
        assertThatThrownBy(()->saved.requireReplayAt(now.plusSeconds(82800))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->saved.requireReplayAt(now.minusSeconds(1))).isInstanceOf(IllegalStateException.class);
    }
}
