package com.example.ecsite.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.ecsite.entity.User;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.UserForm;
import com.example.ecsite.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
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

    private String normalizeUsername(String username) {

        return username.trim();
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ユーザーが見つかりません: " + id));
    }
}
