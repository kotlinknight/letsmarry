package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTests {
  @Test
  void acceptsSpacesAndUnicodeWithoutCompositionRules() {
    assertThat(PasswordPolicy.normalizeAndValidate(" a long café phrase "))
        .isEqualTo(" a long café phrase ");
    assertThat(PasswordPolicy.normalizeAndValidate(" a long cafe\u0301 phrase "))
        .isEqualTo(" a long café phrase ");
  }

  @Test
  void enforcesUnicodeCodePointBoundsAndRejectsControlCharacters() {
    assertThatThrownBy(() -> PasswordPolicy.normalizeAndValidate("x".repeat(14)))
        .isInstanceOf(InvalidRegistrationException.class);
    assertThat(PasswordPolicy.normalizeAndValidate("x".repeat(15))).hasSize(15);
    assertThat(PasswordPolicy.normalizeAndValidate("🙂".repeat(128))).hasSize(256);
    assertThatThrownBy(() -> PasswordPolicy.normalizeAndValidate("x".repeat(129)))
        .isInstanceOf(InvalidRegistrationException.class);
    assertThatThrownBy(() -> PasswordPolicy.normalizeAndValidate("long password\ntext"))
        .isInstanceOf(InvalidRegistrationException.class);
  }
}
