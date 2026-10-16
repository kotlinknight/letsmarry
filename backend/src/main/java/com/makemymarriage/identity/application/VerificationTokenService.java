package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.EmailVerificationToken;
import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.EmailVerificationTokenRepository;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationTokenService {
  static final Duration LIFETIME = Duration.ofHours(24);

  private final UserAccountRepository users;
  private final EmailVerificationTokenRepository tokens;
  private final VerificationTokenSecrets secrets;
  private final Clock clock;

  VerificationTokenService(
      UserAccountRepository users,
      EmailVerificationTokenRepository tokens,
      VerificationTokenSecrets secrets,
      Clock clock) {
    this.users = users;
    this.tokens = tokens;
    this.secrets = secrets;
    this.clock = clock;
  }

  @Transactional
  IssuedVerificationToken issueForUser(UUID userId) {
    Instant now = clock.instant();
    UserAccount user = users.lockById(userId).orElseThrow();
    if (!user.canVerifyEmail()) {
      throw new IllegalStateException("Account cannot receive a verification token");
    }

    EmailVerificationToken previous =
        tokens.findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(userId).orElse(null);
    if (previous != null) {
      previous.revoke(now);
      tokens.flush();
    }

    String secret = secrets.generate();
    EmailVerificationToken token =
        tokens.saveAndFlush(
            new EmailVerificationToken(
                userId, secrets.hashGeneratedToken(secret), now.plus(LIFETIME)));
    return new IssuedVerificationToken(
        token.getId(), secret, token.getExpiresAt(), previous == null ? null : previous.getId());
  }

  @Transactional
  public UUID verify(String presentedToken) {
    byte[] hash =
        secrets
            .hashPresentedToken(presentedToken)
            .orElseThrow(InvalidVerificationTokenException::new);
    UUID userId =
        tokens.findUserIdByTokenHash(hash).orElseThrow(InvalidVerificationTokenException::new);
    UserAccount user = users.lockById(userId).orElseThrow(InvalidVerificationTokenException::new);
    EmailVerificationToken token =
        tokens.lockByTokenHash(hash).orElseThrow(InvalidVerificationTokenException::new);
    Instant now = clock.instant();
    if (!token.getUserId().equals(userId) || !token.isUsableAt(now) || !user.canVerifyEmail()) {
      throw new InvalidVerificationTokenException();
    }
    token.consume(now);
    user.verifyEmail(now);
    return token.getId();
  }
}
