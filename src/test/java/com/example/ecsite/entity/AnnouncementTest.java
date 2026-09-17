package com.example.ecsite.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class AnnouncementTest {

    @Test
    void unpublishedIsNeitherScheduledNorCurrentlyPublished() {
        Announcement announcement = new Announcement();

        announcement.setPublished(false);
        announcement.setPublishedAt(
                LocalDateTime.now().minusDays(1));

        assertFalse(announcement.isScheduled());
        assertFalse(announcement.isCurrentlyPublished());
    }

    @Test
    void futurePublishedAtIsScheduled() {
        Announcement announcement = new Announcement();

        announcement.setPublished(true);
        announcement.setPublishedAt(
                LocalDateTime.now().plusDays(1));

        assertTrue(announcement.isScheduled());
        assertFalse(announcement.isCurrentlyPublished());
    }

    @Test
    void pastPublishedAtIsCurrentlyPublished() {
        Announcement announcement = new Announcement();

        announcement.setPublished(true);
        announcement.setPublishedAt(
                LocalDateTime.now().minusDays(1));

        assertFalse(announcement.isScheduled());
        assertTrue(announcement.isCurrentlyPublished());
    }

    @Test
    void publishedWithoutPublishedAtIsNeitherScheduledNorCurrentlyPublished() {
        Announcement announcement = new Announcement();

        announcement.setPublished(true);
        announcement.setPublishedAt(null);

        assertFalse(announcement.isScheduled());
        assertFalse(announcement.isCurrentlyPublished());
    }
}
