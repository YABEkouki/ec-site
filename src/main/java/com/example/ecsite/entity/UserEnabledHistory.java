package com.example.ecsite.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_enabled_histories")
public class UserEnabledHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "from_enabled", nullable = false)
    private boolean fromEnabled;

    @Column(name = "to_enabled", nullable = false)
    private boolean toEnabled;

    @Column(name = "changed_by_account_id", nullable = false)
    private Long changedByAccountId;

    @Column(name = "changed_by_username", nullable = false, length = 100)
    private String changedByUsername;

    @Column(
            name = "changed_at",
            nullable = false,
            insertable = false,
            updatable = false)
    private LocalDateTime changedAt;

    protected UserEnabledHistory() {
    }

    public static UserEnabledHistory create(
            User user,
            boolean fromEnabled,
            boolean toEnabled,
            Long changedByAccountId,
            String changedByUsername) {

        UserEnabledHistory history = new UserEnabledHistory();

        history.user = user;
        history.fromEnabled = fromEnabled;
        history.toEnabled = toEnabled;
        history.changedByAccountId = changedByAccountId;
        history.changedByUsername = changedByUsername;

        return history;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public boolean isFromEnabled() {
        return fromEnabled;
    }

    public boolean isToEnabled() {
        return toEnabled;
    }

    public Long getChangedByAccountId() {
        return changedByAccountId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
