package com.makemymarriage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@Import(PasswordResetTokenIntegrationTests.TestClockConfiguration.class)
@Testcontainers
class PasswordResetTokenIntegrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2.9")).withExposedPorts(6379);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.data.redis.host", REDIS::getHost);
    properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    properties.add("spring.data.redis.password", () -> "");
  }

  @Autowired UserAccountRepository users;
  @Autowired PasswordResetTokenService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MutableClock clock;

  @BeforeEach
  void resetClock() {
    clock.set(Instant.now());
  }

  @Test
  void freshMigrationAndHibernateValidationPreserveHashOnlyTokensAndDefaultVersion()
      throws Exception {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '5' and success",
                Integer.class))
        .isEqualTo(1);
    UUID userId = createUser(true);

    IssuedPasswordResetToken issued = service.issueForUser(userId);

    var row =
        jdbc.queryForMap(
            "select user_id, token_hash, expires_at, consumed_at, revoked_at"
                + " from password_reset_tokens where id = ?",
            issued.id());
    assertThat(row.get("user_id")).isEqualTo(userId);
    assertThat((byte[]) row.get("token_hash"))
        .containsExactly(
            MessageDigest.getInstance("SHA-256")
                .digest(Base64.getUrlDecoder().decode(issued.secret())));
    assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
    assertThat(issued.toString()).doesNotContain(issued.secret());
    assertThat(row.get("consumed_at")).isNull();
    assertThat(row.get("revoked_at")).isNull();
    assertThat(
            jdbc.queryForObject("select auth_version from users where id = ?", Long.class, userId))
        .isZero();
  }

  @Test
  void consumedTokenCannotBeReusedAndConsumptionRequiresOuterTransaction() {
    UUID userId = createUser(true);
    IssuedPasswordResetToken issued = service.issueForUser(userId);

    assertThatThrownBy(() -> service.consume(issued.secret()))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    assertThat(consumeInTransaction(issued.secret())).isEqualTo(userId);
    assertThatThrownBy(() -> consumeInTransaction(issued.secret()))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
    assertThat(
            jdbc.queryForObject(
                "select consumed_at from password_reset_tokens where id = ?",
                Instant.class,
                issued.id()))
        .isNotNull();
    assertThat(
            jdbc.queryForObject("select auth_version from users where id = ?", Long.class, userId))
        .isZero();
  }

  @Test
  void rollbackLeavesTokenUsableForFutureAtomicPasswordChange() {
    UUID userId = createUser(true);
    IssuedPasswordResetToken issued = service.issueForUser(userId);

    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(
                        status -> {
                          service.consume(issued.secret());
                          throw new DeliberateRollback();
                        }))
        .isInstanceOf(DeliberateRollback.class);
    assertThat(consumeInTransaction(issued.secret())).isEqualTo(userId);
  }

  @Test
  void expirationReplacementAndExplicitRevocationMakeOldSecretsUnusable() {
    UUID userId = createUser(true);
    IssuedPasswordResetToken first = service.issueForUser(userId);
    assertThatThrownBy(() -> consumeInTransaction("invalid"))
        .isInstanceOf(InvalidPasswordResetTokenException.class);

    clock.set(
        jdbc.queryForObject(
            "select expires_at from password_reset_tokens where id = ?",
            Instant.class,
            first.id()));
    assertThatThrownBy(() -> consumeInTransaction(first.secret()))
        .isInstanceOf(InvalidPasswordResetTokenException.class);

    IssuedPasswordResetToken second = service.issueForUser(userId);
    assertThat(second.supersededId()).isEqualTo(first.id());
    assertThat(
            jdbc.queryForObject(
                "select revoked_at from password_reset_tokens where id = ?",
                Instant.class,
                first.id()))
        .isNotNull();
    assertThatThrownBy(() -> consumeInTransaction(first.secret()))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
    assertThat(service.revokeActiveForUser(userId)).contains(second.id());
    assertThat(service.revokeActiveForUser(userId)).isEmpty();
    assertThatThrownBy(() -> consumeInTransaction(second.secret()))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
  }

  @Test
  void unverifiedSuspendedAndDeactivatedAccountsCannotIssueOrConsume() {
    UUID unverified = createUser(false);
    assertThatThrownBy(() -> service.issueForUser(unverified))
        .isInstanceOf(IllegalStateException.class);

    UUID suspended = createUser(true);
    IssuedPasswordResetToken suspendedToken = service.issueForUser(suspended);
    jdbc.update("update users set status = 'SUSPENDED' where id = ?", suspended);
    assertThatThrownBy(() -> service.issueForUser(suspended))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> consumeInTransaction(suspendedToken.secret()))
        .isInstanceOf(InvalidPasswordResetTokenException.class);

    UUID deactivated = createUser(true);
    IssuedPasswordResetToken deactivatedToken = service.issueForUser(deactivated);
    jdbc.update("update users set status = 'DEACTIVATED' where id = ?", deactivated);
    assertThatThrownBy(() -> service.issueForUser(deactivated))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> consumeInTransaction(deactivatedToken.secret()))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
  }

  @Test
  void concurrentIssuanceLeavesOneActiveTokenAndConcurrentConsumptionOneSuccess() {
    UUID userId = createUser(true);
    CountDownLatch issueStart = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          CompletableFuture.supplyAsync(() -> issueAfterLatch(userId, issueStart), executor);
      var second =
          CompletableFuture.supplyAsync(() -> issueAfterLatch(userId, issueStart), executor);
      issueStart.countDown();
      assertThat(first.join().id()).isNotEqualTo(second.join().id());
    }
    assertThat(activeTokenCount(userId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from password_reset_tokens where user_id = ?",
                Integer.class,
                userId))
        .isEqualTo(2);

    IssuedPasswordResetToken current = service.issueForUser(userId);
    CountDownLatch consumeStart = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          CompletableFuture.supplyAsync(
              () -> consumeAfterLatch(current.secret(), consumeStart), executor);
      var second =
          CompletableFuture.supplyAsync(
              () -> consumeAfterLatch(current.secret(), consumeStart), executor);
      consumeStart.countDown();
      assertThat(first.join() + second.join()).isEqualTo(1);
    }
    assertThat(activeTokenCount(userId)).isZero();
  }

  @Test
  void databaseConstraintsRejectSecondActiveTokenMalformedHashAndNegativeVersion() {
    UUID userId = createUser(true);
    service.issueForUser(userId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into password_reset_tokens"
                        + " (id, user_id, token_hash, expires_at)"
                        + " values (?, ?, ?, now() + interval '30 minutes')",
                    UUID.randomUUID(),
                    userId,
                    new byte[32]))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID otherUser = createUser(true);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into password_reset_tokens"
                        + " (id, user_id, token_hash, expires_at)"
                        + " values (?, ?, ?, now() + interval '30 minutes')",
                    UUID.randomUUID(),
                    otherUser,
                    new byte[3]))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update users set auth_version = -1 where id = ?", userId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID createUser(boolean verified) {
    UUID userId =
        users
            .saveAndFlush(
                new UserAccount("reset-" + UUID.randomUUID() + "@example.com", "test-hash", "Test"))
            .getId();
    if (verified) {
      jdbc.update("update users set email_verified_at = now() where id = ?", userId);
    }
    return userId;
  }

  private UUID consumeInTransaction(String secret) {
    return new TransactionTemplate(transactions).execute(status -> service.consume(secret));
  }

  private int activeTokenCount(UUID userId) {
    return jdbc.queryForObject(
        "select count(*) from password_reset_tokens"
            + " where user_id = ? and consumed_at is null and revoked_at is null",
        Integer.class,
        userId);
  }

  private IssuedPasswordResetToken issueAfterLatch(UUID userId, CountDownLatch latch) {
    await(latch);
    return service.issueForUser(userId);
  }

  private int consumeAfterLatch(String secret, CountDownLatch latch) {
    await(latch);
    try {
      consumeInTransaction(secret);
      return 1;
    } catch (InvalidPasswordResetTokenException exception) {
      return 0;
    }
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  static class DeliberateRollback extends RuntimeException {}

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfiguration {
    @Bean
    @Primary
    MutableClock testPasswordResetClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    void set(Instant instant) {
      now.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
