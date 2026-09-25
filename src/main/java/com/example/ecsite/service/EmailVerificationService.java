package com.example.ecsite.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.EmailVerificationToken;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.EmailAlreadyVerifiedException;
import com.example.ecsite.exception.EmailNotRegisteredException;
import com.example.ecsite.exception.EmailVerificationTooSoonException;
import com.example.ecsite.repository.EmailVerificationTokenRepository;

@Service
public class EmailVerificationService {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final int TOKEN_VALID_HOURS = 24;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserService userService;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            UserService userService) {
        this.tokenRepository = tokenRepository;
        this.userService = userService;
    }

    @Transactional
    public String issueToken(Long userId) {

        User user = userService.findById(userId);

        if (user.getEmail() == null) {
            throw new EmailNotRegisteredException();
        }

        if (user.getEmailVerifiedAt() != null) {
            throw new EmailAlreadyVerifiedException();
        }

        LocalDateTime now = LocalDateTime.now();

        tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(userId)
                .filter(token -> token.getEmail().equals(user.getEmail()))
                .filter(token -> token.getCreatedAt()
                        .plusSeconds(RESEND_COOLDOWN_SECONDS)
                        .isAfter(now))
                .ifPresent(token -> {
                    throw new EmailVerificationTooSoonException();
                });

        invalidateUnusedTokens(userId, now);

        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        EmailVerificationToken token = new EmailVerificationToken();

        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setEmail(user.getEmail());
        token.setCreatedAt(now);
        token.setExpiresAt(now.plusHours(TOKEN_VALID_HOURS));

        tokenRepository.save(token);

        return rawToken;
    }

    @Transactional
    public EmailVerificationResult verify(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            return EmailVerificationResult.INVALID;
        }

        String tokenHash = hashToken(rawToken);

        EmailVerificationToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElse(null);

        if (token == null) {
            return EmailVerificationResult.INVALID;
        }

        User user = token.getUser();

        if (token.getUsedAt() != null) {

            if (user.getEmailVerifiedAt() != null
                    && token.getEmail().equals(user.getEmail())) {
                return EmailVerificationResult.ALREADY_VERIFIED;
            }

            return EmailVerificationResult.USED;
        }

        LocalDateTime now = LocalDateTime.now();

        if (token.getExpiresAt().isBefore(now)) {
            return EmailVerificationResult.EXPIRED;
        }

        if (!token.getEmail().equals(user.getEmail())) {
            return EmailVerificationResult.EMAIL_CHANGED;
        }

        if (user.getEmailVerifiedAt() != null) {
            token.setUsedAt(now);
            return EmailVerificationResult.ALREADY_VERIFIED;
        }

        user.setEmailVerifiedAt(now);
        token.setUsedAt(now);

        return EmailVerificationResult.VERIFIED;
    }

    private void invalidateUnusedTokens(
            Long userId,
            LocalDateTime usedAt) {

        List<EmailVerificationToken> unusedTokens = tokenRepository.findByUserIdAndUsedAtIsNull(userId);

        for (EmailVerificationToken token : unusedTokens) {
            token.setUsedAt(usedAt);
        }
    }

    private String generateRawToken() {

        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String hashToken(String rawToken) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    rawToken.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256を利用できません。", e);
        }
    }
}
