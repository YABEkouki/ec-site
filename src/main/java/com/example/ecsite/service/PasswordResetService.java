package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.PasswordResetToken;
import com.example.ecsite.entity.User;
import com.example.ecsite.repository.PasswordResetTokenRepository;

@Service
public class PasswordResetService {

    private static final int TOKEN_VALID_HOURS = 1;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserService userService;
    private final SecureTokenService secureTokenService;

    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserService userService,
            SecureTokenService secureTokenService) {

        this.tokenRepository = tokenRepository;
        this.userService = userService;
        this.secureTokenService = secureTokenService;
    }

    @Transactional
    public String issueToken(String email) {

        User user = userService.findByEmail(email);

        if (user == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        PasswordResetToken latestToken = tokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElse(null);

        if (latestToken != null
                && latestToken.getCreatedAt()
                        .plusSeconds(RESEND_COOLDOWN_SECONDS)
                        .isAfter(now)) {
            return null;
        }

        invalidateUnusedTokens(user.getId(), now);

        String rawToken = secureTokenService.generateRawToken();
        String tokenHash = secureTokenService.hashToken(rawToken);

        PasswordResetToken token = new PasswordResetToken();

        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setEmail(user.getEmail());
        token.setCreatedAt(now);
        token.setExpiresAt(
                now.plusHours(TOKEN_VALID_HOURS));

        tokenRepository.save(token);

        return rawToken;
    }

    @Transactional(readOnly = true)
    public PasswordResetResult validateToken(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            return PasswordResetResult.INVALID;
        }

        String tokenHash = secureTokenService.hashToken(rawToken);

        PasswordResetToken token = tokenRepository
                .findByTokenHash(tokenHash)
                .orElse(null);

        if (token == null) {
            return PasswordResetResult.INVALID;
        }

        if (token.getUsedAt() != null) {
            return PasswordResetResult.USED;
        }

        LocalDateTime now = LocalDateTime.now();

        if (token.getExpiresAt().isBefore(now)) {
            return PasswordResetResult.EXPIRED;
        }

        User user = token.getUser();

        if (user.getEmail() == null
                || !user.getEmail().equals(token.getEmail())) {
            return PasswordResetResult.EMAIL_CHANGED;
        }

        return PasswordResetResult.VALID;
    }

    @Transactional
    public PasswordResetResult resetPassword(
            String rawToken,
            String newPassword) {

        PasswordResetResult validationResult = validateToken(rawToken);

        if (validationResult != PasswordResetResult.VALID) {
            return validationResult;
        }

        String tokenHash = secureTokenService.hashToken(rawToken);

        PasswordResetToken token = tokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow();

        Long userId = token.getUser().getId();

        userService.resetPassword(
                userId,
                newPassword);

        LocalDateTime now = LocalDateTime.now();

        invalidateUnusedTokens(userId, now);

        return PasswordResetResult.VALID;
    }

    @Transactional
    public void invalidateToken(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        String tokenHash = secureTokenService.hashToken(rawToken);

        tokenRepository.findByTokenHash(tokenHash)
                .filter(token -> token.getUsedAt() == null)
                .ifPresent(token -> token.setUsedAt(LocalDateTime.now()));
    }

    private void invalidateUnusedTokens(
            Long userId,
            LocalDateTime usedAt) {

        List<PasswordResetToken> tokens = tokenRepository.findByUserIdAndUsedAtIsNull(userId);

        for (PasswordResetToken token : tokens) {
            token.setUsedAt(usedAt);
        }
    }
}
