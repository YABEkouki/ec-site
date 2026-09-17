package com.example.ecsite.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Announcement;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Page<Announcement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            SELECT a
            FROM Announcement a
            WHERE a.published = true
              AND a.publishedAt IS NOT NULL
              AND a.publishedAt <= :now
            ORDER BY
              CASE a.importance
                WHEN com.example.ecsite.entity.AnnouncementImportance.URGENT THEN 1
                WHEN com.example.ecsite.entity.AnnouncementImportance.IMPORTANT THEN 2
                WHEN com.example.ecsite.entity.AnnouncementImportance.NORMAL THEN 3
              END,
              a.publishedAt DESC,
              a.id DESC
            """)
    Page<Announcement> findPublishedAnnouncements(
            @Param("now") LocalDateTime now,
            Pageable pageable);

    @Query("""
            SELECT a
            FROM Announcement a
            WHERE a.id = :id
              AND a.published = true
              AND a.publishedAt IS NOT NULL
              AND a.publishedAt <= :now
            """)
    Optional<Announcement> findPublishedById(
            @Param("id") Long id,
            @Param("now") LocalDateTime now);
}
