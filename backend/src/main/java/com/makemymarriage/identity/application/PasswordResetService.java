package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.PasswordResetSessionCleanup;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import com.makemymarriage.notification.EmailDraft;
import com.makemymarriage.notification.EmailOutbox;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Profile("(local | test) & !production")
@ConditionalOnProperty(
    name = {"app.password-recovery.enabled", "app.email-outbox.enabled"},
    havingValue = "true")
public class PasswordResetService {
  private final PasswordResetTokenService tokens;
  private final UserAccountRepository users;
  private final PasswordEncoder encoder;
  private final EmailOutbox outbox;
  private final PasswordResetSessionCleanup cleanup;
  private final Clock clock;

  PasswordResetService(
      PasswordResetTokenService tokens,
      UserAccountRepository users,
      PasswordEncoder encoder,
      EmailOutbox outbox,
      PasswordResetSessionCleanup cleanup,
      Clock clock) {
    this.tokens = tokens;
    this.users = users;
    this.encoder = encoder;
    this.outbox = outbox;
    this.cleanup = cleanup;
    this.clock = clock;
  }

  @Transactional
  public void reset(String presentedToken, String newPassword) {
    String hash = encoder.encode(PasswordPolicy.normalizeAndValidate(newPassword, "newPassword"));
    var consumed = tokens.consumeForPasswordChange(presentedToken);
    UserAccount user =
        users.lockById(consumed.userId()).orElseThrow(InvalidPasswordResetTokenException::new);
    user.changePasswordHash(hash);
    users.saveAndFlush(user);
    outbox.cancelPending(consumed.tokenId());
    outbox.enqueue(
        new EmailDraft(
            user.getEmail(),
            "PASSWORD_CHANGED",
            "{}",
            clock.instant().plus(Duration.ofDays(1)),
            user.getId()));
    long newAuthVersion = user.getAuthVersion();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            cleanup.removeSessions(user.getId(), newAuthVersion);
          }
        });
  }
}
