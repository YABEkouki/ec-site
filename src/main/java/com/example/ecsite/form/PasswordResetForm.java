package com.example.ecsite.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PasswordResetForm {

    @NotBlank(message = "新しいパスワードを入力してください。")
    @Size(
            min = 8,
            max = 72,
            message = "新しいパスワードは8文字以上72文字以下で入力してください。")
    private String newPassword;

    @NotBlank(message = "確認用パスワードを入力してください。")
    private String confirmPassword;

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
