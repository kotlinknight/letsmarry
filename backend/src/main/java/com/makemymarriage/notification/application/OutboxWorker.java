package com.makemymarriage.notification.application;

import com.makemymarriage.notification.EmailDelivery;
import com.makemymarriage.notification.EmailDeliveryEligibility;
import com.makemymarriage.notification.EmailSendException;
import com.makemymarriage.notification.EmailSender;
import com.makemymarriage.notification.infrastructure.ClaimedEmail;
import com.makemymarriage.notification.infrastructure.EmailOutboxClaims;
import com.makemymarriage.notification.infrastructure.EmailPayloadCipher;
import com.makemymarriage.notification.infrastructure.EmailPayloadCipher.KeyUnavailableException;
import com.makemymarriage.notification.infrastructure.EmailPayloadCipher.PayloadAuthenticationException;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxWorker {
  private final EmailOutboxClaims claims;
  private final EmailPayloadCipher cipher;
  private final EmailSender sender;
  private final EmailDeliveryEligibility eligibility;
  private final Clock clock;

  public OutboxWorker(
      EmailOutboxClaims claims,
      EmailPayloadCipher cipher,
      EmailSender sender,
      EmailDeliveryEligibility eligibility,
      Clock clock) {
    this.claims = claims;
    this.cipher = cipher;
    this.sender = sender;
    this.eligibility = eligibility;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${app.email-outbox.worker.poll-delay-ms:5000}",
      initialDelayString = "${app.email-outbox.worker.initial-delay-ms:5000}")
  public void runOnce() {
    for (ClaimedEmail claim : claims.claim(1)) {
      deliver(claim);
    }
  }

  private void deliver(ClaimedEmail claim) {
    if (!clock.instant().isBefore(claim.expiresAt())) {
      claims.expire(claim);
      return;
    }

    try {
      if (!eligibility.maySend(claim.templateCode(), claim.purposeId())) {
        claims.expire(claim);
        return;
      }
    } catch (RuntimeException exception) {
      retryOrFail(claim, "ELIGIBILITY_UNAVAILABLE");
      return;
    }

    String payload;
    try {
      payload =
          cipher.decrypt(
              claim.id(),
              claim.recipientEmail(),
              claim.templateCode(),
              claim.expiresAt(),
              claim.encryptedPayload(),
              claim.nonce(),
              claim.keyVersion());
    } catch (KeyUnavailableException exception) {
      claims.deferForMissingKey(claim);
      return;
    } catch (PayloadAuthenticationException exception) {
      claims.fail(claim, "PAYLOAD_AUTH_FAILED");
      return;
    }

    try {
      if (!eligibility.maySend(claim.templateCode(), claim.purposeId())) {
        claims.expire(claim);
        return;
      }
      EmailSender.SendReceipt receipt =
          sender.send(
              new EmailDelivery(claim.id(), claim.recipientEmail(), claim.templateCode(), payload));
      if (receipt == null) {
        retryOrFail(claim, "DELIVERY_ERROR");
      } else {
        claims.markSent(claim, receipt.providerMessageId());
      }
    } catch (EmailSendException exception) {
      if (exception.isPermanent()) {
        claims.fail(claim, "SENDER_PERMANENT");
      } else {
        retryOrFail(claim, "SENDER_TEMPORARY");
      }
    } catch (RuntimeException exception) {
      retryOrFail(claim, "DELIVERY_ERROR");
    }
  }

  private void retryOrFail(ClaimedEmail claim, String safeCode) {
    if (claim.attemptCount() >= EmailOutboxClaims.maximumAttempts()) {
      claims.fail(claim, "ATTEMPTS_EXHAUSTED");
    } else {
      int delaySeconds = 60 << (claim.attemptCount() - 1);
      claims.retry(claim, delaySeconds, safeCode);
    }
  }
}
