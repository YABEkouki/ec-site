package com.example.ecsite.service.warehouse;

/** Feature132-B: outcome of one row of a shipped CSV (V90 warehouse_shipment_import_lines.state). */
public enum WarehouseShipmentImportLineState {
    PENDING("処理待ち", false),
    SHIPPED("発送完了", false),
    CAPTURE_UNCONFIRMED("売上確定を確認中", false),
    CAPTURE_FAILED("発送済み・売上確定に失敗（要対応）", true),
    ALREADY_SHIPPED("既に発送済み", false),
    REJECTED("取込み不可", true);

    private final String label;
    private final boolean actionRequired;

    WarehouseShipmentImportLineState(String label, boolean actionRequired) {
        this.label = label;
        this.actionRequired = actionRequired;
    }

    public String label() { return label; }
    public boolean actionRequired() { return actionRequired; }
}
