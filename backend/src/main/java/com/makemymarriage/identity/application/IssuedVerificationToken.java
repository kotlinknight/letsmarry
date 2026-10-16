package com.makemymarriage.identity.application;

import java.time.Instant;
import java.util.UUID;

record IssuedVerificationToken(UUID id, String secret, Instant expiresAt, UUID supersededId) {
  @Override
  public String toString() {
    return "IssuedVerificationToken[id=" + id + ", expiresAt=" + expiresAt + "]";
  }
}
