package com.makemymarriage.identity.application;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import com.makemymarriage.notification.EmailDraft;
import com.makemymarriage.notification.EmailOutbox;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class EmailVerificationService {
  private final UserAccountRepository users;
  private final VerificationTokenService tokens;
  private final ObjectProvider<EmailOutbox> outbox;
  private final ObjectMapper mapper;
  private final String verificationUrl;

  EmailVerificationService(
      UserAccountRepository users,
      VerificationTokenService tokens,
      ObjectProvider<EmailOutbox> outbox,
      ObjectMapper mapper,
      @Value("${app.verification.frontend-url:http://127.0.0.1:4200/verify-email}")
          String verificationUrl) {
    this.users = users;
    this.tokens = tokens;
    this.outbox = outbox;
    this.mapper = mapper;
    URI url = URI.create(verificationUrl);
    if (url.getFragment() != null
        || !("https".equals(url.getScheme())
            || ("http".equals(url.getScheme())
                && ("127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost()))))) {
      throw new IllegalArgumentException("Invalid verification frontend URL");
    }
    this.verificationUrl = verificationUrl;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void issueForUser(UserAccount user) {
    IssuedVerificationToken issued = tokens.issueForUser(user.getId());
    EmailOutbox emails = requiredOutbox();
    if (issued.supersededId() != null) {
      emails.cancelPending(issued.supersededId());
    }
    String payload =
        mapper.writeValueAsString(
            Map.of("verificationUrl", verificationUrl + "#token=" + issued.secret()));
    emails.enqueue(
        new EmailDraft(user.getEmail(), "VERIFY_EMAIL", payload, issued.expiresAt(), issued.id()));
  }

  @Transactional
  public void requestResend(String presentedEmail) {
    String email = EmailAddressPolicy.normalize(presentedEmail);
    users.lockByEmail(email).filter(UserAccount::canVerifyEmail).ifPresent(this::issueForUser);
  }

  @Transactional
  public void verify(String presentedToken) {
    var tokenId = tokens.verify(presentedToken);
    requiredOutbox().cancelPending(tokenId);
  }

  private EmailOutbox requiredOutbox() {
    EmailOutbox emails = outbox.getIfAvailable();
    if (emails == null) {
      throw new IllegalStateException("Email outbox is required for verification");
    }
    return emails;
  }
}
