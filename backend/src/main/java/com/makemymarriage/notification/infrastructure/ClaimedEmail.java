package com.makemymarriage.notification.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record ClaimedEmail(
    UUID id,
    UUID claimId,
    UUID purposeId,
    String recipientEmail,
    String templateCode,
    byte[] encryptedPayload,
    byte[] nonce,
    String keyVersion,
    Instant expiresAt,
    int attemptCount) {
  public ClaimedEmail(
      UUID id,
      UUID claimId,
      String recipientEmail,
      String templateCode,
      byte[] encryptedPayload,
      byte[] nonce,
      String keyVersion,
      Instant expiresAt,
      int attemptCount) {
    this(
        id,
        claimId,
        null,
        recipientEmail,
        templateCode,
        encryptedPayload,
        nonce,
        keyVersion,
        expiresAt,
        attemptCount);
  }

  @Override
  public String toString() {
    return "ClaimedEmail[id=" + id + ", attemptCount=" + attemptCount + "]";
  }
}
