package com.makemymarriage.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, columnDefinition = "bytea")
  private byte[] tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected EmailVerificationToken() {}

  public EmailVerificationToken(UUID userId, byte[] tokenHash, Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash.clone();
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public boolean isUsableAt(Instant now) {
    return consumedAt == null && revokedAt == null && now.isBefore(expiresAt);
  }

  public boolean isActive() {
    return consumedAt == null && revokedAt == null;
  }

  public void revoke(Instant now) {
    if (!isActive()) {
      throw new IllegalStateException("Only an active verification token can be revoked");
    }
    revokedAt = now;
  }

  public void consume(Instant now) {
    if (!isUsableAt(now)) {
      throw new IllegalStateException("Verification token is not usable");
    }
    consumedAt = now;
  }
}
