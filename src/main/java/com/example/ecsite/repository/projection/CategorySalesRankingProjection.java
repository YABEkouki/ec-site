package com.example.ecsite.repository.projection;

public interface CategorySalesRankingProjection {

    Long getCategoryId();

    String getCategoryName();

    Long getQuantity();

    Long getOrderCount();

    Long getSalesAmount();
}
