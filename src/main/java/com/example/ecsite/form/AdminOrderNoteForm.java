package com.example.ecsite.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminOrderNoteForm {

    @NotBlank(message = "メモを入力してください。")
    @Size(max = 1000, message = "メモは1000文字以内で入力してください。")
    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
