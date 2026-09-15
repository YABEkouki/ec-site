package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.ecsite.dto.UserAccountInfo;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.IncorrectCurrentPasswordException;
import com.example.ecsite.exception.SameAsCurrentPasswordException;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.UserAccountEditForm;
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

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        userService.register(userForm);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        verify(userRepository)
                .saveAndFlush(
                        userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertEquals(
                "user1",
                savedUser.getUsername());

        assertEquals(
                "encoded-password",
                savedUser.getPassword());

        assertTrue(savedUser.isEnabled());

        assertNotNull(savedUser.getCreatedAt());
        assertNotNull(savedUser.getUpdatedAt());

        assertEquals(
                savedUser.getCreatedAt(),
                savedUser.getUpdatedAt());

        assertNull(savedUser.getPreviousLoginAt());
        assertNull(savedUser.getLastLoginAt());

        verify(passwordEncoder)
                .encode("password123");
    }

    @Test
    void usernameExistsUsesNormalizedUsername() {

        when(userRepository
                .existsByUsername("user1"))
                .thenReturn(true);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        boolean result = userService.usernameExists(
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

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                UsernameAlreadyExistsException.class,
                () -> userService.register(
                        userForm));
    }

    @Test
    void getAccountInfoReturnsAccountInformation() {

        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 10, 12, 0);

        LocalDateTime previousLoginAt = LocalDateTime.of(2026, 9, 14, 9, 0);

        LocalDateTime lastLoginAt = LocalDateTime.of(2026, 9, 15, 9, 0);

        User user = new User();
        user.setUsername("user1");
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(updatedAt);
        user.setPreviousLoginAt(previousLoginAt);
        user.setLastLoginAt(lastLoginAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        UserAccountInfo accountInfo = userService.getAccountInfo(10L);

        assertEquals("user1", accountInfo.username());
        assertEquals(createdAt, accountInfo.createdAt());
        assertEquals(updatedAt, accountInfo.updatedAt());
        assertEquals(
                previousLoginAt,
                accountInfo.previousLoginAt());
        assertEquals(lastLoginAt, accountInfo.lastLoginAt());
    }

    @Test
    void recordSuccessfulLoginSetsLastLoginOnFirstLogin() {

        User user = new User();

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        user.setUpdatedAt(updatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        userService.recordSuccessfulLogin(10L);

        assertNull(user.getPreviousLoginAt());
        assertNotNull(user.getLastLoginAt());

        assertEquals(
                updatedAt,
                user.getUpdatedAt());
    }

    @Test
    void recordSuccessfulLoginMovesLastLoginToPreviousLogin() {

        LocalDateTime oldLastLoginAt = LocalDateTime.of(2026, 9, 14, 9, 0);

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        User user = new User();
        user.setLastLoginAt(oldLastLoginAt);
        user.setUpdatedAt(updatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        userService.recordSuccessfulLogin(10L);

        assertEquals(
                oldLastLoginAt,
                user.getPreviousLoginAt());

        assertNotNull(user.getLastLoginAt());

        assertFalse(
                user.getLastLoginAt()
                        .isBefore(oldLastLoginAt));

        assertEquals(
                updatedAt,
                user.getUpdatedAt());
    }

    @Test
    void changePasswordChangesPasswordAndUpdatedAt() {

        User user = new User();

        LocalDateTime oldUpdatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        user.setPassword("encoded-current-password");
        user.setUpdatedAt(oldUpdatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "current-password",
                "encoded-current-password"))
                .thenReturn(true);

        when(passwordEncoder.matches(
                "new-password",
                "encoded-current-password"))
                .thenReturn(false);

        when(passwordEncoder.encode("new-password"))
                .thenReturn("encoded-new-password");

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        userService.changePassword(
                10L,
                "current-password",
                "new-password");

        assertEquals(
                "encoded-new-password",
                user.getPassword());

        assertTrue(
                user.getUpdatedAt()
                        .isAfter(oldUpdatedAt));

        verify(passwordEncoder)
                .encode("new-password");
    }

    @Test
    void changePasswordRejectsIncorrectCurrentPassword() {

        User user = new User();
        user.setPassword("encoded-current-password");

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        user.setUpdatedAt(updatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "wrong-password",
                "encoded-current-password"))
                .thenReturn(false);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                IncorrectCurrentPasswordException.class,
                () -> userService.changePassword(
                        10L,
                        "wrong-password",
                        "new-password"));

        assertEquals(
                "encoded-current-password",
                user.getPassword());

        assertEquals(
                updatedAt,
                user.getUpdatedAt());
    }

    @Test
    void changePasswordRejectsSamePasswordAsCurrentPassword() {

        User user = new User();
        user.setPassword("encoded-current-password");

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        user.setUpdatedAt(updatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "current-password",
                "encoded-current-password"))
                .thenReturn(true);

        when(passwordEncoder.matches(
                "same-password",
                "encoded-current-password"))
                .thenReturn(true);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                SameAsCurrentPasswordException.class,
                () -> userService.changePassword(
                        10L,
                        "current-password",
                        "same-password"));

        assertEquals(
                "encoded-current-password",
                user.getPassword());

        assertEquals(
                updatedAt,
                user.getUpdatedAt());
    }

    @Test
    void createAccountEditFormReturnsCurrentUsername() {

        User user = new User();
        user.setUsername("user1");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        UserAccountEditForm form = userService.createAccountEditForm(10L);

        assertEquals("user1", form.getUsername());
    }

    @Test
    void updateUsernameNormalizesUsernameAndUpdatesUpdatedAt() {

        LocalDateTime oldUpdatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        LocalDateTime previousLoginAt = LocalDateTime.of(2026, 9, 14, 9, 0);

        LocalDateTime lastLoginAt = LocalDateTime.of(2026, 9, 15, 9, 0);

        User user = new User();
        user.setUsername("user1");
        user.setUpdatedAt(oldUpdatedAt);
        user.setPreviousLoginAt(previousLoginAt);
        user.setLastLoginAt(lastLoginAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userRepository.existsByUsername("user2"))
                .thenReturn(false);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        String result = userService.updateUsername(10L, " user2 ");

        assertEquals("user2", result);
        assertEquals("user2", user.getUsername());

        assertTrue(
                user.getUpdatedAt()
                        .isAfter(oldUpdatedAt));

        assertEquals(
                previousLoginAt,
                user.getPreviousLoginAt());

        assertEquals(
                lastLoginAt,
                user.getLastLoginAt());

        verify(userRepository)
                .saveAndFlush(user);
    }

    @Test
    void updateUsernameDoesNothingWhenNormalizedUsernameIsUnchanged() {

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        User user = new User();
        user.setUsername("user1");
        user.setUpdatedAt(updatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        String result = userService.updateUsername(10L, " user1 ");

        assertEquals("user1", result);
        assertEquals(updatedAt, user.getUpdatedAt());

        verify(userRepository, never())
                .existsByUsername(any());

        verify(userRepository, never())
                .saveAndFlush(any(User.class));
    }

    @Test
    void updateUsernameRejectsExistingUsername() {

        User user = new User();
        user.setUsername("user1");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userRepository.existsByUsername("user2"))
                .thenReturn(true);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                UsernameAlreadyExistsException.class,
                () -> userService.updateUsername(
                        10L,
                        "user2"));

        assertEquals("user1", user.getUsername());

        verify(userRepository, never())
                .saveAndFlush(any(User.class));
    }

    @Test
    void updateUsernameConvertsDatabaseDuplicateException() {

        User user = new User();
        user.setUsername("user1");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userRepository.existsByUsername("user2"))
                .thenReturn(false);

        when(userRepository.saveAndFlush(user))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "duplicate username"));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                UsernameAlreadyExistsException.class,
                () -> userService.updateUsername(
                        10L,
                        "user2"));
    }

}
