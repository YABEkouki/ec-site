package com.example.ecsite.service;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserEnabledHistory;
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
}
