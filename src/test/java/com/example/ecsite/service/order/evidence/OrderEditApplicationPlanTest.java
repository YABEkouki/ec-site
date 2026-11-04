package com.example.ecsite.service.order.evidence;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.*;

class OrderEditApplicationPlanTest {
    OrderContentSnapshot.Item item(){return new OrderContentSnapshot.Item(9,1,"saved",1,"category",1000,1,1000,1,"STANDARD","standard",1000);}
    @Test void newLineDoesNotHaveAPersistedIdEvenWhenItsPreviewKeyLooksLikeOne(){
        var key=new LineKey(KeyKind.NEW,9);var line=new Line(key,null,null,item());assertThat(line.persistedItemId()).isNull();
        assertThatThrownBy(()->new Line(key,9L,null,item())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Line(key,null,item(),item())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void removedExistingLinePreservesItsHistoricalSnapshot(){
        var line=new Line(new LineKey(KeyKind.EXISTING,9),9L,item(),null);
        assertThat(line.before().productName()).isEqualTo("saved");assertThat(line.after()).isNull();
        assertThatThrownBy(()->new Line(new LineKey(KeyKind.EXISTING,9),10L,item(),null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void keyKindAndPositiveValueAreMandatory(){
        assertThatThrownBy(()->new LineKey(KeyKind.NEW,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new LineKey(null,9)).isInstanceOf(NullPointerException.class);
    }
    @Test void ownershipAndCompletionFormatDoNotAcceptMissingIdentityOrInvalidRevision(){
        var own=new Ownership(UUID.randomUUID(),UUID.randomUUID(),1,1);
        var context=new Context(1,own,1,0,1,6000,"a".repeat(64));
        assertThat(new Completion(context,UUID.randomUUID(),1,"b".repeat(64)).context()).isEqualTo(context);
        assertThatThrownBy(()->new Ownership(own.admissionId(),own.attemptId(),0,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Context(2,own,1,0,1,6000,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Context(1,own,1,0,2,6000,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Completion(context,UUID.randomUUID(),0,"b".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Completion(context,UUID.randomUUID(),1,"invalid")).isInstanceOf(IllegalArgumentException.class);
    }
}
