package com.example.ecsite.service.order.evidence;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class OrderContentSnapshotFactoryTest {
    @Test void missingPersistedFactsAreNotReconstructed() {
        var mapper=JsonMapper.builder().build();
        assertThatThrownBy(()->new OrderContentSnapshotFactory().fromRows(mapper.readTree("{\"id\":1}"),List.of(),List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void persistedSnapshotsAndIntegerArithmeticArePreserved() {
        var m=JsonMapper.builder().build();
        var order=m.readTree("{\"id\":1,\"user_id\":2,\"content_revision\":0,\"item_subtotal\":6000,\"charge_total\":0,\"tax_amount\":545,\"total_amount\":6000}");
        var item=m.readTree("{\"id\":3,\"product_id\":7,\"product_name\":\" 保存名　\",\"category_id\":4,\"category_name\":\"保存分類\",\"price\":6000,\"quantity\":1,\"subtotal\":6000,\"tax_category_id\":5,\"tax_category_code\":\"STANDARD\",\"tax_category_name\":\"標準\",\"tax_rate\":10.00}");
        var charge=m.readTree("{\"id\":6,\"charge_type\":\"SHIPPING\",\"name\":\"保存送料\",\"amount\":0,\"tax_category_id\":5,\"tax_category_code\":\"STANDARD\",\"tax_category_name\":\"標準\",\"tax_rate\":10.00,\"display_order\":10}");
        var f=new OrderContentSnapshotFactory();var snapshot=f.fromRows(order,List.of(item),List.of(charge));
        assertThat(snapshot.items().getFirst().productName()).isEqualTo(" 保存名　");
        var mutable=(tools.jackson.databind.node.ObjectNode)item;mutable.put("quantity",2147483648L);
        assertThatThrownBy(()->f.fromRows(order,List.of(item),List.of(charge))).isInstanceOf(IllegalArgumentException.class);
        mutable.putNull("quantity");assertThatThrownBy(()->f.fromRows(order,List.of(item),List.of(charge))).isInstanceOf(IllegalArgumentException.class);
    }
}
