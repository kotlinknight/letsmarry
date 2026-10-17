package com.makemymarriage.identity.application;

import java.text.Normalizer;

final class PasswordPolicy {
  private PasswordPolicy() {}

  static String normalizeAndValidate(String password) {
    return normalizeAndValidate(password, "password");
  }

  static String normalizeAndValidate(String password, String field) {
    if (password == null) {
      throw new InvalidRegistrationException(field, "A password is required.");
    }

    String normalized = normalizeForAuthentication(password);
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 15 || length > 128 || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new InvalidRegistrationException(
          field, "Use 15 to 128 characters without control characters.");
    }
    return normalized;
  }

  static String normalizeForAuthentication(String password) {
    return Normalizer.normalize(password, Normalizer.Form.NFC);
  }
}
