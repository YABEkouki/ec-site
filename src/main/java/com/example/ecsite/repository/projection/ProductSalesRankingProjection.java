package com.example.ecsite.repository.projection;

public interface ProductSalesRankingProjection {

    Long getProductId();

    String getProductName();

    Long getQuantity();

    Long getOrderCount();

    Long getSalesAmount();
}
