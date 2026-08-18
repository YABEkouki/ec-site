package com.example.ecsite.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ProductForm {

    @NotBlank(message = "商品名を入力してください。")
    @Size(max = 100, message = "商品名は100文字以内で入力してください。")
    private String name;

    @NotNull(message = "価格を入力してください。")
    @Min(value = 0, message = "価格は0円以上で入力してください。")
    private Integer price;

    @NotNull(message = "在庫数を入力してください。")
    @Min(value = 0, message = "在庫数は0以上で入力してください。")
    private Integer stock;

    private String description;

    @NotNull(message = "カテゴリを選択してください。")
    private Long categoryId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPrice() {
        return price;
    }

    public void setPrice(Integer price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }
}