package com.example.ecsite.repository.projection;

public interface AdminAssigneeActionRequiredCountProjection {

    Long getAdminAccountId();

    String getUsername();

    boolean getEnabled();

    long getOrderCount();

    long getThreeDaysOrMoreCount();

    long getSevenDaysOrMoreCount();
}
