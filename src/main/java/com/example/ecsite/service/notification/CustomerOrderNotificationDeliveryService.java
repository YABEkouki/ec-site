package com.example.ecsite.service.notification;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.config.CustomerOrderNotificationProperties;

/**
 * Feature133-B: one delivery pass. Each notification is claimed, prepared and completed in its own short
 * transaction; SMTP runs between them with no transaction, connection or row lock held.
 */
@Service
@ConditionalOnProperty(prefix = "app.customer-notification", name = "enabled", havingValue = "true")
public class CustomerOrderNotificationDeliveryService {
    private final UUID owner = UUID.randomUUID();
    private final CustomerOrderNotificationStore store;
    private final CustomerOrderNotificationMailClient client;
    private final CustomerOrderNotificationProperties properties;

    public CustomerOrderNotificationDeliveryService(CustomerOrderNotificationStore store,
            CustomerOrderNotificationMailClient client, CustomerOrderNotificationProperties properties) {
        this.store = store; this.client = client; this.properties = properties;
    }

    /** Returns how many notifications this pass closed or attempted. Safe to repeat. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int runPass() {
        store.recoverExpired();
        int handled = 0;
        while (handled < properties.batchSize()) {
            var claim = store.claim(owner);
            if (claim.isEmpty()) break;
            handled++;
            var mail = store.prepare(claim.get());
            if (mail.isEmpty()) continue;
            // If recording the outcome fails, the SENDING lease expires and recovery closes it as uncertain.
            store.complete(claim.get(), client.send(mail.get()));
        }
        return handled;
    }
}
