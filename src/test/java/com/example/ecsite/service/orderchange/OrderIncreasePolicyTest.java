package com.example.ecsite.service.orderchange;

import com.example.ecsite.entity.Order;
import com.example.ecsite.service.OrderModificationPolicy;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
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
    @Test void requestIsImmutableAndDigestIgnoresMapIterationOrder(){
        var key=UUID.randomUUID();var source=new LinkedHashMap<Long,Integer>();source.put(2L,4);source.put(1L,3);
        var first=new OrderIncreaseService.Request(10,20,key,0,source);source.put(1L,99);
        var second=new OrderIncreaseService.Request(10,20,key,0,Map.of(1L,3,2L,4));
        assertThat(first).isEqualTo(second);assertThat(first.digest()).isEqualTo(second.digest());
        assertThat(new OrderIncreaseService.Request(10,20,key,1,second.quantities()).digest()).isNotEqualTo(first.digest());
        assertThatThrownBy(()->new OrderIncreaseService.Request(10,20,key,0,Map.of(1L,0))).isInstanceOf(IllegalArgumentException.class);
    }
}
