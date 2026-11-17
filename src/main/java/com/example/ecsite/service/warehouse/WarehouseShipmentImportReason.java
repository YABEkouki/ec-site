package com.example.ecsite.service.warehouse;

/** Feature132-B: why a row of a shipped CSV was not used to ship its order (V90 reason). */
public enum WarehouseShipmentImportReason {
    INVALID_FORMAT("列数・引用符またはバッチID・注文番号の形式が正しくありません。"),
    INVALID_DATE("出荷日が正しくありません（yyyy-MM-dd、未来日は不可）。"),
    INVALID_CARRIER("配送業者が空か、50 文字を超えています。"),
    INVALID_TRACKING("追跡番号が空か、50 文字を超えているか、英数字とハイフン以外を含んでいます。"),
    DUPLICATE_IN_FILE("同じファイルの前の行に同じ注文があります。"),
    NOT_EXPORTED("このバッチで倉庫へ出力した注文ではありません。"),
    BATCH_NOT_SEALED("倉庫出力バッチの出力処理が完了していません。"),
    ALREADY_IMPORTED("別の取込みで処理中、または処理済みの注文です。"),
    ORDER_NOT_SHIPPABLE("取消済みなど、発送できない状態の注文です。"),
    SHIPMENT_REFUSED("発送処理が安全確認で止まりました。注文と決済の状態を確認してください。"),
    RETRY_LIMIT("発送処理を規定回数試みましたが完了しませんでした。注文と決済の状態を確認してください。");

    private final String label;

    WarehouseShipmentImportReason(String label) {
        this.label = label;
    }

    public String label() { return label; }
}
