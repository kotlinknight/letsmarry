package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordResetTokenSecretsTests {
  private final PasswordResetTokenSecrets secrets = new PasswordResetTokenSecrets();

  @Test
  void generatedSecretsAreDistinctCanonicalRandomValuesWithSha256Digests() throws Exception {
    String first = secrets.generate();
    String second = secrets.generate();

    assertThat(first).hasSize(43).isNotEqualTo(second);
    assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
    assertThat(secrets.hashGeneratedToken(first))
        .containsExactly(
            MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(first)));
    assertThat(new IssuedPasswordResetToken(UUID.randomUUID(), first, null, null).toString())
        .doesNotContain(first);
  }

  @Test
  void malformedAndNoncanonicalPresentationIsRejected() {
    assertThat(secrets.hashPresentedToken(null)).isEmpty();
    assertThat(secrets.hashPresentedToken("invalid")).isEmpty();
    assertThat(secrets.hashPresentedToken("A".repeat(42) + "B")).isEmpty();
    assertThat(secrets.hashPresentedToken(secrets.generate() + "=")).isEmpty();
  }
}
