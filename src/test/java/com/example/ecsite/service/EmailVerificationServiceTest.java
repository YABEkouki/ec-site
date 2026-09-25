package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.EmailVerificationToken;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.EmailAlreadyVerifiedException;
import com.example.ecsite.exception.EmailNotRegisteredException;
import com.example.ecsite.exception.EmailVerificationTooSoonException;
import com.example.ecsite.repository.EmailVerificationTokenRepository;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationTokenRepository tokenRepository;

    @Mock
    private UserService userService;

    @Test
    void issueTokenCreatesHashedTokenWithTwentyFourHourExpiration() {

        User user = createUser("user@example.com");

        when(userService.findById(10L))
                .thenReturn(user);

        when(tokenRepository.findByUserIdAndUsedAtIsNull(10L))
                .thenReturn(List.of());

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        LocalDateTime before = LocalDateTime.now();

        String rawToken = service.issueToken(10L);

        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<EmailVerificationToken> captor = ArgumentCaptor.forClass(
                EmailVerificationToken.class);

        verify(tokenRepository).save(captor.capture());

        EmailVerificationToken savedToken = captor.getValue();

        assertNotNull(rawToken);
        assertFalse(rawToken.isBlank());

        assertEquals(user, savedToken.getUser());
        assertEquals(
                "user@example.com",
                savedToken.getEmail());

        assertNotEquals(
                rawToken,
                savedToken.getTokenHash());

        assertEquals(
                sha256(rawToken),
                savedToken.getTokenHash());

        assertEquals(
                64,
                savedToken.getTokenHash().length());

        assertFalse(
                savedToken.getCreatedAt()
                        .isBefore(before));

        assertFalse(
                savedToken.getCreatedAt()
                        .isAfter(after));

        assertEquals(
                savedToken.getCreatedAt().plusHours(24),
                savedToken.getExpiresAt());
    }

    @Test
    void issueTokenInvalidatesExistingUnusedTokens() {

        User user = createUser("user@example.com");

        EmailVerificationToken oldToken1 = new EmailVerificationToken();

        EmailVerificationToken oldToken2 = new EmailVerificationToken();

        when(userService.findById(10L))
                .thenReturn(user);

        when(tokenRepository.findByUserIdAndUsedAtIsNull(10L))
                .thenReturn(List.of(
                        oldToken1,
                        oldToken2));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        LocalDateTime before = LocalDateTime.now();

        service.issueToken(10L);

        LocalDateTime after = LocalDateTime.now();

        assertNotNull(oldToken1.getUsedAt());
        assertNotNull(oldToken2.getUsedAt());

        assertFalse(
                oldToken1.getUsedAt()
                        .isBefore(before));

        assertFalse(
                oldToken1.getUsedAt()
                        .isAfter(after));

        assertEquals(
                oldToken1.getUsedAt(),
                oldToken2.getUsedAt());
    }

    @Test
    void issueTokenGeneratesDifferentTokens() {

        User user = createUser("user@example.com");

        when(userService.findById(10L))
                .thenReturn(user);

        when(tokenRepository.findByUserIdAndUsedAtIsNull(10L))
                .thenReturn(List.of());

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        String first = service.issueToken(10L);
        String second = service.issueToken(10L);

        assertNotEquals(first, second);
    }

    @Test
    void issueTokenRejectsUserWithoutEmail() {

        User user = createUser(null);

        when(userService.findById(10L))
                .thenReturn(user);

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        assertThrows(
                EmailNotRegisteredException.class,
                () -> service.issueToken(10L));

        verify(tokenRepository, never())
                .save(any(EmailVerificationToken.class));
    }

    @Test
    void issueTokenRejectsAlreadyVerifiedEmail() {

        User user = createUser("user@example.com");
        user.setEmailVerifiedAt(LocalDateTime.now());

        when(userService.findById(10L))
                .thenReturn(user);

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        assertThrows(
                EmailAlreadyVerifiedException.class,
                () -> service.issueToken(10L));

        verify(tokenRepository, never())
                .save(any(EmailVerificationToken.class));
    }

    private User createUser(String email) {

        User user = new User();
        user.setEmail(email);

        return user;
    }

    @Test
    void verifyMarksEmailAsVerified() {

        User user = createUser("user@example.com");

        EmailVerificationToken token = createToken(
                user,
                "raw-token",
                "user@example.com",
                LocalDateTime.now().plusHours(1),
                null);

        when(tokenRepository.findByTokenHash(
                sha256("raw-token")))
                .thenReturn(java.util.Optional.of(token));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        EmailVerificationResult result = service.verify("raw-token");

        assertEquals(
                EmailVerificationResult.VERIFIED,
                result);

        assertNotNull(user.getEmailVerifiedAt());
        assertNotNull(token.getUsedAt());

        assertEquals(
                user.getEmailVerifiedAt(),
                token.getUsedAt());
    }

    @Test
    void verifyReturnsInvalidForUnknownToken() {

        when(tokenRepository.findByTokenHash(
                sha256("unknown-token")))
                .thenReturn(java.util.Optional.empty());

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        EmailVerificationResult result = service.verify("unknown-token");

        assertEquals(
                EmailVerificationResult.INVALID,
                result);
    }

    @Test
    void verifyReturnsExpiredForExpiredToken() {

        User user = createUser("user@example.com");

        EmailVerificationToken token = createToken(
                user,
                "expired-token",
                "user@example.com",
                LocalDateTime.now().minusMinutes(1),
                null);

        when(tokenRepository.findByTokenHash(
                sha256("expired-token")))
                .thenReturn(java.util.Optional.of(token));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        EmailVerificationResult result = service.verify("expired-token");

        assertEquals(
                EmailVerificationResult.EXPIRED,
                result);

        assertNull(user.getEmailVerifiedAt());
        assertNull(token.getUsedAt());
    }

    @Test
    void verifyReturnsUsedForUsedToken() {

        User user = createUser("user@example.com");

        LocalDateTime usedAt = LocalDateTime.now().minusMinutes(10);

        EmailVerificationToken token = createToken(
                user,
                "used-token",
                "user@example.com",
                LocalDateTime.now().plusHours(1),
                usedAt);

        when(tokenRepository.findByTokenHash(
                sha256("used-token")))
                .thenReturn(java.util.Optional.of(token));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        EmailVerificationResult result = service.verify("used-token");

        assertEquals(
                EmailVerificationResult.USED,
                result);

        assertNull(user.getEmailVerifiedAt());
        assertEquals(usedAt, token.getUsedAt());
    }

    @Test
    void verifyReturnsEmailChangedWhenCurrentEmailDiffers() {

        User user = createUser("new@example.com");

        EmailVerificationToken token = createToken(
                user,
                "old-email-token",
                "old@example.com",
                LocalDateTime.now().plusHours(1),
                null);

        when(tokenRepository.findByTokenHash(
                sha256("old-email-token")))
                .thenReturn(java.util.Optional.of(token));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        EmailVerificationResult result = service.verify("old-email-token");

        assertEquals(
                EmailVerificationResult.EMAIL_CHANGED,
                result);

        assertNull(user.getEmailVerifiedAt());
        assertNull(token.getUsedAt());
    }

    @Test
    void verifyReturnsAlreadyVerifiedForUsedSuccessfulToken() {

        User user = createUser("user@example.com");

        LocalDateTime verifiedAt = LocalDateTime.now().minusMinutes(10);

        user.setEmailVerifiedAt(verifiedAt);

        EmailVerificationToken token = createToken(
                user,
                "verified-token",
                "user@example.com",
                LocalDateTime.now().plusHours(1),
                verifiedAt);

        when(tokenRepository.findByTokenHash(
                sha256("verified-token")))
                .thenReturn(java.util.Optional.of(token));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        EmailVerificationResult result = service.verify("verified-token");

        assertEquals(
                EmailVerificationResult.ALREADY_VERIFIED,
                result);

        assertEquals(
                verifiedAt,
                user.getEmailVerifiedAt());

        assertEquals(
                verifiedAt,
                token.getUsedAt());
    }

    @Test
    void issueTokenRejectsResendWithinCooldownWithoutInvalidatingExistingToken() {

        User user = createUser("user@example.com");

        EmailVerificationToken existingToken = new EmailVerificationToken();

        existingToken.setUser(user);
        existingToken.setTokenHash(sha256("existing-token"));
        existingToken.setEmail("user@example.com");
        existingToken.setCreatedAt(
                LocalDateTime.now().minusSeconds(30));
        existingToken.setExpiresAt(
                LocalDateTime.now().plusHours(24));

        when(userService.findById(user.getId()))
                .thenReturn(user);

        when(tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(java.util.Optional.of(existingToken));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        assertThrows(
                EmailVerificationTooSoonException.class,
                () -> service.issueToken(user.getId()));

        assertNull(existingToken.getUsedAt());

        verify(tokenRepository, never())
                .findByUserIdAndUsedAtIsNull(user.getId());

        verify(tokenRepository, never())
                .save(any(EmailVerificationToken.class));
    }

    @Test
    void issueTokenAllowsResendAfterCooldownAndInvalidatesExistingToken() {

        User user = createUser("user@example.com");

        EmailVerificationToken existingToken = new EmailVerificationToken();

        existingToken.setUser(user);
        existingToken.setTokenHash(sha256("existing-token"));
        existingToken.setEmail("user@example.com");
        existingToken.setCreatedAt(
                LocalDateTime.now().minusSeconds(61));
        existingToken.setExpiresAt(
                LocalDateTime.now().plusHours(24));

        when(userService.findById(user.getId()))
                .thenReturn(user);

        when(tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(java.util.Optional.of(existingToken));

        when(tokenRepository
                .findByUserIdAndUsedAtIsNull(user.getId()))
                .thenReturn(java.util.List.of(existingToken));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        String rawToken = service.issueToken(user.getId());

        assertNotNull(rawToken);

        assertNotNull(existingToken.getUsedAt());

        verify(tokenRepository)
                .findByUserIdAndUsedAtIsNull(user.getId());

        verify(tokenRepository)
                .save(any(EmailVerificationToken.class));
    }

    @Test
    void issueTokenAllowsImmediateIssueWhenLatestTokenTargetsOldEmail() {

        User user = createUser("new@example.com");

        EmailVerificationToken oldEmailToken = new EmailVerificationToken();

        oldEmailToken.setUser(user);
        oldEmailToken.setTokenHash(
                sha256("old-email-token"));
        oldEmailToken.setEmail("old@example.com");
        oldEmailToken.setCreatedAt(
                LocalDateTime.now().minusSeconds(30));
        oldEmailToken.setExpiresAt(
                LocalDateTime.now().plusHours(24));

        when(userService.findById(user.getId()))
                .thenReturn(user);

        when(tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(
                        java.util.Optional.of(oldEmailToken));

        when(tokenRepository
                .findByUserIdAndUsedAtIsNull(user.getId()))
                .thenReturn(
                        List.of(oldEmailToken));

        EmailVerificationService service = new EmailVerificationService(
                tokenRepository,
                userService);

        String rawToken = service.issueToken(user.getId());

        assertNotNull(rawToken);

        assertNotNull(oldEmailToken.getUsedAt());

        ArgumentCaptor<EmailVerificationToken> captor = ArgumentCaptor.forClass(
                EmailVerificationToken.class);

        verify(tokenRepository)
                .save(captor.capture());

        EmailVerificationToken newToken = captor.getValue();

        assertEquals(
                "new@example.com",
                newToken.getEmail());

        assertEquals(
                user,
                newToken.getUser());

        assertNull(newToken.getUsedAt());

        assertEquals(
                sha256(rawToken),
                newToken.getTokenHash());
    }

    private String sha256(String value) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private EmailVerificationToken createToken(
            User user,
            String rawToken,
            String email,
            LocalDateTime expiresAt,
            LocalDateTime usedAt) {

        EmailVerificationToken token = new EmailVerificationToken();

        token.setUser(user);
        token.setTokenHash(sha256(rawToken));
        token.setEmail(email);
        token.setCreatedAt(LocalDateTime.now());
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);

        return token;
    }

}
