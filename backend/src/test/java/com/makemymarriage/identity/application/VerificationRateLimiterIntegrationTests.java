package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class VerificationRateLimiterIntegrationTests {
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2.9")).withExposedPorts(6379);

  @Test
  void concurrentRequestsAllowOnlyOneResendAndDoNotStorePlaintextEmail() {
    var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    try {
      factory.afterPropertiesSet();
      var redis = new StringRedisTemplate(factory);
      redis.afterPropertiesSet();
      var limiter = new VerificationRateLimiter(redis, key(), 30, 20);
      String email = "rate-" + UUID.randomUUID() + "@example.com";
      String ip = UUID.randomUUID().toString();
      CountDownLatch start = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var a = CompletableFuture.supplyAsync(() -> allowed(limiter, email, ip, start), executor);
        var b = CompletableFuture.supplyAsync(() -> allowed(limiter, email, ip, start), executor);
        start.countDown();
        assertThat((a.join() ? 1 : 0) + (b.join() ? 1 : 0)).isEqualTo(1);
      }
      assertThat(redis.keys("verification:*"))
          .allSatisfy(redisKey -> assertThat(redisKey).doesNotContain(email, ip));
    } finally {
      factory.destroy();
    }
  }

  @Test
  void ipWideAndVerificationAttemptLimitsFailClosed() {
    var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    try {
      factory.afterPropertiesSet();
      var redis = new StringRedisTemplate(factory);
      redis.afterPropertiesSet();
      var limiter = new VerificationRateLimiter(redis, key(), 1, 2);
      String ip = UUID.randomUUID().toString();
      assertThat(limiter.allowResend("one@example.com", ip)).isTrue();
      assertThatThrownBy(() -> limiter.allowResend("two@example.com", ip))
          .isInstanceOfSatisfying(
              VerificationRateLimitException.class,
              exception -> assertThat(exception.isUnavailable()).isFalse());
      limiter.checkVerificationAttempt(ip);
      limiter.checkVerificationAttempt(ip);
      assertThatThrownBy(() -> limiter.checkVerificationAttempt(ip))
          .isInstanceOf(VerificationRateLimitException.class);
    } finally {
      factory.destroy();
    }
  }

  @Test
  void fourthDailyResendIsDeniedEvenAfterCooldownKeysExpire() {
    var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    try {
      factory.afterPropertiesSet();
      var redis = new StringRedisTemplate(factory);
      redis.afterPropertiesSet();
      var limiter = new VerificationRateLimiter(redis, key(), 30, 20);
      String email = "daily-" + UUID.randomUUID() + "@example.com";
      String ip = UUID.randomUUID().toString();
      for (int attempt = 1; attempt <= 4; attempt++) {
        var before = redis.keys("verification:resend:cooldown:*");
        boolean allowed = limiter.allowResend(email, ip);
        assertThat(allowed).isEqualTo(attempt <= 3);
        var cooldowns = redis.keys("verification:resend:cooldown:*");
        cooldowns.removeAll(before);
        if (attempt < 4) {
          assertThat(cooldowns).hasSize(1);
          redis.delete(cooldowns.iterator().next());
        }
      }
    } finally {
      factory.destroy();
    }
  }

  @Test
  void redisFailureRejectsRequestsBeforeAnyTokenWork() {
    StringRedisTemplate unavailable =
        mock(
            StringRedisTemplate.class,
            invocation -> {
              throw new IllegalStateException("Redis offline");
            });
    var limiter = new VerificationRateLimiter(unavailable, key(), 30, 20);
    assertThatThrownBy(() -> limiter.allowResend("any@example.com", "127.0.0.1"))
        .isInstanceOfSatisfying(
            VerificationRateLimitException.class,
            exception -> assertThat(exception.isUnavailable()).isTrue());
    assertThatThrownBy(() -> limiter.checkVerificationAttempt("127.0.0.1"))
        .isInstanceOfSatisfying(
            VerificationRateLimitException.class,
            exception -> assertThat(exception.isUnavailable()).isTrue());
  }

  private boolean allowed(
      VerificationRateLimiter limiter, String email, String ip, CountDownLatch start) {
    try {
      start.await();
      return limiter.allowResend(email, ip);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private String key() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
