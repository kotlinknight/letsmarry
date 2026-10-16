package com.makemymarriage.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "email_outbox")
public class EmailOutboxEntry {
  @Id private UUID id;

  @Column(name = "purpose_id")
  private UUID purposeId;

  @Column(name = "recipient_email", nullable = false, length = 320)
  private String recipientEmail;

  @Column(name = "template_code", nullable = false, length = 100)
  private String templateCode;

  @Column(name = "encrypted_payload", columnDefinition = "bytea")
  private byte[] encryptedPayload;

  @Column(name = "nonce", columnDefinition = "bytea")
  private byte[] nonce;

  @Column(name = "key_version", length = 32)
  private String keyVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OutboxStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "claim_id")
  private UUID claimId;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "provider_message_id", length = 255)
  private String providerMessageId;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  protected EmailOutboxEntry() {}

  public EmailOutboxEntry(
      UUID id,
      String recipientEmail,
      String templateCode,
      byte[] encryptedPayload,
      byte[] nonce,
      String keyVersion,
      Instant expiresAt,
      Instant nextAttemptAt,
      UUID purposeId) {
    this.id = id;
    this.purposeId = purposeId;
    this.recipientEmail = recipientEmail;
    this.templateCode = templateCode;
    this.encryptedPayload = encryptedPayload.clone();
    this.nonce = nonce.clone();
    this.keyVersion = keyVersion;
    this.status = OutboxStatus.PENDING;
    this.expiresAt = expiresAt;
    this.nextAttemptAt = nextAttemptAt;
  }

  public UUID getId() {
    return id;
  }
}
