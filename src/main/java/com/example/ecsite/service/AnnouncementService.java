package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Announcement;
import com.example.ecsite.exception.AnnouncementNotFoundException;
import com.example.ecsite.form.AdminAnnouncementForm;
import com.example.ecsite.repository.AnnouncementRepository;

@Service
@Transactional
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;

    public AnnouncementService(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    @Transactional(readOnly = true)
    public Page<Announcement> findAll(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return announcementRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Announcement findById(Long id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("お知らせが見つかりません: " + id));
    }

    public Announcement create(AdminAnnouncementForm form) {
        validatePublication(form);

        Announcement announcement = new Announcement();
        applyForm(announcement, form);

        return announcementRepository.save(announcement);
    }

    public Announcement update(Long id, AdminAnnouncementForm form) {
        validatePublication(form);

        Announcement announcement = findById(id);
        applyForm(announcement, form);

        return announcementRepository.save(announcement);
    }

    @Transactional(readOnly = true)
    public AdminAnnouncementForm createEditForm(Long id) {
        Announcement announcement = findById(id);

        AdminAnnouncementForm form = new AdminAnnouncementForm();
        form.setType(announcement.getType());
        form.setImportance(announcement.getImportance());
        form.setTitle(announcement.getTitle());
        form.setContent(announcement.getContent());
        form.setPublished(announcement.isPublished());
        form.setPublishedAt(announcement.getPublishedAt());

        return form;
    }

    @Transactional(readOnly = true)
    public Page<Announcement> findPublished(
            int page,
            int size) {

        Pageable pageable = PageRequest.of(page, size);

        return announcementRepository.findPublishedAnnouncements(
                LocalDateTime.now(),
                pageable);
    }

    @Transactional(readOnly = true)
    public List<Announcement> findLatestPublished(int limit) {

        Pageable pageable = PageRequest.of(0, limit);

        return announcementRepository.findPublishedAnnouncements(
                LocalDateTime.now(),
                pageable)
                .getContent();
    }

    @Transactional(readOnly = true)
    public Announcement findPublishedById(Long id) {

        return announcementRepository.findPublishedById(
                id,
                LocalDateTime.now())
                .orElseThrow(() -> new AnnouncementNotFoundException(id));
    }

    private void applyForm(
            Announcement announcement,
            AdminAnnouncementForm form) {

        announcement.setType(form.getType());
        announcement.setImportance(form.getImportance());
        announcement.setTitle(form.getTitle().trim());
        announcement.setContent(form.getContent().trim());
        announcement.setPublished(form.isPublished());
        announcement.setPublishedAt(form.getPublishedAt());
    }

    private void validatePublication(AdminAnnouncementForm form) {
        if (form.isPublished() && form.getPublishedAt() == null) {
            throw new IllegalArgumentException(
                    "公開する場合は公開日時を入力してください");
        }
    }

}
