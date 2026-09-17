package com.example.ecsite.form;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.example.ecsite.entity.AnnouncementImportance;
import com.example.ecsite.entity.AnnouncementType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class AdminAnnouncementForm {

    @NotNull(message = "種別を選択してください")
    private AnnouncementType type;

    @NotNull(message = "重要度を選択してください")
    private AnnouncementImportance importance;

    @NotBlank(message = "タイトルを入力してください")
    @Size(max = 200, message = "タイトルは200文字以内で入力してください")
    private String title;

    @NotBlank(message = "本文を入力してください")
    @Size(max = 5000, message = "本文は5000文字以内で入力してください")
    private String content;

    private boolean published;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime publishedAt;

    public AnnouncementType getType() {
        return type;
    }

    public void setType(AnnouncementType type) {
        this.type = type;
    }

    public AnnouncementImportance getImportance() {
        return importance;
    }

    public void setImportance(AnnouncementImportance importance) {
        this.importance = importance;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

}
