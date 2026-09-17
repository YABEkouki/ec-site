package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE)
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

        Page<Announcement> result =
                repository.findAllByOrderByCreatedAtDesc(
                        PageRequest.of(0, 20));

        assertEquals(2, result.getTotalElements());
        assertEquals(
                second.getId(),
                result.getContent().get(0).getId());
        assertEquals(
                first.getId(),
                result.getContent().get(1).getId());
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
