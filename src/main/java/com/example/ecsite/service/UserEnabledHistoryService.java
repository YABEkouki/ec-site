package com.example.ecsite.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserEnabledHistory;
import com.example.ecsite.form.AdminUserEnabledHistorySearchForm;
import com.example.ecsite.repository.UserEnabledHistoryRepository;

@Service
public class UserEnabledHistoryService {

    private final UserEnabledHistoryRepository repository;

    public UserEnabledHistoryService(
            UserEnabledHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(
            User user,
            boolean fromEnabled,
            boolean toEnabled,
            Long changedByAccountId,
            String changedByUsername) {

        UserEnabledHistory history = UserEnabledHistory.create(
                user,
                fromEnabled,
                toEnabled,
                changedByAccountId,
                changedByUsername);

        repository.save(history);
    }

    @Transactional(readOnly = true)
    public Page<UserEnabledHistory> search(
            AdminUserEnabledHistorySearchForm form,
            int page,
            int size) {

        return search(
                form,
                PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public List<UserEnabledHistory> searchAll(
            AdminUserEnabledHistorySearchForm form) {

        return search(
                form,
                Pageable.unpaged())
                .getContent();
    }

    private Page<UserEnabledHistory> search(
            AdminUserEnabledHistorySearchForm form,
            Pageable pageable) {

        LocalDateTime from = form.getFrom() != null
                ? form.getFrom().atStartOfDay()
                : LocalDate.of(2000, 1, 1).atStartOfDay();

        LocalDateTime toExclusive = form.getTo() != null
                ? form.getTo().plusDays(1).atStartOfDay()
                : LocalDate.of(2100, 1, 1).atStartOfDay();

        String username = normalize(form.getUsername());
        String changedByUsername = normalize(form.getChangedByUsername());

        return repository.search(
                form.getUserId(),
                username,
                form.getToEnabled(),
                changedByUsername,
                from,
                toExclusive,
                pageable);
    }

    private String normalize(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }
}
