package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.dto.UserAccountInfo;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.EmailAlreadyExistsException;
import com.example.ecsite.exception.IncorrectCurrentPasswordException;
import com.example.ecsite.exception.SameAsCurrentPasswordException;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.UserAccountEditForm;
import com.example.ecsite.form.UserForm;
import com.example.ecsite.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void register(UserForm userForm) {

        String username = normalizeUsername(userForm.getUsername());
        String email = normalizeEmail(userForm.getEmail());

        User user = new User();

        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(userForm.getPassword()));
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email, null);
        }

        try {
            userRepository.saveAndFlush(user);

        } catch (DataIntegrityViolationException e) {

            if (isConstraintViolation(e, "uq_users_email")) {
                throw new EmailAlreadyExistsException(email, e);
            }

            throw new UsernameAlreadyExistsException(username, e);
        }
    }

    public boolean passwordsMatch(UserForm userForm) {
        return userForm.getPassword()
                .equals(userForm.getConfirmPassword());
    }

    public boolean usernameExists(String username) {
        return userRepository.existsByUsername(normalizeUsername(username));
    }

    public boolean emailExists(String email) {
        return userRepository.existsByEmail(normalizeEmail(email));
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ユーザーが見つかりません: " + id));
    }

    public UserAccountInfo getAccountInfo(Long userId) {

        User user = findById(userId);

        return new UserAccountInfo(
                user.getUsername(),
                user.getEmail(),
                user.getEmailVerifiedAt(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getPreviousLoginAt(),
                user.getLastLoginAt());
    }

    @Transactional
    public void recordSuccessfulLogin(Long userId) {

        User user = findById(userId);

        LocalDateTime now = LocalDateTime.now();

        user.setPreviousLoginAt(user.getLastLoginAt());
        user.setLastLoginAt(now);
    }

    @Transactional
    public void changePassword(
            Long userId,
            String currentPassword,
            String newPassword) {

        User user = findById(userId);

        if (!passwordEncoder.matches(
                currentPassword,
                user.getPassword())) {

            throw new IncorrectCurrentPasswordException();
        }

        if (passwordEncoder.matches(
                newPassword,
                user.getPassword())) {

            throw new SameAsCurrentPasswordException();
        }

        user.setPassword(
                passwordEncoder.encode(newPassword));

        user.setUpdatedAt(LocalDateTime.now());
    }

    public UserAccountEditForm createAccountEditForm(Long userId) {
        User user = findById(userId);

        UserAccountEditForm form = new UserAccountEditForm();
        form.setUsername(user.getUsername());
        form.setEmail(user.getEmail());

        return form;
    }

    @Transactional
    public String updateAccount(
            Long userId,
            String username,
            String email) {

        User user = findById(userId);

        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);

        boolean usernameChanged = !user.getUsername().equals(normalizedUsername);

        boolean emailChanged = user.getEmail() == null
                || !user.getEmail().equals(normalizedEmail);

        if (usernameChanged
                && userRepository.existsByUsername(normalizedUsername)) {
            throw new UsernameAlreadyExistsException(
                    normalizedUsername, null);
        }

        if (emailChanged
                && userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException(
                    normalizedEmail, null);
        }

        if (!usernameChanged && !emailChanged) {
            return normalizedUsername;
        }

        user.setUsername(normalizedUsername);

        if (emailChanged) {
            user.setEmail(normalizedEmail);
            user.setEmailVerifiedAt(null);
        }

        user.setUpdatedAt(LocalDateTime.now());

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {

            if (isConstraintViolation(e, "uq_users_email")) {
                throw new EmailAlreadyExistsException(
                        normalizedEmail, e);
            }

            throw new UsernameAlreadyExistsException(
                    normalizedUsername, e);
        }

        return normalizedUsername;
    }

    private String normalizeUsername(String username) {

        return username.trim();
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isConstraintViolation(
            DataIntegrityViolationException exception,
            String constraintName) {

        Throwable cause = exception;

        while (cause != null) {

            String message = cause.getMessage();

            if (message != null
                    && message.contains(constraintName)) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

}
