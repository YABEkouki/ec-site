package com.example.ecsite.form;

import jakarta.validation.constraints.Size;

public class AdminAccountEditForm {

    @jakarta.validation.constraints.NotBlank
    @Size(max = 100)
    private String username;

    @Size(max = 100)
    private String password;

    @Size(max = 100)
    private String confirmPassword;

    private boolean enabled;

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
