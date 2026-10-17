package com.makemymarriage.identity.application;

import com.makemymarriage.identity.infrastructure.EmailVerificationTokenRepository;
import com.makemymarriage.identity.infrastructure.PasswordResetTokenRepository;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import com.makemymarriage.notification.EmailDeliveryEligibility;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityEmailEligibility implements EmailDeliveryEligibility {
  private final EmailVerificationTokenRepository verificationTokens;
  private final PasswordResetTokenRepository resetTokens;
  private final UserAccountRepository users;
  private final Clock clock;

  IdentityEmailEligibility(
      EmailVerificationTokenRepository verificationTokens,
      PasswordResetTokenRepository resetTokens,
      UserAccountRepository users,
      Clock clock) {
    this.verificationTokens = verificationTokens;
    this.resetTokens = resetTokens;
    this.users = users;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean maySend(String templateCode, UUID purposeId) {
    if (purposeId == null) {
      return false;
    }
    if ("PASSWORD_CHANGED".equals(templateCode)) {
      return users.existsById(purposeId);
    }
    if ("VERIFY_EMAIL".equals(templateCode)) {
      return verificationTokens
          .findById(purposeId)
          .filter(token -> token.isUsableAt(clock.instant()))
          .flatMap(token -> users.findById(token.getUserId()))
          .map(user -> user.canVerifyEmail())
          .orElse(false);
    }
    if ("PASSWORD_RESET".equals(templateCode)) {
      return resetTokens
          .findById(purposeId)
          .filter(token -> token.isUsableAt(clock.instant()))
          .flatMap(token -> users.findById(token.getUserId()))
          .map(user -> user.canRecoverPassword())
          .orElse(false);
    }
    return false;
  }
}
