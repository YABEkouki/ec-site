package com.example.ecsite.service.order.evidence;

import org.junit.jupiter.api.Test;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidence.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidenceValidatorTest.*;
import static org.assertj.core.api.Assertions.*;

class OrderEvidenceDigestsTest {
    // Independently calculated with Python hashlib + big-endian struct.pack, not production helpers.
    @Test void fixedUtf8AndLengthPrefixedVectors() {
        var e=initial();var c=change(e,null,31,5000,7);
        assertThat(CODEC.fingerprint(e.start())).isEqualTo("8a317145ae8a52ecdffc174928e88c33cc45a302966b979a843ef526bf7a838c");
        assertThat(e.digest()).isEqualTo("a99462a13d97ce8af81e0558d36f3df88764f3748cabae0fb67f50b490bbb367");
        assertThat(c.digest()).isEqualTo("8c5fe120d1e1f6746a02119a3fdcaa3ab0608597127763f81f26b6746772b73a");
        assertThat(new OrderContentSnapshotCodec().fingerprint(e.start())).isEqualTo(CODEC.fingerprint(e.start()));
    }
    @Test void digestBindsGenerationRevisionPredecessorAndDomain() {
        var e=initial();var c=change(e,null,31,5000,7);
        assertThat(OrderEvidenceDigests.epoch(new Epoch(1,OP,1,10,20,6000,0,e.origin(),e.start(),null,null,ZERO))).isNotEqualTo(e.digest());
        assertThat(OrderEvidenceDigests.epoch(new Epoch(1,EPOCH,1,11,20,6000,0,e.origin(),e.start(),null,null,ZERO))).isNotEqualTo(e.digest());
        assertThat(OrderEvidenceDigests.change(new Change(1,31,1,c.operationId(),EPOCH,null,1,6000,c.beforeFingerprint(),c.after(),c.previousDigest(),ZERO))).isNotEqualTo(c.digest());
        assertThat(OrderEvidenceDigests.change(new Change(1,31,1,c.operationId(),EPOCH,null,0,6000,c.beforeFingerprint(),c.after(),ZERO,ZERO))).isNotEqualTo(c.digest());
        assertThat(e.digest()).isNotEqualTo(c.digest());
        byte[] bytes=CODEC.encode(e.start());byte[] changed=bytes.clone();changed[0]^=1;
        assertThat(OrderEvidenceDigests.sha256(changed)).isNotEqualTo(OrderEvidenceDigests.sha256(bytes));
    }
}
