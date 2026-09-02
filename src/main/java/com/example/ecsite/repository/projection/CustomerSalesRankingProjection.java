package com.example.ecsite.repository.projection;

public interface CustomerSalesRankingProjection {

    Long getUserId();

    String getUsername();

    Long getOrderCount();

    Long getQuantity();

    Long getSalesAmount();
}