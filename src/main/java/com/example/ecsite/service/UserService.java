package com.example.ecsite.service;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.dto.UserAccountInfo;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
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

        User user = new User();

        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(userForm.getPassword()));
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            userRepository.saveAndFlush(user);

        } catch (DataIntegrityViolationException e) {

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

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ユーザーが見つかりません: " + id));
    }

    public UserAccountInfo getAccountInfo(Long userId) {

        User user = findById(userId);

        return new UserAccountInfo(
                user.getUsername(),
                user.getCreatedAt(),
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

    private String normalizeUsername(String username) {

        return username.trim();
    }
}
