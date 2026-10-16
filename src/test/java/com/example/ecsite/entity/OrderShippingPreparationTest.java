package com.example.ecsite.entity;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;

class OrderShippingPreparationTest {
    private final Order order = new Order();
    private final LocalDateTime now = LocalDateTime.of(2026,10,16,14,0);
    private void empty() {
        assertThat(order.getShippingPreparationStartedAt()).isNull();
        assertThat(order.getShippingPreparationStartedByAccountId()).isNull();
        assertThat(order.getShippingPreparationStartedByUsername()).isNull();
    }
    @Test void recordsThreeFieldsAtomicallyAndAcceptsMaximumUsername() {
        String username = "x".repeat(100);
        order.beginShippingPreparation(now,1L,username);
        assertThat(order.getShippingPreparationStartedAt()).isEqualTo(now);
        assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(1L);
        assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo(username);
        assertThat(order.getShipmentCapturePaymentId()).isNull();
    }
    @Test void nullTime() {
        assertThatIllegalArgumentException().isThrownBy(() -> order.beginShippingPreparation(null,1L,"admin")); empty();
    }
    @ParameterizedTest @NullSource @ValueSource(longs={0,-1})
    void invalidActorId(Long id) {
        assertThatIllegalArgumentException().isThrownBy(() -> order.beginShippingPreparation(now,id,"admin")); empty();
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"", " ", "\t\n", "　"})
    void invalidUsername(String username) {
        assertThatIllegalArgumentException().isThrownBy(() -> order.beginShippingPreparation(now,1L,username)); empty();
    }
    @Test void overlongUsername() {
        assertThatIllegalArgumentException().isThrownBy(() -> order.beginShippingPreparation(now,1L,"x".repeat(101))); empty();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7})
    void refusesAnyPreviouslySetFieldWithoutOverwriting(int mask) {
        if ((mask & 1)!=0) ReflectionTestUtils.setField(order,"shippingPreparationStartedAt",now);
        if ((mask & 2)!=0) ReflectionTestUtils.setField(order,"shippingPreparationStartedByAccountId",9L);
        if ((mask & 4)!=0) ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername","original");
        var beforeAt=order.getShippingPreparationStartedAt();
        var beforeId=order.getShippingPreparationStartedByAccountId();
        var beforeName=order.getShippingPreparationStartedByUsername();
        assertThatIllegalStateException().isThrownBy(() -> order.beginShippingPreparation(now.plusDays(1),1L,"new"));
        assertThat(order.getShippingPreparationStartedAt()).isEqualTo(beforeAt);
        assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(beforeId);
        assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo(beforeName);
    }
}
