package com.example.ecsite.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CategoryForm {

    @NotBlank(message = "カテゴリ名を入力してください。")
    @Size(
            max = 100,
            message = "カテゴリ名は100文字以内で入力してください。")
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}