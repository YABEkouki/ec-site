package com.example.ecsite.exception;

public class ReviewAccessDeniedException extends RuntimeException {

    public ReviewAccessDeniedException() {
        super("このレビューを編集または削除する権限がありません");
    }
}