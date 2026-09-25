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
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.dto.UserAccountInfo;
import com.example.ecsite.dto.UserAccountUpdateResult;
import com.example.ecsite.dto.UserRegistrationResult;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.EmailAlreadyExistsException;
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
        userForm.setEmail("user1@example.com");
        userForm.setPassword("password123");
        userForm.setConfirmPassword("password123");

        when(passwordEncoder.encode("password123"))
                .thenReturn("encoded-password");

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);

                    ReflectionTestUtils.setField(
                            user,
                            "id",
                            100L);

                    return user;
                });

        UserRegistrationResult result = userService.register(userForm);

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

        assertEquals(
                "user1@example.com",
                savedUser.getEmail());

        assertNull(savedUser.getEmailVerifiedAt());

        assertEquals(
                100L,
                result.userId());

        assertEquals(
                "user1@example.com",
                result.email());

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
        userForm.setEmail("user1@example.com");
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
        LocalDateTime emailVerifiedAt = LocalDateTime.of(2026, 9, 12, 15, 0);

        User user = new User();
        user.setUsername("user1");
        user.setEmail("user1@example.com");
        user.setEmailVerifiedAt(emailVerifiedAt);
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
        assertEquals(
                "user1@example.com",
                accountInfo.email());

        assertEquals(
                emailVerifiedAt,
                accountInfo.emailVerifiedAt());
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
    void createAccountEditFormReturnsCurrentAccountInformation() {

        User user = new User();
        user.setUsername("user1");
        user.setEmail("user1@example.com");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        UserAccountEditForm form = userService.createAccountEditForm(10L);

        assertEquals("user1", form.getUsername());
        assertEquals("user1@example.com", form.getEmail());
    }

    @Test
    void updateAccountNormalizesUsernameAndUpdatesUpdatedAt() {

        LocalDateTime oldUpdatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        LocalDateTime previousLoginAt = LocalDateTime.of(2026, 9, 14, 9, 0);

        LocalDateTime lastLoginAt = LocalDateTime.of(2026, 9, 15, 9, 0);

        User user = new User();
        user.setUsername("user1");
        user.setEmail("user1@example.com");
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

        UserAccountUpdateResult result = userService.updateAccount(
                10L,
                " user2 ",
                "user1@example.com");

        assertEquals("user2", result.username());
        assertEquals("user1@example.com", result.email());
        assertFalse(result.emailChanged());
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
    void updateAccountDoesNothingWhenNormalizedUsernameIsUnchanged() {

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        User user = new User();
        user.setUsername("user1");
        user.setEmail("user1@example.com");
        user.setUpdatedAt(updatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        UserAccountUpdateResult result = userService.updateAccount(
                10L,
                " user1 ",
                "user1@example.com");

        assertEquals("user1", result.username());
        assertEquals("user1@example.com", result.email());
        assertFalse(result.emailChanged());
        assertEquals(updatedAt, user.getUpdatedAt());

        verify(userRepository, never())
                .existsByUsername(any());

        verify(userRepository, never())
                .saveAndFlush(any(User.class));
    }

    @Test
    void updateAccountRejectsExistingUsername() {

        User user = new User();
        user.setUsername("user1");
        user.setEmail("user1@example.com");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userRepository.existsByUsername("user2"))
                .thenReturn(true);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                UsernameAlreadyExistsException.class,
                () -> userService.updateAccount(
                        10L,
                        "user2",
                        "user1@example.com"));

        assertEquals("user1", user.getUsername());

        verify(userRepository, never())
                .saveAndFlush(any(User.class));
    }

    @Test
    void updateAccountConvertsDatabaseDuplicateException() {

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
                () -> userService.updateAccount(
                        10L,
                        "user2",
                        "user1@example.com"));
    }

    @Test
    void emailExistsUsesNormalizedEmail() {

        when(userRepository
                .existsByEmail("user1@example.com"))
                .thenReturn(true);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        boolean result = userService.emailExists(
                " User1@Example.COM ");

        assertTrue(result);

        verify(userRepository)
                .existsByEmail("user1@example.com");
    }

    @Test
    void updateAccountNormalizesEmailAndResetsVerification() {

        LocalDateTime oldUpdatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        LocalDateTime verifiedAt = LocalDateTime.of(2026, 9, 10, 12, 0);

        User user = new User();
        user.setUsername("user1");
        user.setEmail("old@example.com");
        user.setEmailVerifiedAt(verifiedAt);
        user.setUpdatedAt(oldUpdatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userRepository.existsByEmail("new@example.com"))
                .thenReturn(false);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        UserAccountUpdateResult result = userService.updateAccount(
                10L,
                "user1",
                " New@Example.COM ");

        assertEquals("user1", result.username());
        assertEquals("new@example.com", result.email());
        assertTrue(result.emailChanged());
        assertNull(user.getEmailVerifiedAt());

        assertTrue(
                user.getUpdatedAt()
                        .isAfter(oldUpdatedAt));

        verify(userRepository)
                .saveAndFlush(user);
    }

    @Test
    void updateAccountKeepsVerificationWhenEmailIsUnchanged() {

        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

        LocalDateTime verifiedAt = LocalDateTime.of(2026, 9, 10, 12, 0);

        User user = new User();
        user.setUsername("user1");
        user.setEmail("user1@example.com");
        user.setEmailVerifiedAt(verifiedAt);
        user.setUpdatedAt(updatedAt);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        UserAccountUpdateResult result = userService.updateAccount(
                10L,
                " user1 ",
                " User1@Example.COM ");

        assertEquals("user1", result.username());
        assertEquals("user1@example.com", result.email());
        assertFalse(result.emailChanged());
        assertEquals(
                verifiedAt,
                user.getEmailVerifiedAt());

        assertEquals(updatedAt, user.getUpdatedAt());

        verify(userRepository, never())
                .saveAndFlush(any(User.class));
    }

    @Test
    void updateAccountRejectsExistingEmail() {

        User user = new User();
        user.setUsername("user1");
        user.setEmail("old@example.com");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userRepository.existsByEmail("used@example.com"))
                .thenReturn(true);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                EmailAlreadyExistsException.class,
                () -> userService.updateAccount(
                        10L,
                        "user1",
                        " Used@Example.COM "));

        assertEquals("old@example.com", user.getEmail());

        verify(userRepository, never())
                .saveAndFlush(any(User.class));
    }

    @Test
    void registerConvertsDatabaseEmailDuplicateException() {

        UserForm userForm = new UserForm();
        userForm.setUsername("user1");
        userForm.setEmail("user1@example.com");
        userForm.setPassword("password123");
        userForm.setConfirmPassword("password123");

        when(passwordEncoder.encode("password123"))
                .thenReturn("encodedPassword");

        when(userRepository.existsByEmail("user1@example.com"))
                .thenReturn(false);

        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_users_email\"");

        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(exception);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                EmailAlreadyExistsException.class,
                () -> userService.register(userForm));
    }

    @Test
    void updateAccountConvertsDatabaseEmailDuplicateException() {

        User user = new User();
        user.setUsername("user1");
        user.setEmail("old@example.com");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userRepository.existsByEmail("new@example.com"))
                .thenReturn(false);

        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_users_email\"");

        when(userRepository.saveAndFlush(user))
                .thenThrow(exception);

        UserService userService = new UserService(
                userRepository,
                passwordEncoder);

        assertThrows(
                EmailAlreadyExistsException.class,
                () -> userService.updateAccount(
                        10L,
                        "user1",
                        "new@example.com"));
    }

}
