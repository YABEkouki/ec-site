package com.example.ecsite.service.warehouse;

import com.example.ecsite.entity.Order;

/**
 * Feature132-A: the system identity of a shipment recorded by the warehouse shipment import
 * (account id NULL, username {@link Order#WAREHOUSE_SHIPMENT_IMPORTER}, actor/initiator type SYSTEM; V89).
 * There is no public constructor: only the warehouse package (the import worker) can obtain an instance,
 * and holding one is the permission to ship an exported order without a logged-in administrator.
 */
public final class WarehouseShipmentActor {
    static final WarehouseShipmentActor INSTANCE = new WarehouseShipmentActor();

    private WarehouseShipmentActor() {
    }

    public String username() {
        return Order.WAREHOUSE_SHIPMENT_IMPORTER;
    }
}
