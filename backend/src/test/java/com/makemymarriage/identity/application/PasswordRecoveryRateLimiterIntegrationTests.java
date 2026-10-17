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
class PasswordRecoveryRateLimiterIntegrationTests {
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2.9")).withExposedPorts(6379);

  @Test
  void concurrentRequestsAllowOneAndRedisKeysContainNoEmailOrIp() {
    withRedis(
        redis -> {
          var limiter = new PasswordRecoveryRateLimiter(redis, key(), 300, 3, 30, 20);
          String email = "concurrent-" + UUID.randomUUID() + "@example.com";
          String ip = UUID.randomUUID().toString();
          CountDownLatch start = new CountDownLatch(1);
          try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                CompletableFuture.supplyAsync(() -> allowed(limiter, email, ip, start), executor);
            var second =
                CompletableFuture.supplyAsync(() -> allowed(limiter, email, ip, start), executor);
            start.countDown();
            assertThat((first.join() ? 1 : 0) + (second.join() ? 1 : 0)).isEqualTo(1);
          }
          assertThat(redis.keys("recovery:forgot:*"))
              .allSatisfy(k -> assertThat(k).doesNotContain(email, ip));
        });
  }

  @Test
  void dailyAndIpLimitsAreIndependentAndAccountLimitsStayGeneric() {
    withRedis(
        redis -> {
          var limiter = new PasswordRecoveryRateLimiter(redis, key(), 300, 3, 30, 20);
          String email = "daily-" + UUID.randomUUID() + "@example.com";
          for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(limiter.allowRequest(email, UUID.randomUUID().toString()))
                .isEqualTo(attempt <= 3);
            redis.delete(redis.keys("recovery:forgot:cooldown:*"));
          }
          var ipLimiter = new PasswordRecoveryRateLimiter(redis, key(), 300, 3, 1, 20);
          String ip = UUID.randomUUID().toString();
          assertThat(ipLimiter.allowRequest(UUID.randomUUID() + "@example.com", ip)).isTrue();
          assertThatThrownBy(() -> ipLimiter.allowRequest(UUID.randomUUID() + "@example.com", ip))
              .isInstanceOfSatisfying(
                  PasswordRecoveryRateLimitException.class,
                  exception -> assertThat(exception.isUnavailable()).isFalse());
        });
  }

  @Test
  void redisFailureFailsClosed() {
    StringRedisTemplate unavailable =
        mock(
            StringRedisTemplate.class,
            invocation -> {
              throw new IllegalStateException("offline");
            });
    var limiter = new PasswordRecoveryRateLimiter(unavailable, key(), 300, 3, 30, 20);
    assertThatThrownBy(() -> limiter.allowRequest("any@example.com", "127.0.0.1"))
        .isInstanceOfSatisfying(
            PasswordRecoveryRateLimitException.class,
            exception -> assertThat(exception.isUnavailable()).isTrue());
    assertThatThrownBy(() -> limiter.checkResetAttempt("127.0.0.1"))
        .isInstanceOfSatisfying(
            PasswordRecoveryRateLimitException.class,
            exception -> assertThat(exception.isUnavailable()).isTrue());
  }

  @Test
  void resetAttemptLimitIsAtomicPerDirectIp() {
    withRedis(
        redis -> {
          var limiter = new PasswordRecoveryRateLimiter(redis, key(), 300, 3, 30, 2);
          String ip = UUID.randomUUID().toString();
          limiter.checkResetAttempt(ip);
          limiter.checkResetAttempt(ip);
          assertThatThrownBy(() -> limiter.checkResetAttempt(ip))
              .isInstanceOfSatisfying(
                  PasswordRecoveryRateLimitException.class,
                  exception -> assertThat(exception.isUnavailable()).isFalse());
          assertThat(redis.keys("recovery:reset:ip:*"))
              .allSatisfy(k -> assertThat(k).doesNotContain(ip));
        });
  }

  private void withRedis(java.util.function.Consumer<StringRedisTemplate> check) {
    var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    try {
      factory.afterPropertiesSet();
      var redis = new StringRedisTemplate(factory);
      redis.afterPropertiesSet();
      check.accept(redis);
    } finally {
      factory.destroy();
    }
  }

  private boolean allowed(
      PasswordRecoveryRateLimiter limiter, String email, String ip, CountDownLatch start) {
    try {
      start.await();
      return limiter.allowRequest(email, ip);
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
