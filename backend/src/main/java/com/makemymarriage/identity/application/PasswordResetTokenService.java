package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.PasswordResetToken;
import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.PasswordResetTokenRepository;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetTokenService {
  static final Duration LIFETIME = Duration.ofMinutes(30);

  private final UserAccountRepository users;
  private final PasswordResetTokenRepository tokens;
  private final PasswordResetTokenSecrets secrets;
  private final Clock clock;

  PasswordResetTokenService(
      UserAccountRepository users,
      PasswordResetTokenRepository tokens,
      PasswordResetTokenSecrets secrets,
      Clock clock) {
    this.users = users;
    this.tokens = tokens;
    this.secrets = secrets;
    this.clock = clock;
  }

  @Transactional
  IssuedPasswordResetToken issueForUser(UUID userId) {
    Instant now = clock.instant();
    UserAccount user = users.lockById(userId).orElseThrow();
    if (!user.canRecoverPassword()) {
      throw new IllegalStateException("Account cannot receive a password reset token");
    }

    PasswordResetToken previous =
        tokens.findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(userId).orElse(null);
    if (previous != null) {
      previous.revoke(now);
      tokens.flush();
    }

    String secret = secrets.generate();
    PasswordResetToken token =
        tokens.saveAndFlush(
            new PasswordResetToken(userId, secrets.hashGeneratedToken(secret), now.plus(LIFETIME)));
    return new IssuedPasswordResetToken(
        token.getId(), secret, token.getExpiresAt(), previous == null ? null : previous.getId());
  }

  @Transactional
  Optional<UUID> revokeActiveForUser(UUID userId) {
    users.lockById(userId).orElseThrow();
    PasswordResetToken active =
        tokens.findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(userId).orElse(null);
    if (active == null) {
      return Optional.empty();
    }
    active.revoke(clock.instant());
    tokens.flush();
    return Optional.of(active.getId());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  UUID consume(String presentedToken) {
    return consumeForPasswordChange(presentedToken).userId();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  ConsumedResetToken consumeForPasswordChange(String presentedToken) {
    byte[] hash =
        secrets
            .hashPresentedToken(presentedToken)
            .orElseThrow(InvalidPasswordResetTokenException::new);
    UUID userId =
        tokens.findUserIdByTokenHash(hash).orElseThrow(InvalidPasswordResetTokenException::new);
    UserAccount user = users.lockById(userId).orElseThrow(InvalidPasswordResetTokenException::new);
    PasswordResetToken token =
        tokens.lockByTokenHash(hash).orElseThrow(InvalidPasswordResetTokenException::new);
    Instant now = clock.instant();
    if (!token.getUserId().equals(userId) || !token.isUsableAt(now) || !user.canRecoverPassword()) {
      throw new InvalidPasswordResetTokenException();
    }
    token.consume(now);
    return new ConsumedResetToken(userId, token.getId());
  }

  record ConsumedResetToken(UUID userId, UUID tokenId) {}
}
