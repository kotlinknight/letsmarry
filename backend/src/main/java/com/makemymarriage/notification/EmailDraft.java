package com.makemymarriage.notification;

import java.time.Instant;
import java.util.UUID;

public record EmailDraft(
    String recipientEmail,
    String templateCode,
    String payloadJson,
    Instant expiresAt,
    UUID purposeId) {
  public EmailDraft(
      String recipientEmail, String templateCode, String payloadJson, Instant expiresAt) {
    this(recipientEmail, templateCode, payloadJson, expiresAt, null);
  }

  @Override
  public String toString() {
    return "EmailDraft[templateCode=" + templateCode + "]";
  }
}
