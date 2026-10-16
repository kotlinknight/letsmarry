package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.makemymarriage.identity.domain.EmailVerificationToken;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailVerificationTokenTests {
  private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

  @Test
  void expiryIsExclusiveAndConsumptionIsSingleUse() {
    EmailVerificationToken token =
        new EmailVerificationToken(UUID.randomUUID(), new byte[32], NOW.plusSeconds(60));

    assertThat(token.isUsableAt(NOW.plusSeconds(59))).isTrue();
    assertThat(token.isUsableAt(NOW.plusSeconds(60))).isFalse();
    token.consume(NOW.plusSeconds(59));
    assertThat(token.isUsableAt(NOW.plusSeconds(59))).isFalse();
    assertThatThrownBy(() -> token.consume(NOW.plusSeconds(59)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void revocationPermanentlyPreventsConsumption() {
    EmailVerificationToken token =
        new EmailVerificationToken(UUID.randomUUID(), new byte[32], NOW.plusSeconds(60));

    token.revoke(NOW);
    assertThat(token.isUsableAt(NOW)).isFalse();
    assertThatThrownBy(() -> token.consume(NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> token.revoke(NOW)).isInstanceOf(IllegalStateException.class);
  }
}
