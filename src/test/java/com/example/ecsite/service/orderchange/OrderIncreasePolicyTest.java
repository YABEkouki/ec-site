package com.example.ecsite.service.orderchange;

import com.example.ecsite.entity.Order;
import com.example.ecsite.service.OrderModificationPolicy;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class OrderIncreasePolicyTest {
    final OrderModificationPolicy policy=new OrderModificationPolicy();
    final LocalDateTime deadline=LocalDateTime.of(2026,10,21,14,0);
    @Test void admissionAndGraceUseDistinctStrictBoundaries(){
        var order=new Order(1L,100,deadline.minusHours(1),deadline);
        assertThat(policy.allowsModification(order,deadline.minusNanos(1))).isTrue();
        assertThat(policy.allowsModification(order,deadline)).isFalse();
        var grace=policy.changeGraceDeadline(order);assertThat(grace).isEqualTo(deadline.plusMinutes(10));
        assertThat(policy.canExpireChange(grace,null,grace.minusNanos(1))).isFalse();
        assertThat(policy.canExpireChange(grace,null,grace)).isTrue();
        assertThat(policy.canExpireChange(grace,deadline,grace.plusDays(1))).isFalse();
    }
}
