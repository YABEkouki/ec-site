package com.example.ecsite.service;

/** Exact V51 whitespace set; length is measured before any normalization. */
public final class PaymentAuditNotificationHandlingComments {
    private PaymentAuditNotificationHandlingComments() {}
    public static void validate(String comment,boolean required) {
        if(comment!=null && comment.codePointCount(0,comment.length())>1000)
            throw new IllegalArgumentException("コメントは1,000文字以内で入力してください。");
        if(required && (comment==null || comment.codePoints().allMatch(PaymentAuditNotificationHandlingComments::isSpace)))
            throw new IllegalArgumentException("完了・再オープンにはコメントが必要です。");
    }
    private static boolean isSpace(int cp) {
        return cp>=9 && cp<=13 || cp==0x20 || cp==0x85 || cp==0xa0 || cp==0x1680
            || cp>=0x2000 && cp<=0x200b || cp==0x2028 || cp==0x2029 || cp==0x202f || cp==0x205f || cp==0x3000;
    }
}
