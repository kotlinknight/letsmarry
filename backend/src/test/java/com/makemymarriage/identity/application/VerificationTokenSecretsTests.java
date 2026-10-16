package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class VerificationTokenSecretsTests {
  private final VerificationTokenSecrets secrets = new VerificationTokenSecrets();

  @Test
  void generatesDistinctUrlSafe256BitSecretsAndHashesDecodedBytes() throws Exception {
    String first = secrets.generate();
    String second = secrets.generate();

    assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second);
    byte[] decoded = Base64.getUrlDecoder().decode(first);
    assertThat(decoded).hasSize(32);
    assertThat(secrets.hashPresentedToken(first)).isPresent();
    assertThat(secrets.hashPresentedToken(first).orElseThrow())
        .containsExactly(MessageDigest.getInstance("SHA-256").digest(decoded));
  }

  @Test
  void rejectsMalformedAndNonCanonicalSecrets() {
    assertThat(secrets.hashPresentedToken(null)).isEmpty();
    assertThat(secrets.hashPresentedToken("short")).isEmpty();
    assertThat(secrets.hashPresentedToken("!".repeat(43))).isEmpty();

    String canonical = secrets.generate();
    String nonCanonical = canonical.substring(0, 42) + changedLastBase64Character(canonical);
    assertThat(secrets.hashPresentedToken(nonCanonical)).isEmpty();
  }

  private char changedLastBase64Character(String canonical) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    int index = alphabet.indexOf(canonical.charAt(42));
    return alphabet.charAt(index ^ 1);
  }
}
