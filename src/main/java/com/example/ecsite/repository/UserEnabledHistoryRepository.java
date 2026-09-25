package com.example.ecsite.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.UserEnabledHistory;

public interface UserEnabledHistoryRepository
        extends JpaRepository<UserEnabledHistory, Long> {
}
