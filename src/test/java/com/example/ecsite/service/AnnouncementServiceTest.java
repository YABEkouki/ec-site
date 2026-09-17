package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.entity.Announcement;
import com.example.ecsite.entity.AnnouncementImportance;
import com.example.ecsite.entity.AnnouncementType;
import com.example.ecsite.exception.AnnouncementNotFoundException;
import com.example.ecsite.form.AdminAnnouncementForm;
import com.example.ecsite.repository.AnnouncementRepository;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock
    private AnnouncementRepository repository;

    private AnnouncementService service;

    @BeforeEach
    void setUp() {
        service = new AnnouncementService(repository);
    }

    @Test
    void createSavesAnnouncementAndTrimsText() {
        AdminAnnouncementForm form = createForm();

        form.setTitle("  テストタイトル  ");
        form.setContent("  テスト本文  ");

        when(repository.save(any(Announcement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(form);

        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);

        verify(repository).save(captor.capture());

        Announcement saved = captor.getValue();

        assertEquals(AnnouncementType.GENERAL, saved.getType());
        assertEquals(
                AnnouncementImportance.NORMAL,
                saved.getImportance());
        assertEquals("テストタイトル", saved.getTitle());
        assertEquals("テスト本文", saved.getContent());
        assertFalse(saved.isPublished());
    }

    @Test
    void createRejectsPublishedWithoutPublishedAt() {
        AdminAnnouncementForm form = createForm();

        form.setPublished(true);
        form.setPublishedAt(null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(form));

        assertEquals(
                "公開する場合は公開日時を入力してください",
                exception.getMessage());
    }

    @Test
    void updateChangesExistingAnnouncement() {
        Announcement announcement = createAnnouncement();

        when(repository.findById(10L))
                .thenReturn(Optional.of(announcement));

        when(repository.save(announcement))
                .thenReturn(announcement);

        AdminAnnouncementForm form = createForm();

        LocalDateTime publishedAt = LocalDateTime.of(2026, 9, 20, 10, 0);

        form.setType(AnnouncementType.MAINTENANCE);
        form.setImportance(AnnouncementImportance.URGENT);
        form.setTitle("  メンテナンス  ");
        form.setContent("  メンテナンスを実施します。  ");
        form.setPublished(true);
        form.setPublishedAt(publishedAt);

        Announcement updated = service.update(10L, form);

        assertEquals(
                AnnouncementType.MAINTENANCE,
                updated.getType());
        assertEquals(
                AnnouncementImportance.URGENT,
                updated.getImportance());
        assertEquals("メンテナンス", updated.getTitle());
        assertEquals(
                "メンテナンスを実施します。",
                updated.getContent());
        assertTrue(updated.isPublished());
        assertEquals(publishedAt, updated.getPublishedAt());

        verify(repository).save(announcement);
    }

    @Test
    void updateAllowsUnpublishedWithPublishedAt() {
        Announcement announcement = createAnnouncement();

        when(repository.findById(10L))
                .thenReturn(Optional.of(announcement));

        when(repository.save(announcement))
                .thenReturn(announcement);

        AdminAnnouncementForm form = createForm();

        LocalDateTime publishedAt = LocalDateTime.of(2026, 9, 10, 10, 0);

        form.setPublished(false);
        form.setPublishedAt(publishedAt);

        Announcement updated = service.update(10L, form);

        assertFalse(updated.isPublished());
        assertEquals(publishedAt, updated.getPublishedAt());
    }

    @Test
    void updateRejectsPublishedWithoutPublishedAt() {
        AdminAnnouncementForm form = createForm();

        form.setPublished(true);
        form.setPublishedAt(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.update(10L, form));
    }

    @Test
    void createEditFormCopiesAnnouncementValues() {
        Announcement announcement = createAnnouncement();

        LocalDateTime publishedAt = LocalDateTime.of(2026, 9, 15, 12, 30);

        announcement.setType(AnnouncementType.PRODUCT);
        announcement.setImportance(
                AnnouncementImportance.IMPORTANT);
        announcement.setTitle("商品のお知らせ");
        announcement.setContent("新商品を追加しました。");
        announcement.setPublished(true);
        announcement.setPublishedAt(publishedAt);

        when(repository.findById(10L))
                .thenReturn(Optional.of(announcement));

        AdminAnnouncementForm form = service.createEditForm(10L);

        assertEquals(AnnouncementType.PRODUCT, form.getType());
        assertEquals(
                AnnouncementImportance.IMPORTANT,
                form.getImportance());
        assertEquals("商品のお知らせ", form.getTitle());
        assertEquals(
                "新商品を追加しました。",
                form.getContent());
        assertTrue(form.isPublished());
        assertEquals(publishedAt, form.getPublishedAt());
    }

    @Test
    void findByIdThrowsWhenAnnouncementDoesNotExist() {
        when(repository.findById(999L))
                .thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.findById(999L));

        assertEquals(
                "お知らせが見つかりません: 999",
                exception.getMessage());
    }

    @Test
    void findPublishedReturnsPublishedAnnouncementPage() {

        Announcement announcement = createAnnouncement();
        Page<Announcement> page = new PageImpl<>(List.of(announcement));

        when(repository.findPublishedAnnouncements(
                any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(page);

        Page<Announcement> result = service.findPublished(2, 10);

        assertSame(page, result);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(repository).findPublishedAnnouncements(
                any(LocalDateTime.class),
                pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();

        assertEquals(2, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
    }

    @Test
    void findLatestPublishedReturnsRequestedNumberOfAnnouncements() {

        Announcement first = createAnnouncement();
        Announcement second = createAnnouncement();

        Page<Announcement> page = new PageImpl<>(List.of(first, second));

        when(repository.findPublishedAnnouncements(
                any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(page);

        List<Announcement> result = service.findLatestPublished(5);

        assertEquals(2, result.size());
        assertSame(first, result.get(0));
        assertSame(second, result.get(1));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(repository).findPublishedAnnouncements(
                any(LocalDateTime.class),
                pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();

        assertEquals(0, pageable.getPageNumber());
        assertEquals(5, pageable.getPageSize());
    }

    @Test
    void findPublishedByIdThrowsWhenAnnouncementIsNotPublished() {

        when(repository.findPublishedById(
                eq(999L),
                any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        AnnouncementNotFoundException exception = assertThrows(
                AnnouncementNotFoundException.class,
                () -> service.findPublishedById(999L));

        assertEquals(
                "お知らせが見つかりません: 999",
                exception.getMessage());
    }

    private AdminAnnouncementForm createForm() {
        AdminAnnouncementForm form = new AdminAnnouncementForm();

        form.setType(AnnouncementType.GENERAL);
        form.setImportance(AnnouncementImportance.NORMAL);
        form.setTitle("テストタイトル");
        form.setContent("テスト本文");
        form.setPublished(false);

        return form;
    }

    private Announcement createAnnouncement() {
        Announcement announcement = new Announcement();

        announcement.setType(AnnouncementType.GENERAL);
        announcement.setImportance(
                AnnouncementImportance.NORMAL);
        announcement.setTitle("変更前タイトル");
        announcement.setContent("変更前本文");
        announcement.setPublished(false);

        return announcement;
    }

}
