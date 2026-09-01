package com.example.ecsite.repository.projection;

import java.time.LocalDate;

public interface DailySalesProjection {

        LocalDate getDate();

        long getOrderCount();

        long getSalesOrderCount();

        long getSalesAmount();
}