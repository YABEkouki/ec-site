package com.example.ecsite.repository.projection;

public interface ActionRequiredAgingSummaryProjection {

    long getThreeDaysOrMoreCount();

    long getSevenDaysOrMoreCount();
}
