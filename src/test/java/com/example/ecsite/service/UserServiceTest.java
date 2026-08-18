package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.ecsite.entity.User;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.UserForm;
import com.example.ecsite.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void registerNormalizesUsernameAndCreatesUser() {

        UserForm userForm = new UserForm();

        userForm.setUsername(" user1 ");
        userForm.setPassword("password123");
        userForm.setConfirmPassword("password123");

        when(passwordEncoder.encode("password123"))
                .thenReturn("encoded-password");

        UserService userService =
                new UserService(
                        userRepository,
                        passwordEncoder);

        userService.register(userForm);

        ArgumentCaptor<User> userCaptor =
                ArgumentCaptor.forClass(User.class);

        verify(userRepository)
                .saveAndFlush(
                        userCaptor.capture());

        User savedUser =
                userCaptor.getValue();

        assertEquals(
                "user1",
                savedUser.getUsername());

        assertEquals(
                "encoded-password",
                savedUser.getPassword());

        assertTrue(savedUser.isEnabled());

        assertEquals(
                "ROLE_USER",
                savedUser.getRole());

        verify(passwordEncoder)
                .encode("password123");
    }

    @Test
    void usernameExistsUsesNormalizedUsername() {

        when(userRepository
                .existsByUsername("user1"))
                .thenReturn(true);

        UserService userService =
                new UserService(
                        userRepository,
                        passwordEncoder);

        boolean result =
                userService.usernameExists(
                        " user1 ");

        assertTrue(result);

        verify(userRepository)
                .existsByUsername("user1");
    }

    @Test
    void registerConvertsDatabaseDuplicateException() {

        UserForm userForm = new UserForm();

        userForm.setUsername("user1");
        userForm.setPassword("password123");
        userForm.setConfirmPassword("password123");

        when(passwordEncoder.encode("password123"))
                .thenReturn("encoded-password");

        when(userRepository
                .saveAndFlush(any(User.class)))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "duplicate username"));

        UserService userService =
                new UserService(
                        userRepository,
                        passwordEncoder);

        assertThrows(
                UsernameAlreadyExistsException.class,
                () -> userService.register(
                        userForm));
    }
}