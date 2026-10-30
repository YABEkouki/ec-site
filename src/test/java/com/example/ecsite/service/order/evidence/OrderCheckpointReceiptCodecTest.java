package com.example.ecsite.service.order.evidence;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class OrderCheckpointReceiptCodecTest {
    final OrderCheckpointReceiptCodec codec=new OrderCheckpointReceiptCodec();
    OrderCheckpointReceiptCodec.Receipt valid() {return new OrderCheckpointReceiptCodec.Receipt(1,1,2,3,0,"ab".repeat(32),"{}",OrderCheckpointReceiptCodec.hash("{}"),"{}",OrderCheckpointReceiptCodec.hash("{}"));}
    @Test void canonicalRoundTripAndTamperRejection() {
        byte[] bytes=codec.encode(valid());assertThat(codec.encode(codec.decode(bytes))).isEqualTo(bytes);
        assertThatThrownBy(()->codec.decode((" "+new String(bytes,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.decode(new String(bytes,StandardCharsets.UTF_8).replace("\"version\":1","\"version\":2").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.decode(new String(bytes,StandardCharsets.UTF_8).replace("\"version\":1","\"version\":1,\"version\":1").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void digestsAreRecomputedAndNullNeverDefaults() {
        var r=valid();assertThatThrownBy(()->codec.encode(new OrderCheckpointReceiptCodec.Receipt(1,1,2,3,0,r.contentFingerprint(),"{\"changed\":true}",r.localAssessmentDigest(),r.externalObservation(),r.externalObservationDigest()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.decode("{}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
