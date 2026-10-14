package com.example.ecsite.web;

import org.springframework.stereotype.Component;
import com.example.ecsite.entity.*;
import com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason;

@Component("manualResendDisplay")
public class AdminPaymentAuditNotificationManualResendDisplay {
    public String rejection(Reason r){if(r==null)return "再送できます";return switch(r){
        case NOT_EXHAUSTED->"元通知が再試行上限到達ではありません";case DELIVERY_UNCERTAIN,UNKNOWN_PRESENT->"結果不明または配送結果不確実のため再送できません";
        case HANDLING_RESOLVED->"管理者対応が対応完了です";case NOTIFICATION_DISABLED->"通知機能がOFFです";case LIMIT_REACHED->"最大2回に到達しています";
        case TOO_SOON->"前回要求から30分経過していません";case ACTIVE_REQUEST->"手動再送処理中です";case PREVIOUS_RESULT->"前回要求が送信済み等のため再送できません";
        case RISK_ACKNOWLEDGEMENT_REQUIRED->"重複配信リスクの確認が必要です";case STALE_SNAPSHOT->"状態が更新されています。詳細を再読み込みしてください";case NONCE_CONFLICT->"再送要求の内容が一致しません。詳細を再読み込みしてください";};}
    public String status(PaymentAuditNotificationManualResendStatus s){return switch(s){case PENDING->"送信待ち";case CLAIMED->"配送準備中";case SENDING->"送信処理中";case SENT->"送信済み";case FAILED->"送信失敗";case UNKNOWN->"結果不明";case CANCELLED->"送信取消";};}
    public String attempt(PaymentAuditNotificationAttemptResult r){if(r==null)return "—";return switch(r){case IN_PROGRESS->"処理中";case SUCCESS->"送信成功";case FAILURE->"送信失敗";case UNKNOWN->"結果不明";};}
}
