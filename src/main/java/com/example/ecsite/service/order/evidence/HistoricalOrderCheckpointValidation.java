package com.example.ecsite.service.order.evidence;

import tools.jackson.databind.JsonNode;

/** Pure adapter for the existing persisted checkpoint contract. Never creates a live receipt. */
public final class HistoricalOrderCheckpointValidation {
    private HistoricalOrderCheckpointValidation() {}
    public static OrderPaymentEvidence.Checkpoint validate(byte[] raw,JsonNode epoch,OrderContentSnapshot start){
        var codec=new OrderCheckpointReceiptCodec();var receipt=codec.decode(raw);
        codec.validateStored(receipt,epoch,start);
        return codec.checkpoint(receipt);
    }
}
