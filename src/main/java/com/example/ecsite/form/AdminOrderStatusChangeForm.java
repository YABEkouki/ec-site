package com.example.ecsite.form;

import jakarta.validation.constraints.Size;

public class AdminOrderStatusChangeForm {

    @Size(max = 500)
    private String internalNote;

    public String getInternalNote() {
        return internalNote;
    }

    public void setInternalNote(String internalNote) {
        this.internalNote = internalNote;
    }
}
