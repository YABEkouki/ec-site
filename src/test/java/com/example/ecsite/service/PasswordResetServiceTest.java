package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.entity.PasswordResetToken;
import com.example.ecsite.entity.User;
import com.example.ecsite.repository.PasswordResetTokenRepository;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private UserService userService;

    private SecureTokenService secureTokenService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        secureTokenService = new SecureTokenService();

        service = new PasswordResetService(
                tokenRepository,
                userService,
                secureTokenService);
    }

    @Test
    void issueTokenReturnsNullForUnknownEmail() {

        when(userService.findByEmail("unknown@example.com"))
                .thenReturn(null);

        String rawToken = service.issueToken("unknown@example.com");

        assertNull(rawToken);

        verify(tokenRepository, never())
                .save(any(PasswordResetToken.class));
    }

    @Test
    void issueTokenCreatesHashedTokenWithOneHourExpiration() {

        User user = createUser(
                10L,
                "user@example.com");

        when(userService.findByEmail("user@example.com"))
                .thenReturn(user);

        when(tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.empty());

        when(tokenRepository
                .findByUserIdAndUsedAtIsNull(10L))
                .thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();

        String rawToken = service.issueToken("user@example.com");

        LocalDateTime after = LocalDateTime.now();

        assertNotNull(rawToken);
        assertFalse(rawToken.isBlank());

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(
                PasswordResetToken.class);

        verify(tokenRepository).save(captor.capture());

        PasswordResetToken savedToken = captor.getValue();

        assertEquals(user, savedToken.getUser());
        assertEquals(
                "user@example.com",
                savedToken.getEmail());

        assertNotEquals(
                rawToken,
                savedToken.getTokenHash());

        assertEquals(
                secureTokenService.hashToken(rawToken),
                savedToken.getTokenHash());

        assertTrue(
                savedToken.getCreatedAt()
                        .compareTo(before) >= 0);

        assertTrue(
                savedToken.getCreatedAt()
                        .compareTo(after) <= 0);

        assertEquals(
                savedToken.getCreatedAt().plusHours(1),
                savedToken.getExpiresAt());

        assertNull(savedToken.getUsedAt());
    }

    @Test
    void issueTokenInvalidatesExistingUnusedTokens() {

        User user = createUser(
                10L,
                "user@example.com");

        PasswordResetToken oldToken1 = createToken(
                user,
                "old-hash-1",
                "user@example.com",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(50));

        PasswordResetToken oldToken2 = createToken(
                user,
                "old-hash-2",
                "user@example.com",
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusMinutes(55));

        when(userService.findByEmail("user@example.com"))
                .thenReturn(user);

        when(tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(oldToken2));

        when(tokenRepository
                .findByUserIdAndUsedAtIsNull(10L))
                .thenReturn(List.of(
                        oldToken1,
                        oldToken2));

        String rawToken = service.issueToken("user@example.com");

        assertNotNull(rawToken);
        assertNotNull(oldToken1.getUsedAt());
        assertNotNull(oldToken2.getUsedAt());

        verify(tokenRepository)
                .save(any(PasswordResetToken.class));
    }

    @Test
    void issueTokenReturnsNullWithinCooldown() {

        User user = createUser(
                10L,
                "user@example.com");

        PasswordResetToken latestToken = createToken(
                user,
                "existing-hash",
                "user@example.com",
                LocalDateTime.now().minusSeconds(30),
                LocalDateTime.now().plusMinutes(59));

        when(userService.findByEmail("user@example.com"))
                .thenReturn(user);

        when(tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(latestToken));

        String rawToken = service.issueToken("user@example.com");

        assertNull(rawToken);
        assertNull(latestToken.getUsedAt());

        verify(tokenRepository, never())
                .findByUserIdAndUsedAtIsNull(10L);

        verify(tokenRepository, never())
                .save(any(PasswordResetToken.class));
    }

    @Test
    void validateTokenReturnsInvalidForBlankToken() {

        assertEquals(
                PasswordResetResult.INVALID,
                service.validateToken(null));

        assertEquals(
                PasswordResetResult.INVALID,
                service.validateToken(""));

        assertEquals(
                PasswordResetResult.INVALID,
                service.validateToken("   "));
    }

    @Test
    void validateTokenReturnsInvalidForUnknownToken() {

        String rawToken = "unknown-token";
        String tokenHash = secureTokenService.hashToken(rawToken);

        when(tokenRepository.findByTokenHash(tokenHash))
                .thenReturn(Optional.empty());

        PasswordResetResult result = service.validateToken(rawToken);

        assertEquals(
                PasswordResetResult.INVALID,
                result);
    }

    @Test
    void validateTokenReturnsUsedForUsedToken() {

        User user = createUser(
                10L,
                "user@example.com");

        String rawToken = "used-token";

        PasswordResetToken token = createToken(
                user,
                secureTokenService.hashToken(rawToken),
                "user@example.com",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(50));

        token.setUsedAt(
                LocalDateTime.now().minusMinutes(1));

        when(tokenRepository.findByTokenHash(
                token.getTokenHash()))
                .thenReturn(Optional.of(token));

        PasswordResetResult result = service.validateToken(rawToken);

        assertEquals(
                PasswordResetResult.USED,
                result);
    }

    @Test
    void validateTokenReturnsExpiredForExpiredToken() {

        User user = createUser(
                10L,
                "user@example.com");

        String rawToken = "expired-token";

        PasswordResetToken token = createToken(
                user,
                secureTokenService.hashToken(rawToken),
                "user@example.com",
                LocalDateTime.now().minusHours(2),
                LocalDateTime.now().minusHours(1));

        when(tokenRepository.findByTokenHash(
                token.getTokenHash()))
                .thenReturn(Optional.of(token));

        PasswordResetResult result = service.validateToken(rawToken);

        assertEquals(
                PasswordResetResult.EXPIRED,
                result);
    }

    @Test
    void validateTokenReturnsEmailChangedWhenEmailDiffers() {

        User user = createUser(
                10L,
                "new@example.com");

        String rawToken = "old-email-token";

        PasswordResetToken token = createToken(
                user,
                secureTokenService.hashToken(rawToken),
                "old@example.com",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(50));

        when(tokenRepository.findByTokenHash(
                token.getTokenHash()))
                .thenReturn(Optional.of(token));

        PasswordResetResult result = service.validateToken(rawToken);

        assertEquals(
                PasswordResetResult.EMAIL_CHANGED,
                result);
    }

    @Test
    void validateTokenReturnsValidForValidToken() {

        User user = createUser(
                10L,
                "user@example.com");

        String rawToken = "valid-token";

        PasswordResetToken token = createToken(
                user,
                secureTokenService.hashToken(rawToken),
                "user@example.com",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(50));

        when(tokenRepository.findByTokenHash(
                token.getTokenHash()))
                .thenReturn(Optional.of(token));

        PasswordResetResult result = service.validateToken(rawToken);

        assertEquals(
                PasswordResetResult.VALID,
                result);
    }

    @Test
    void resetPasswordUpdatesPasswordAndInvalidatesAllUnusedTokens() {

        User user = createUser(
                10L,
                "user@example.com");

        String rawToken = "valid-reset-token";
        String tokenHash = secureTokenService.hashToken(rawToken);

        PasswordResetToken resetToken = createToken(
                user,
                tokenHash,
                "user@example.com",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(50));

        PasswordResetToken anotherToken = createToken(
                user,
                "another-hash",
                "user@example.com",
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusMinutes(55));

        when(tokenRepository.findByTokenHash(tokenHash))
                .thenReturn(Optional.of(resetToken));

        when(tokenRepository
                .findByUserIdAndUsedAtIsNull(10L))
                .thenReturn(List.of(
                        resetToken,
                        anotherToken));

        PasswordResetResult result = service.resetPassword(
                rawToken,
                "new-password");

        assertEquals(
                PasswordResetResult.VALID,
                result);

        verify(userService).resetPassword(
                10L,
                "new-password");

        assertNotNull(resetToken.getUsedAt());
        assertNotNull(anotherToken.getUsedAt());
    }

    @Test
    void resetPasswordDoesNotUpdatePasswordForInvalidToken() {

        String rawToken = "invalid-token";
        String tokenHash = secureTokenService.hashToken(rawToken);

        when(tokenRepository.findByTokenHash(tokenHash))
                .thenReturn(Optional.empty());

        PasswordResetResult result = service.resetPassword(
                rawToken,
                "new-password");

        assertEquals(
                PasswordResetResult.INVALID,
                result);

        verify(userService, never())
                .resetPassword(
                        any(),
                        any());
    }

    @Test
    void invalidateTokenMarksUnusedTokenAsUsed() {

        User user = createUser(
                10L,
                "user@example.com");

        String rawToken = "mail-failed-token";
        String tokenHash = secureTokenService.hashToken(rawToken);

        PasswordResetToken token = createToken(
                user,
                tokenHash,
                "user@example.com",
                LocalDateTime.now(),
                LocalDateTime.now().plusHours(1));

        when(tokenRepository.findByTokenHash(tokenHash))
                .thenReturn(Optional.of(token));

        service.invalidateToken(rawToken);

        assertNotNull(token.getUsedAt());
    }

    @Test
    void issueTokenReturnsNullForDisabledUser() {

        User user = createUser(
                10L,
                "disabled@example.com");

        user.setEnabled(false);

        when(userService.findByEmail("disabled@example.com"))
                .thenReturn(user);

        String rawToken = service.issueToken("disabled@example.com");

        assertNull(rawToken);

        verify(tokenRepository, never())
                .findTopByUserIdOrderByCreatedAtDesc(10L);

        verify(tokenRepository, never())
                .save(any(PasswordResetToken.class));
    }

    @Test
    void validateTokenReturnsInvalidForDisabledUser() {

        User user = createUser(
                10L,
                "disabled@example.com");

        user.setEnabled(false);

        String rawToken = "disabled-user-token";

        PasswordResetToken token = createToken(
                user,
                secureTokenService.hashToken(rawToken),
                "disabled@example.com",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(50));

        when(tokenRepository.findByTokenHash(
                token.getTokenHash()))
                .thenReturn(Optional.of(token));

        PasswordResetResult result = service.validateToken(rawToken);

        assertEquals(
                PasswordResetResult.INVALID,
                result);
    }

    @Test
    void invalidateUnusedTokensMarksAllUnusedTokensAsUsed() {

        User user = createUser(
                10L,
                "user@example.com");

        PasswordResetToken token1 = createToken(
                user,
                "token-hash-1",
                "user@example.com",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(50));

        PasswordResetToken token2 = createToken(
                user,
                "token-hash-2",
                "user@example.com",
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusMinutes(55));

        when(tokenRepository
                .findByUserIdAndUsedAtIsNull(10L))
                .thenReturn(List.of(token1, token2));

        service.invalidateUnusedTokens(10L);

        assertNotNull(token1.getUsedAt());
        assertNotNull(token2.getUsedAt());
    }

    private User createUser(
            Long id,
            String email) {

        User user = new User();

        ReflectionTestUtils.setField(
                user,
                "id",
                id);

        user.setEmail(email);

        return user;
    }

    private PasswordResetToken createToken(
            User user,
            String tokenHash,
            String email,
            LocalDateTime createdAt,
            LocalDateTime expiresAt) {

        PasswordResetToken token = new PasswordResetToken();

        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setEmail(email);
        token.setCreatedAt(createdAt);
        token.setExpiresAt(expiresAt);

        return token;
    }
}
