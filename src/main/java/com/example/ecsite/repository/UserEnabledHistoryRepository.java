package com.example.ecsite.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.UserEnabledHistory;

public interface UserEnabledHistoryRepository
        extends JpaRepository<UserEnabledHistory, Long> {

    @Query("""
            SELECT h
            FROM UserEnabledHistory h
            JOIN FETCH h.user
            WHERE (:userId IS NULL OR h.user.id = :userId)
              AND h.user.username LIKE CONCAT('%', COALESCE(:username, ''), '%')
              AND (:toEnabled IS NULL OR h.toEnabled = :toEnabled)
              AND h.changedByUsername LIKE CONCAT('%', COALESCE(:changedByUsername, ''), '%')
              AND h.changedAt >= :from
              AND h.changedAt < :toExclusive
            ORDER BY h.changedAt DESC, h.id DESC
            """)
    Page<UserEnabledHistory> search(
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("toEnabled") Boolean toEnabled,
            @Param("changedByUsername") String changedByUsername,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);

}
