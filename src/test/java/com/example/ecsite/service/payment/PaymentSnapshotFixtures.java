package com.example.ecsite.service.payment;
import com.example.ecsite.entity.*;
final class PaymentSnapshotFixtures {
    static PaymentOperationSnapshot of(Long order, Long payment, Long transaction, PaymentTransactionType type,
            String key, int amount, int revision, String provider, PaymentTransactionInitiatorType actor, Long actorId,
            String username, String note) {
        return new PaymentOperationSnapshot(order,payment,transaction,type,key,amount,revision,provider,
                PaymentProvider.PAYJP,PaymentTransactionStatus.PENDING,actor,actorId,username,note);
    }
    static PaymentOperationSnapshot authorization(Long order, Long payment) {
        return of(order,payment,100L,PaymentTransactionType.AUTHORIZE,"auth-key",3300,0,"pf_test_123",null,null,null,null);
    }
}
