package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.entity.Announcement;
import com.example.ecsite.entity.AnnouncementImportance;
import com.example.ecsite.entity.AnnouncementType;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnnouncementRepositoryTest {

    @Autowired
    private AnnouncementRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndFindAnnouncement() {
        Announcement announcement = new Announcement();

        announcement.setType(AnnouncementType.SHIPPING);
        announcement.setImportance(
                AnnouncementImportance.IMPORTANT);
        announcement.setTitle("配送について");
        announcement.setContent("配送に関するお知らせです。");
        announcement.setPublished(false);

        Announcement saved = repository.save(announcement);

        entityManager.flush();
        entityManager.clear();

        Announcement reloaded = repository.findById(saved.getId())
                .orElseThrow();

        assertEquals(
                AnnouncementType.SHIPPING,
                reloaded.getType());

        assertEquals(
                AnnouncementImportance.IMPORTANT,
                reloaded.getImportance());

        assertEquals(
                "配送について",
                reloaded.getTitle());

        assertEquals(
                "配送に関するお知らせです。",
                reloaded.getContent());

        assertFalse(reloaded.isPublished());

        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
    }

    @Test
    void findAllReturnsNewestCreatedFirst() {
        Announcement first = createAnnouncement(
                "最初のお知らせ");

        first = repository.save(first);
        entityManager.flush();

        setCreatedAt(
                first.getId(),
                LocalDateTime.of(2026, 9, 1, 10, 0));

        Announcement second = createAnnouncement(
                "後のお知らせ");

        second = repository.save(second);
        entityManager.flush();

        setCreatedAt(
                second.getId(),
                LocalDateTime.of(2026, 9, 2, 10, 0));

        entityManager.clear();

        Page<Announcement> result = repository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(0, 20));

        assertEquals(2, result.getTotalElements());
        assertEquals(
                second.getId(),
                result.getContent().get(0).getId());
        assertEquals(
                first.getId(),
                result.getContent().get(1).getId());
    }

    @Test
    void findPublishedAnnouncementsReturnsOnlyCurrentlyPublishedInImportanceOrder() {

        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 12, 0);

        Announcement normalOld = createPublishedAnnouncement(
                "通常・古い",
                AnnouncementImportance.NORMAL,
                LocalDateTime.of(2026, 9, 10, 10, 0));

        Announcement normalNew = createPublishedAnnouncement(
                "通常・新しい",
                AnnouncementImportance.NORMAL,
                LocalDateTime.of(2026, 9, 16, 10, 0));

        Announcement important = createPublishedAnnouncement(
                "重要",
                AnnouncementImportance.IMPORTANT,
                LocalDateTime.of(2026, 9, 5, 10, 0));

        Announcement urgent = createPublishedAnnouncement(
                "緊急",
                AnnouncementImportance.URGENT,
                LocalDateTime.of(2026, 9, 1, 10, 0));

        Announcement unpublished = createPublishedAnnouncement(
                "非公開",
                AnnouncementImportance.URGENT,
                LocalDateTime.of(2026, 9, 17, 10, 0));
        unpublished.setPublished(false);

        Announcement scheduled = createPublishedAnnouncement(
                "公開予定",
                AnnouncementImportance.URGENT,
                LocalDateTime.of(2026, 9, 18, 10, 0));

        repository.save(normalOld);
        repository.save(normalNew);
        repository.save(important);
        repository.save(urgent);
        repository.save(unpublished);
        repository.save(scheduled);

        entityManager.flush();
        entityManager.clear();

        Page<Announcement> result = repository.findPublishedAnnouncements(
                now,
                PageRequest.of(0, 10));

        assertEquals(4, result.getTotalElements());

        assertEquals(
                "緊急",
                result.getContent().get(0).getTitle());
        assertEquals(
                "重要",
                result.getContent().get(1).getTitle());
        assertEquals(
                "通常・新しい",
                result.getContent().get(2).getTitle());
        assertEquals(
                "通常・古い",
                result.getContent().get(3).getTitle());
    }

    @Test
    void findPublishedByIdReturnsOnlyCurrentlyPublishedAnnouncement() {

        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 12, 0);

        Announcement published = createPublishedAnnouncement(
                "公開中",
                AnnouncementImportance.NORMAL,
                LocalDateTime.of(2026, 9, 17, 10, 0));

        Announcement unpublished = createPublishedAnnouncement(
                "非公開",
                AnnouncementImportance.NORMAL,
                LocalDateTime.of(2026, 9, 17, 10, 0));
        unpublished.setPublished(false);

        Announcement scheduled = createPublishedAnnouncement(
                "公開予定",
                AnnouncementImportance.NORMAL,
                LocalDateTime.of(2026, 9, 18, 10, 0));

        published = repository.save(published);
        unpublished = repository.save(unpublished);
        scheduled = repository.save(scheduled);

        entityManager.flush();
        entityManager.clear();

        assertTrue(
                repository.findPublishedById(
                        published.getId(),
                        now)
                        .isPresent());

        assertTrue(
                repository.findPublishedById(
                        unpublished.getId(),
                        now)
                        .isEmpty());

        assertTrue(
                repository.findPublishedById(
                        scheduled.getId(),
                        now)
                        .isEmpty());
    }

    private Announcement createAnnouncement(String title) {
        Announcement announcement = new Announcement();

        announcement.setType(AnnouncementType.GENERAL);
        announcement.setImportance(
                AnnouncementImportance.NORMAL);
        announcement.setTitle(title);
        announcement.setContent("テスト本文");
        announcement.setPublished(false);

        return announcement;
    }

    private Announcement createPublishedAnnouncement(
            String title,
            AnnouncementImportance importance,
            LocalDateTime publishedAt) {

        Announcement announcement = new Announcement();

        announcement.setType(AnnouncementType.GENERAL);
        announcement.setImportance(importance);
        announcement.setTitle(title);
        announcement.setContent("テスト本文");
        announcement.setPublished(true);
        announcement.setPublishedAt(publishedAt);

        return announcement;
    }

    private void setCreatedAt(
            Long id,
            LocalDateTime createdAt) {

        entityManager.createNativeQuery("""
                UPDATE announcements
                SET created_at = :createdAt
                WHERE id = :id
                """)
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();
    }
}
