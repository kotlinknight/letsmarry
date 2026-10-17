package com.makemymarriage.identity.application;

import java.time.Instant;
import java.util.UUID;

record IssuedPasswordResetToken(UUID id, String secret, Instant expiresAt, UUID supersededId) {
  @Override
  public String toString() {
    return "IssuedPasswordResetToken[id=" + id + ", expiresAt=" + expiresAt + "]";
  }
}
