package com.example.ecsite.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

public class ProductSearchForm {

    private String keyword;

    private Long categoryId;

    @Min(value = 0, message = "最低価格は0円以上で入力してください。")
    private Integer minPrice;

    @Min(value = 0, message = "最高価格は0円以上で入力してください。")
    private Integer maxPrice;

    private boolean inStockOnly;

    private String sort = "newest";

    @AssertTrue(message = "最低価格は最高価格以下で入力してください。")
    public boolean isPriceRangeValid() {

        if (minPrice == null || maxPrice == null) {
            return true;
        }

        return minPrice <= maxPrice;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(Integer minPrice) {
        this.minPrice = minPrice;
    }

    public Integer getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(Integer maxPrice) {
        this.maxPrice = maxPrice;
    }

    public boolean isInStockOnly() {
        return inStockOnly;
    }

    public void setInStockOnly(boolean inStockOnly) {
        this.inStockOnly = inStockOnly;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }
}
