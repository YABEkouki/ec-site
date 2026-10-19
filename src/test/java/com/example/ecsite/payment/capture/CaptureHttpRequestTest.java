package com.example.ecsite.payment.capture;

import static org.assertj.core.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import com.example.ecsite.config.CaptureConnectionProperties;

class CaptureHttpRequestTest {
    CaptureConnectionIdentity identity() {
        return new CaptureConnectionIdentity("primary", "merchant-verified", "TEST", "https://api.pay.jp");
    }
    CaptureHttpRequest request() { return CaptureHttpRequest.create(identity(), "pfw_contract", 5500, "same-key"); }

    @Test void canonicalBodyAndIndependentLengthPrefixedFingerprint() throws Exception {
        var r = request();
        assertThat(r.body()).isEqualTo("{\"amount_to_capture\":5500}".getBytes(StandardCharsets.UTF_8));
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v2/payment_flows/pfw_contract/capture");
        assertThat(r.contentType()).isEqualTo("application/json");
        var digest = MessageDigest.getInstance("SHA-256");
        for (String value : List.of("1", "1", "primary", "merchant-verified", "TEST", "https://api.pay.jp",
                "pfw_contract", "POST", r.path(), "application/json", "5500", "same-key")) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        byte[] body = r.body(); digest.update(ByteBuffer.allocate(4).putInt(body.length).array()); digest.update(body);
        assertThat(r.fingerprint()).isEqualTo(digest.digest());
    }
    @Test void restoreValidatesAndDefensivelyCopiesBothArrays() {
        var r = request(); byte[] body = r.body(); byte[] hash = r.fingerprint();
        var restored = CaptureHttpRequest.restore(identity(), r.flowId(), r.amount(), r.idempotencyKey(),
                r.method(), r.path(), r.contentType(), 1, body, 1, hash);
        body[0] = 0; hash[0] ^= 1;
        assertThat(restored.body()).isEqualTo(r.body()); assertThat(restored.fingerprint()).isEqualTo(r.fingerprint());
        byte[] returned = restored.body(); returned[0] = 0;
        byte[] returnedHash = restored.fingerprint(); returnedHash[0] ^= 1;
        assertThat(restored.body()).isEqualTo(r.body()); assertThat(restored.fingerprint()).isEqualTo(r.fingerprint());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","pfw_bad/path","pfw_bad?query","wrong","pfw_"})
    void rejectsInvalidFlow(String flow) { assertThatIllegalArgumentException().isThrownBy(() -> CaptureHttpRequest.create(identity(),flow,1,"key")); }
    @ParameterizedTest @ValueSource(ints={0,-1,Integer.MIN_VALUE})
    void rejectsInvalidAmount(int amount) { assertThatIllegalArgumentException().isThrownBy(() -> CaptureHttpRequest.create(identity(),"pfw_ok",amount,"key")); }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","key/other","key\n","キー"})
    void rejectsInvalidKey(String key) { assertThatIllegalArgumentException().isThrownBy(() -> CaptureHttpRequest.create(identity(),"pfw_ok",1,key)); }
    @Test void rejectsOversizedValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> CaptureHttpRequest.create(identity(),"pfw_"+"x".repeat(252),1,"key"));
        assertThatIllegalArgumentException().isThrownBy(() -> CaptureHttpRequest.create(identity(),"pfw_ok",1,"x".repeat(101)));
    }
    @ParameterizedTest @ValueSource(strings={"body","hash","method","path","content-type","body-version","hash-version","flow","amount","key","connection"})
    void rejectsCorruptStoredRequest(String field) {
        var r=request();byte[] body=r.body(),hash=r.fingerprint();
        if(field.equals("body")) body[0]=0;if(field.equals("hash")) hash[0]^=1;
        var c=field.equals("connection")?new CaptureConnectionIdentity("other","merchant-verified","TEST","https://api.pay.jp"):identity();
        assertThatIllegalArgumentException().isThrownBy(() -> CaptureHttpRequest.restore(c,
                field.equals("flow")?"pfw_other":r.flowId(),field.equals("amount")?1:r.amount(),field.equals("key")?"other":r.idempotencyKey(),
                field.equals("method")?"GET":r.method(),field.equals("path")?"/wrong":r.path(),field.equals("content-type")?"text/plain":r.contentType(),
                field.equals("body-version")?2:1,body,field.equals("hash-version")?2:1,hash));
    }
    @Test void missingConfigurationDoesNotFailBindingButCannotProduceIdentity() {
        var absent=new CaptureConnectionProperties(null,null,null,null);
        assertThatIllegalStateException().isThrownBy(() -> absent.requireIdentity(null));
    }
    @ParameterizedTest @ValueSource(strings={"connection","account","mode","origin","credentials","slash","length"})
    void rejectsInvalidConnection(String field) {
        assertThatIllegalArgumentException().isThrownBy(() -> new CaptureConnectionIdentity(
                field.equals("connection")?" ":field.equals("length")?"x".repeat(101):"primary",
                field.equals("account")?null:"merchant",field.equals("mode")?"UNKNOWN":"TEST",
                field.equals("origin")?"http://api.pay.jp":field.equals("credentials")?"https://user@api.pay.jp":field.equals("slash")?"https://api.pay.jp/":"https://api.pay.jp"));
    }

    @ParameterizedTest @ValueSource(strings={"TEST","LIVE"})
    void explicitIdentityMatchesActiveModeAndOrigin(String mode) {
        String prefix=mode.equals("TEST")?"test_":"live_";
        var properties=new CaptureConnectionProperties("primary","verified-merchant",mode,"https://api.pay.jp");
        var active=new com.example.ecsite.config.PayJpProperties("pk_"+prefix+"dummy","sk_"+prefix+"dummy","https://api.pay.jp","unused");
        assertThat(properties.requireIdentity(active).mode()).isEqualTo(mode);
    }
    @ParameterizedTest @ValueSource(strings={"null","mode","public","origin"})
    void rejectsConnectionMismatchWithoutAffectingExistingGateway(String field) {
        var p=new CaptureConnectionProperties("primary","merchant","TEST","https://api.pay.jp");
        var active=field.equals("null")?null:new com.example.ecsite.config.PayJpProperties(
                field.equals("public")?"pk_live_dummy":"pk_test_dummy",field.equals("mode")?"sk_live_dummy":"sk_test_dummy",
                field.equals("origin")?"http://localhost:1":"https://api.pay.jp","unused");
        assertThatIllegalStateException().isThrownBy(()->p.requireIdentity(active));
    }
}
