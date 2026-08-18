package com.example.ecsite.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserForm {

    @NotBlank(message = "ユーザ名を入力してください。")
    @Size(max = 100, message = "ユーザ名は100文字以内で入力してください。")
    private String username;

    @NotBlank(message = "パスワードを入力してください。")
    @Size(min = 8, max = 100, message = "パスワードは8文字以上100文字以内で入力してください。")
    private String password;

    @NotBlank(message = "確認用パスワードを入力してください。")
    @Size(min = 8, max = 100, message = "確認用パスワードは8文字以上100文字以内で入力してください。")
    private String confirmPassword;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}