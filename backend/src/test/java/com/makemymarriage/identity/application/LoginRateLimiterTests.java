package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class LoginRateLimiterTests {
  @Test
  void unavailableRedisRejectsLoginAttempts() {
    StringRedisTemplate unavailable =
        org.mockito.Mockito.mock(
            StringRedisTemplate.class,
            invocation -> {
              throw new IllegalStateException("Redis offline");
            });
    LoginRateLimiter limiter = new LoginRateLimiter(unavailable, randomKey(), 10, 60, 900);

    assertThatThrownBy(() -> limiter.check("user@example.com", "127.0.0.1"))
        .isInstanceOfSatisfying(
            LoginRateLimitException.class,
            exception -> assertThat(exception.isUnavailable()).isTrue());
  }

  @Test
  void missingOrWeakKeyCannotEnableAuthenticationLimiter() {
    assertThatThrownBy(() -> new LoginRateLimiter(null, "", 10, 60, 900))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new LoginRateLimiter(
                    null, Base64.getEncoder().encodeToString(new byte[16]), 10, 60, 900))
        .isInstanceOf(IllegalStateException.class);
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
