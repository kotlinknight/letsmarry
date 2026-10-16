package com.makemymarriage.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailPayloadCipherTests {
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final Instant EXPIRY = Instant.parse("2026-10-14T12:00:00Z");

  @Test
  void encryptsWithUniqueNoncesAndAuthenticatesMetadataAndCiphertext() {
    EmailPayloadCipher cipher = new EmailPayloadCipher("v1", "v1:" + KEY);
    UUID id = UUID.randomUUID();
    String payload = "{\"verificationToken\":\"sensitive-example\"}";

    var first = cipher.encrypt(id, "user@example.com", "VERIFY_EMAIL", EXPIRY, payload);
    var second = cipher.encrypt(id, "user@example.com", "VERIFY_EMAIL", EXPIRY, payload);

    assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
    assertThat(new String(first.ciphertext())).doesNotContain("sensitive-example");
    assertThat(
            cipher.decrypt(
                id,
                "user@example.com",
                "VERIFY_EMAIL",
                EXPIRY,
                first.ciphertext(),
                first.nonce(),
                first.keyVersion()))
        .isEqualTo(payload);
    assertThatThrownBy(
            () ->
                cipher.decrypt(
                    id,
                    "other@example.com",
                    "VERIFY_EMAIL",
                    EXPIRY,
                    first.ciphertext(),
                    first.nonce(),
                    first.keyVersion()))
        .isInstanceOf(EmailPayloadCipher.PayloadAuthenticationException.class);
    byte[] tampered = first.ciphertext().clone();
    tampered[0] ^= 1;
    assertThatThrownBy(
            () ->
                cipher.decrypt(
                    id,
                    "user@example.com",
                    "VERIFY_EMAIL",
                    EXPIRY,
                    tampered,
                    first.nonce(),
                    first.keyVersion()))
        .isInstanceOf(EmailPayloadCipher.PayloadAuthenticationException.class);
    assertThat(first.toString()).doesNotContain(payload);
  }

  @Test
  void requiresActiveKeyAndRetainsOlderKeyForRotation() {
    assertThatThrownBy(() -> new EmailPayloadCipher("v1", ""))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new EmailPayloadCipher("v1", "v1:not-base64"))
        .isInstanceOf(IllegalStateException.class);

    UUID id = UUID.randomUUID();
    EmailPayloadCipher old = new EmailPayloadCipher("v1", "v1:" + KEY);
    var encrypted = old.encrypt(id, "user@example.com", "VERIFY_EMAIL", EXPIRY, "{}");
    EmailPayloadCipher rotated =
        new EmailPayloadCipher(
            "v2", "v1:" + KEY + ",v2:" + Base64.getEncoder().encodeToString(new byte[32]));
    assertThat(
            rotated.decrypt(
                id,
                "user@example.com",
                "VERIFY_EMAIL",
                EXPIRY,
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.keyVersion()))
        .isEqualTo("{}");
    assertThatThrownBy(
            () ->
                new EmailPayloadCipher("v2", "v2:" + KEY)
                    .decrypt(
                        id,
                        "user@example.com",
                        "VERIFY_EMAIL",
                        EXPIRY,
                        encrypted.ciphertext(),
                        encrypted.nonce(),
                        encrypted.keyVersion()))
        .isInstanceOf(EmailPayloadCipher.KeyUnavailableException.class);
  }
}
