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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@Import(VerificationTokenIntegrationTests.TestClockConfiguration.class)
@Testcontainers
class VerificationTokenIntegrationTests {
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
  @Autowired VerificationTokenService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;

  @BeforeEach
  void resetClock() {
    clock.set(Instant.now());
  }

  @Test
  void migrationValidatesAndExistingUnverifiedUserCanReceiveToken() throws Exception {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success",
                Integer.class))
        .isEqualTo(1);
    UUID userId = createUnverifiedUser();

    IssuedVerificationToken issued = service.issueForUser(userId);

    var row =
        jdbc.queryForMap(
            "select user_id, token_hash, expires_at, consumed_at, revoked_at"
                + " from email_verification_tokens where id = ?",
            issued.id());
    assertThat(row.get("user_id")).isEqualTo(userId);
    assertThat((byte[]) row.get("token_hash"))
        .containsExactly(
            MessageDigest.getInstance("SHA-256")
                .digest(Base64.getUrlDecoder().decode(issued.secret())));
    assertThat(issued.toString()).doesNotContain(issued.secret());
    assertThat(row.get("consumed_at")).isNull();
    assertThat(row.get("revoked_at")).isNull();
    assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
    assertThat(
            jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId))
        .isEqualTo(1);
  }

  @Test
  void validTokenVerifiesEmailOnceWithoutChangingActiveStatus() {
    UUID userId = createUnverifiedUser();
    IssuedVerificationToken issued = service.issueForUser(userId);

    service.verify(issued.secret());

    var user = jdbc.queryForMap("select status, email_verified_at from users where id = ?", userId);
    assertThat(user.get("status")).isEqualTo("ACTIVE");
    assertThat(user.get("email_verified_at")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select consumed_at from email_verification_tokens where id = ?",
                Instant.class,
                issued.id()))
        .isNotNull();
    assertThatThrownBy(() -> service.verify(issued.secret()))
        .isInstanceOf(InvalidVerificationTokenException.class);
  }

  @Test
  void invalidExpiredAndRotatedTokensCannotVerify() {
    UUID userId = createUnverifiedUser();
    IssuedVerificationToken first = service.issueForUser(userId);
    assertThatThrownBy(() -> service.verify("invalid"))
        .isInstanceOf(InvalidVerificationTokenException.class);

    clock.set(clock.instant().plus(Duration.ofHours(25)));
    assertThatThrownBy(() -> service.verify(first.secret()))
        .isInstanceOf(InvalidVerificationTokenException.class);

    IssuedVerificationToken replacement = service.issueForUser(userId);
    assertThat(
            jdbc.queryForObject(
                "select revoked_at from email_verification_tokens where id = ?",
                Instant.class,
                first.id()))
        .isNotNull();
    assertThatThrownBy(() -> service.verify(first.secret()))
        .isInstanceOf(InvalidVerificationTokenException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_verification_tokens"
                    + " where user_id = ? and consumed_at is null and revoked_at is null",
                Integer.class,
                userId))
        .isEqualTo(1);

    service.verify(replacement.secret());
    assertThat(
            jdbc.queryForObject(
                "select email_verified_at from users where id = ?", Instant.class, userId))
        .isNotNull();
  }

  @Test
  void concurrentIssuanceRotatesPreviousTokenAndLeavesOnlyOneActive() throws Exception {
    UUID userId = createUnverifiedUser();
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = CompletableFuture.supplyAsync(() -> issueAfterLatch(userId, start), executor);
      var second = CompletableFuture.supplyAsync(() -> issueAfterLatch(userId, start), executor);
      start.countDown();
      assertThat(first.join().id()).isNotEqualTo(second.join().id());
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_verification_tokens where user_id = ?",
                Integer.class,
                userId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_verification_tokens"
                    + " where user_id = ? and consumed_at is null and revoked_at is null",
                Integer.class,
                userId))
        .isEqualTo(1);
  }

  @Test
  void concurrentConsumptionOnlyAllowsOneSuccess() throws Exception {
    UUID userId = createUnverifiedUser();
    IssuedVerificationToken issued = service.issueForUser(userId);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          CompletableFuture.supplyAsync(() -> verifyAfterLatch(issued.secret(), start), executor);
      var second =
          CompletableFuture.supplyAsync(() -> verifyAfterLatch(issued.secret(), start), executor);
      start.countDown();
      assertThat(first.join() + second.join()).isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "select email_verified_at from users where id = ?", Instant.class, userId))
        .isNotNull();
  }

  @Test
  void suspendedAccountCannotIssueOrConsumeToken() {
    UUID userId = createUnverifiedUser();
    IssuedVerificationToken issued = service.issueForUser(userId);
    jdbc.update("update users set status = 'SUSPENDED' where id = ?", userId);

    assertThatThrownBy(() -> service.issueForUser(userId))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.verify(issued.secret()))
        .isInstanceOf(InvalidVerificationTokenException.class);
    assertThat(
            jdbc.queryForObject(
                "select email_verified_at from users where id = ?", Instant.class, userId))
        .isNull();
  }

  @Test
  void databaseRejectsSecondActiveTokenAndMalformedDigest() {
    UUID userId = createUnverifiedUser();
    service.issueForUser(userId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into email_verification_tokens"
                        + " (id, user_id, token_hash, expires_at) values (?, ?, ?, now() + interval '1 day')",
                    UUID.randomUUID(),
                    userId,
                    new byte[32]))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID otherUserId = createUnverifiedUser();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into email_verification_tokens"
                        + " (id, user_id, token_hash, expires_at) values (?, ?, ?, now() + interval '1 day')",
                    UUID.randomUUID(),
                    otherUserId,
                    new byte[3]))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID createUnverifiedUser() {
    return users
        .saveAndFlush(
            new UserAccount("user-" + UUID.randomUUID() + "@example.com", "test-hash", "Test User"))
        .getId();
  }

  private IssuedVerificationToken issueAfterLatch(UUID userId, CountDownLatch start) {
    await(start);
    return service.issueForUser(userId);
  }

  private int verifyAfterLatch(String secret, CountDownLatch start) {
    await(start);
    try {
      service.verify(secret);
      return 1;
    } catch (InvalidVerificationTokenException exception) {
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

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfiguration {
    @Bean
    @Primary
    MutableClock testVerificationClock() {
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
