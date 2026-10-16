package com.makemymarriage.identity.application;

import com.makemymarriage.identity.infrastructure.EmailVerificationTokenRepository;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import com.makemymarriage.notification.EmailDeliveryEligibility;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class VerificationEmailEligibility implements EmailDeliveryEligibility {
  private final EmailVerificationTokenRepository tokens;
  private final UserAccountRepository users;
  private final Clock clock;

  VerificationEmailEligibility(
      EmailVerificationTokenRepository tokens, UserAccountRepository users, Clock clock) {
    this.tokens = tokens;
    this.users = users;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean maySend(String templateCode, UUID purposeId) {
    if (!"VERIFY_EMAIL".equals(templateCode) || purposeId == null) {
      return false;
    }
    return tokens
        .findById(purposeId)
        .filter(token -> token.isUsableAt(clock.instant()))
        .flatMap(token -> users.findById(token.getUserId()))
        .map(user -> user.canVerifyEmail())
        .orElse(false);
  }
}
