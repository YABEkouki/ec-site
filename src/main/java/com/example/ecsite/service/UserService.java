package com.example.ecsite.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.ecsite.entity.User;
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

        User user = new User();

        user.setUsername(userForm.getUsername());
        user.setPassword(passwordEncoder.encode(userForm.getPassword()));
        user.setEnabled(true);
        user.setRole("ROLE_USER");

        userRepository.save(user);
    }

    public boolean passwordsMatch(UserForm userForm) {
        return userForm.getPassword()
                .equals(userForm.getConfirmPassword());
    }

    public boolean usernameExists(String username) {
        return userRepository.existsByUsername(username);
    }
}