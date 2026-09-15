package com.example.ecsite.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserAccountEditForm {

    @NotBlank(message = "ユーザー名を入力してください。")
    @Size(max = 100, message = "ユーザー名は100文字以内で入力してください。")
    private String username;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
