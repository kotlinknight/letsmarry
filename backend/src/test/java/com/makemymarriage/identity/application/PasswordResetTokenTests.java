package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.makemymarriage.identity.domain.PasswordResetToken;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordResetTokenTests {
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

  @Test
  void expiryIsExclusiveAndTokenCanBeConsumedOnlyOnce() {
    PasswordResetToken token =
        new PasswordResetToken(UUID.randomUUID(), new byte[32], NOW.plusSeconds(1800));

    assertThat(token.isUsableAt(NOW.plusSeconds(1799))).isTrue();
    assertThat(token.isUsableAt(NOW.plusSeconds(1800))).isFalse();
    token.consume(NOW.plusSeconds(1799));
    assertThat(token.isUsableAt(NOW.plusSeconds(1799))).isFalse();
    assertThatThrownBy(() -> token.consume(NOW.plusSeconds(1799)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void revocationCannotBeUndoneOrCombinedWithConsumption() {
    PasswordResetToken token =
        new PasswordResetToken(UUID.randomUUID(), new byte[32], NOW.plusSeconds(1800));

    token.revoke(NOW);
    assertThat(token.isUsableAt(NOW)).isFalse();
    assertThatThrownBy(() -> token.consume(NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> token.revoke(NOW)).isInstanceOf(IllegalStateException.class);
  }
}
