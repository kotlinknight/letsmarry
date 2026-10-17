package com.makemymarriage.identity.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class PasswordResetTokenSecrets {
  private static final int TOKEN_BYTES = 32;
  private static final int TOKEN_LENGTH = 43;

  private final SecureRandom random = new SecureRandom();

  String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  Optional<byte[]> hashPresentedToken(String token) {
    if (token == null || token.length() != TOKEN_LENGTH) {
      return Optional.empty();
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(token);
      if (decoded.length != TOKEN_BYTES
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
        return Optional.empty();
      }
      return Optional.of(hashBytes(decoded));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  byte[] hashGeneratedToken(String token) {
    return hashPresentedToken(token).orElseThrow();
  }

  private byte[] hashBytes(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
