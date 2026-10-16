package com.makemymarriage.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "users")
public class UserAccount {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(nullable = false, length = 320)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "display_name", nullable = false, length = 150)
  private String displayName;

  @Column(length = 30)
  private String phone;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private UserStatus status;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected UserAccount() {}

  public UserAccount(String email, String passwordHash, String displayName) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.displayName = displayName;
    this.status = UserStatus.ACTIVE;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public UserStatus getStatus() {
    return status;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean canAuthenticate() {
    return status == UserStatus.ACTIVE && emailVerifiedAt != null;
  }

  public Instant getEmailVerifiedAt() {
    return emailVerifiedAt;
  }

  public boolean canVerifyEmail() {
    return status == UserStatus.ACTIVE && emailVerifiedAt == null;
  }

  public void verifyEmail(Instant verifiedAt) {
    if (!canVerifyEmail()) {
      throw new IllegalStateException("Account cannot verify email");
    }
    emailVerifiedAt = verifiedAt;
  }
}
