package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import com.makemymarriage.notification.EmailDraft;
import com.makemymarriage.notification.EmailOutbox;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("(local | test) & !production")
@ConditionalOnProperty(
    name = {"app.password-recovery.enabled", "app.email-outbox.enabled"},
    havingValue = "true")
public class PasswordRecoveryEmailService {
  private final UserAccountRepository users;
  private final PasswordResetTokenService tokens;
  private final EmailOutbox outbox;
  private final ObjectMapper mapper;
  private final String frontendUrl;

  PasswordRecoveryEmailService(
      UserAccountRepository users,
      PasswordResetTokenService tokens,
      EmailOutbox outbox,
      ObjectMapper mapper,
      @Value("${app.password-recovery.frontend-url:http://127.0.0.1:4200/reset-password}")
          String frontendUrl) {
    URI url = URI.create(frontendUrl);
    if (url.getFragment() != null
        || url.getUserInfo() != null
        || url.getHost() == null
        || !("https".equals(url.getScheme())
            || ("http".equals(url.getScheme())
                && ("127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost()))))) {
      throw new IllegalArgumentException("Invalid password recovery frontend URL");
    }
    this.users = users;
    this.tokens = tokens;
    this.outbox = outbox;
    this.mapper = mapper;
    this.frontendUrl = frontendUrl;
  }

  @Transactional
  public void request(String presentedEmail) {
    String normalized = EmailAddressPolicy.normalize(presentedEmail);
    users.lockByEmail(normalized).filter(UserAccount::canRecoverPassword).ifPresent(this::enqueue);
  }

  private void enqueue(UserAccount user) {
    IssuedPasswordResetToken issued = tokens.issueForUser(user.getId());
    if (issued.supersededId() != null) {
      outbox.cancelPending(issued.supersededId());
    }
    String payload =
        mapper.writeValueAsString(Map.of("resetUrl", frontendUrl + "#token=" + issued.secret()));
    outbox.enqueue(
        new EmailDraft(
            user.getEmail(), "PASSWORD_RESET", payload, issued.expiresAt(), issued.id()));
  }
}
