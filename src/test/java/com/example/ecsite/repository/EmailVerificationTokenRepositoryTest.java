package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.example.ecsite.entity.EmailVerificationToken;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmailVerificationTokenRepositoryTest {

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByTokenHashReturnsTargetToken() {

        User user = createUser("email-token-find-user");

        EmailVerificationToken token = createToken(
                user,
                "a".repeat(64),
                "find@example.com",
                null);

        entityManager.flush();
        entityManager.clear();

        EmailVerificationToken result = emailVerificationTokenRepository
                .findByTokenHash(token.getTokenHash())
                .orElseThrow();

        assertThat(result.getUser().getId())
                .isEqualTo(user.getId());
        assertThat(result.getTokenHash())
                .isEqualTo("a".repeat(64));
        assertThat(result.getEmail())
                .isEqualTo("find@example.com");
    }

    @Test
    void findByUserIdAndUsedAtIsNullReturnsOnlyUnusedTokens() {

        User targetUser = createUser("email-token-target-user");
        User otherUser = createUser("email-token-other-user");

        EmailVerificationToken unused1 = createToken(
                targetUser,
                "b".repeat(64),
                "target@example.com",
                null);

        EmailVerificationToken unused2 = createToken(
                targetUser,
                "c".repeat(64),
                "target@example.com",
                null);

        createToken(
                targetUser,
                "d".repeat(64),
                "target@example.com",
                LocalDateTime.now());

        createToken(
                otherUser,
                "e".repeat(64),
                "other@example.com",
                null);

        entityManager.flush();
        entityManager.clear();

        List<EmailVerificationToken> result = emailVerificationTokenRepository
                .findByUserIdAndUsedAtIsNull(
                        targetUser.getId());

        assertThat(result)
                .extracting(EmailVerificationToken::getId)
                .containsExactlyInAnyOrder(
                        unused1.getId(),
                        unused2.getId());
    }

    private EmailVerificationToken createToken(
            User user,
            String tokenHash,
            String email,
            LocalDateTime usedAt) {

        LocalDateTime now = LocalDateTime.now();

        EmailVerificationToken token = new EmailVerificationToken();

        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setEmail(email);
        token.setCreatedAt(now);
        token.setExpiresAt(now.plusHours(24));
        token.setUsedAt(usedAt);

        return emailVerificationTokenRepository.save(token);
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        User saved = userRepository.save(user);
        entityManager.flush();

        return saved;
    }

    @Test
    void findTopByUserIdOrderByCreatedAtDescReturnsLatestToken() {

        User targetUser = createUser("email-token-latest-user");
        User otherUser = createUser("email-token-latest-other-user");

        LocalDateTime baseTime = LocalDateTime.now();

        EmailVerificationToken olderToken = createToken(
                targetUser,
                "f".repeat(64),
                "latest@example.com",
                null);
        olderToken.setCreatedAt(baseTime.minusMinutes(10));

        EmailVerificationToken latestToken = createToken(
                targetUser,
                "1".repeat(64),
                "latest@example.com",
                null);
        latestToken.setCreatedAt(baseTime);

        EmailVerificationToken otherUserToken = createToken(
                otherUser,
                "2".repeat(64),
                "other-latest@example.com",
                null);
        otherUserToken.setCreatedAt(baseTime.plusMinutes(10));

        entityManager.flush();
        entityManager.clear();

        EmailVerificationToken result = emailVerificationTokenRepository
                .findTopByUserIdOrderByCreatedAtDesc(
                        targetUser.getId())
                .orElseThrow();

        assertThat(result.getId())
                .isEqualTo(latestToken.getId());

        assertThat(result.getId())
                .isNotEqualTo(olderToken.getId());

        assertThat(result.getUser().getId())
                .isEqualTo(targetUser.getId());
    }

}
