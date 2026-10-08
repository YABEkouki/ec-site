package com.example.ecsite.entity;

/** Only these fixed summaries may be persisted; never accept exception text. */
public enum PaymentDiscrepancyAuditRunErrorCode {
    ALL_ITEMS_FAILED("すべての監査対象で処理に失敗しました。"),
    CANDIDATE_FETCH_FAILED("監査対象の取得に失敗しました。"),
    EXECUTION_ABORTED("監査実行が途中で終了しました。"),
    ITEM_FAILURE("一部の監査対象で処理に失敗しました。");

    private final String summary;

    PaymentDiscrepancyAuditRunErrorCode(String summary) {
        this.summary = summary;
    }

    public String summary() {
        return summary;
    }
}
