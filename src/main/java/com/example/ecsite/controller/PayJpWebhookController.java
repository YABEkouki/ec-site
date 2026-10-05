package com.example.ecsite.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecsite.payment.payjp.PayJpWebhookEvent;
import com.example.ecsite.payment.payjp.PayJpWebhookTokenVerifier;
import com.example.ecsite.service.payment.PayJpWebhookSyncService;

@RestController
@RequestMapping("/webhooks/payjp")
public class PayJpWebhookController {

    private static final String WEBHOOK_TOKEN_HEADER = "X-Payjp-Webhook-Token";

    private final PayJpWebhookTokenVerifier tokenVerifier;
    private final PayJpWebhookSyncService syncService;

    public PayJpWebhookController(
            PayJpWebhookTokenVerifier tokenVerifier,
            PayJpWebhookSyncService syncService) {

        this.tokenVerifier = tokenVerifier;
        this.syncService = syncService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(name = WEBHOOK_TOKEN_HEADER, required = false) String webhookToken,
            @RequestBody PayJpWebhookEvent event) {

        if (!tokenVerifier.isValid(webhookToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (event == null
                || event.type() == null
                || event.data() == null
                || event.data().id() == null
                || event.data().id().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        syncService.synchronize(
                event.type(),
                event.data().id());

        return ResponseEntity.ok().build();
    }
}
