package com.makemymarriage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.makemymarriage.identity.application.PasswordRecoveryEmailService;
import com.makemymarriage.identity.application.PasswordResetService;
import com.makemymarriage.identity.domain.UserAccount;
import com.makemymarriage.identity.infrastructure.UserAccountRepository;
import com.makemymarriage.notification.EmailDeliveryEligibility;
import com.makemymarriage.notification.EmailDraft;
import com.makemymarriage.notification.EmailOutbox;
import com.makemymarriage.notification.EmailSendException;
import com.makemymarriage.notification.application.OutboxWorker;
import com.makemymarriage.notification.infrastructure.EmailOutboxClaims;
import com.makemymarriage.notification.infrastructure.EmailPayloadCipher;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = {
      "server.address=127.0.0.1",
      "server.servlet.session.cookie.secure=false",
      "app.registration.enabled=false",
      "app.password-recovery.enabled=true",
      "app.password-recovery.response-floor-ms=0",
      "app.email-outbox.enabled=true",
      "app.email-outbox.worker.enabled=true",
      "app.email-outbox.worker.initial-delay-ms=600000",
      "app.verification.rate-limit.enabled=false",
      "app.mail.from=hello@makemymarriage.local"
    })
@ActiveProfiles("test")
@Testcontainers
class PasswordRecoveryJourneyIntegrationTests {
  private static final String TEST_KEY = testKey();
  private static final String TEST_RATE_KEY = testKey();

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"));

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2.9")).withExposedPorts(6379);

  @Container
  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.4"))
          .withExposedPorts(1025, 8025);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.data.redis.host", REDIS::getHost);
    properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    properties.add("spring.data.redis.password", () -> "");
    properties.add("spring.mail.host", MAILPIT::getHost);
    properties.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    properties.add("app.email-outbox.active-key-id", () -> "v1");
    properties.add("app.email-outbox.keys", () -> TEST_KEY);
    properties.add("app.password-recovery.rate-limit.key", () -> TEST_RATE_KEY.substring(3));
  }

  @LocalServerPort int port;
  @Autowired UserAccountRepository users;
  @Autowired PasswordRecoveryEmailService recovery;
  @Autowired PasswordResetService resets;
  @Autowired EmailOutbox outbox;
  @Autowired OutboxWorker worker;
  @Autowired EmailOutboxClaims claims;
  @Autowired EmailPayloadCipher cipher;
  @Autowired EmailDeliveryEligibility eligibility;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void validUnknownAndIneligibleAccountsReceiveSameAcceptedResponse() throws Exception {
    UUID eligible = createUser("eligible", true, "ACTIVE");
    UUID unverified = createUser("unverified", false, "ACTIVE");
    UUID suspended = createUser("suspended", true, "SUSPENDED");
    UUID deactivated = createUser("deactivated", true, "DEACTIVATED");
    var browser = browser();
    var csrf = csrf(browser);
    String eligibleEmail = email(eligible);
    HttpResponse<String> accepted =
        post(browser, csrf, "{\"email\":\"  " + eligibleEmail.toUpperCase() + "  \"}");
    assertThat(accepted.statusCode()).isEqualTo(202);
    assertThat(accepted.body()).isEmpty();
    assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(accepted.headers().firstValue("X-Correlation-ID")).isPresent();
    for (String address :
        new String[] {
          email(unverified),
          email(suspended),
          email(deactivated),
          "missing-" + UUID.randomUUID() + "@example.com"
        }) {
      var response = post(browser, csrf, "{\"email\":\"" + address + "\"}");
      assertThat(response.statusCode()).isEqualTo(202);
      assertThat(response.body()).isEmpty();
    }
    assertThat(tokenCount(eligible)).isEqualTo(1);
    assertThat(tokenCount(unverified)).isZero();
    assertThat(tokenCount(suspended)).isZero();
    assertThat(tokenCount(deactivated)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where template_code = 'PASSWORD_RESET'"
                    + " and recipient_email = ?",
                Integer.class,
                eligibleEmail))
        .isEqualTo(1);
    var row =
        jdbc.queryForMap(
            "select encrypted_payload, token_hash from email_outbox"
                + " join password_reset_tokens on email_outbox.purpose_id = password_reset_tokens.id"
                + " where recipient_email = ?",
            eligibleEmail);
    assertThat((byte[]) row.get("encrypted_payload")).isNotEmpty();
    assertThat((byte[]) row.get("token_hash")).hasSize(32);
    assertThat(new String((byte[]) row.get("encrypted_payload")))
        .doesNotContain("reset-password", "token=");
    assertThat(post(browser, csrf, "{\"email\":\"" + eligibleEmail + "\"}").statusCode())
        .isEqualTo(202);
    assertThat(tokenCount(eligible)).isEqualTo(1);
  }

  @Test
  void csrfValidationAndProblemDetailsProtectTheEndpoint() throws Exception {
    var browser = browser();
    var csrf = csrf(browser);
    assertThat(post(browser, null, "{\"email\":\"someone@example.com\"}").statusCode())
        .isEqualTo(403);
    var invalid = post(browser, csrf, "{\"email\":\"bad\"}");
    assertThat(invalid.statusCode()).isEqualTo(422);
    assertThat(invalid.headers().firstValue("Content-Type").orElseThrow())
        .contains("application/problem+json");
    assertThat(JsonPath.<String>read(invalid.body(), "$.code")).isEqualTo("VALIDATION_ERROR");
    assertThat(invalid.headers().firstValue("X-Correlation-ID")).isPresent();
    var malformed = post(browser, csrf, "{");
    assertThat(malformed.statusCode()).isEqualTo(400);
    assertThat(JsonPath.<String>read(malformed.body(), "$.code")).isEqualTo("MALFORMED_JSON");
    assertThat(
            browser
                .send(
                    HttpRequest.newBuilder(uri("/api/v1/weddings")).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void replacementCancelsPendingMailAndConcurrentRequestsLeaveOneActiveToken() {
    UUID userId = createUser("rotation", true, "ACTIVE");
    recovery.request(email(userId));
    UUID first = activeTokenId(userId);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = CompletableFuture.runAsync(() -> requestAfterLatch(userId, start), executor);
      var b = CompletableFuture.runAsync(() -> requestAfterLatch(userId, start), executor);
      start.countDown();
      CompletableFuture.allOf(a, b).join();
    }
    assertThat(tokenCount(userId)).isEqualTo(3);
    assertThat(activeTokenCount(userId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select revoked_at from password_reset_tokens where id = ?",
                java.time.Instant.class,
                first))
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select status from email_outbox where purpose_id = ?", String.class, first))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "select encrypted_payload from email_outbox where purpose_id = ?",
                byte[].class,
                first))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where recipient_email = ? and status = 'PENDING'",
                Integer.class,
                email(userId)))
        .isEqualTo(1);
  }

  @Test
  void rollbackKeepsPreviousTokenAndJobWhenReplacementFails() {
    UUID userId = createUser("rollback", true, "ACTIVE");
    recovery.request(email(userId));
    UUID first = activeTokenId(userId);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status -> {
                          recovery.request(email(userId));
                          throw new PlannedRollback();
                        }))
        .isInstanceOf(PlannedRollback.class);
    assertThat(tokenCount(userId)).isEqualTo(1);
    assertThat(activeTokenId(userId)).isEqualTo(first);
    assertThat(
            jdbc.queryForObject(
                "select status from email_outbox where purpose_id = ?", String.class, first))
        .isEqualTo("PENDING");
  }

  @Test
  void realMailpitDeliveryPurgesCiphertextAndContainsFragmentLink() throws Exception {
    UUID userId = createUser("mailpit", true, "ACTIVE");
    var browser = browser();
    var csrf = csrf(browser);
    assertThat(post(browser, csrf, "{\"email\":\"" + email(userId) + "\"}").statusCode())
        .isEqualTo(202);
    UUID tokenId = activeTokenId(userId);
    for (int i = 0; i < 20 && !"SENT".equals(outboxStatus(tokenId)); i++) worker.runOnce();
    assertThat(outboxStatus(tokenId)).isEqualTo("SENT");
    assertThat(
            jdbc.queryForObject(
                "select encrypted_payload from email_outbox where purpose_id = ?",
                byte[].class,
                tokenId))
        .isNull();
    var message =
        browser.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://"
                            + MAILPIT.getHost()
                            + ":"
                            + MAILPIT.getMappedPort(8025)
                            + "/view/latest.txt"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(message.statusCode()).isEqualTo(200);
    assertThat(message.body()).contains("30 minutes", "ignore this email", "reset-password#token=");
    var match =
        java.util.regex.Pattern.compile("reset-password#token=([A-Za-z0-9_-]{43})")
            .matcher(message.body());
    assertThat(match.find()).isTrue();
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(match.group(1)));
    assertThat(
            jdbc.queryForObject(
                "select token_hash from password_reset_tokens where id = ?", byte[].class, tokenId))
        .containsExactly(hash);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from users where id = ? and auth_version = 0",
                Integer.class,
                userId))
        .isEqualTo(1);

    resets.reset(match.group(1), "A-very-long-new-password");
    for (int i = 0; i < 20 && !"SENT".equals(outboxStatus(userId)); i++) worker.runOnce();
    assertThat(outboxStatus(userId)).isEqualTo("SENT");
    assertThat(
            jdbc.queryForObject(
                "select encrypted_payload from email_outbox where purpose_id = ?",
                byte[].class,
                userId))
        .isNull();
    var changed =
        browser.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://"
                            + MAILPIT.getHost()
                            + ":"
                            + MAILPIT.getMappedPort(8025)
                            + "/view/latest.txt"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(changed.body())
        .contains("password was changed", "contact support")
        .doesNotContain(match.group(1), "A-very-long-new-password", "password_hash");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from users where id = ? and auth_version = 1",
                Integer.class,
                userId))
        .isEqualTo(1);
  }

  @Test
  void revokedPurposeIsExpiredAndTemporaryFailureRetries() {
    UUID revoked = createUser("revoked", true, "ACTIVE");
    recovery.request(email(revoked));
    UUID revokedId = activeTokenId(revoked);
    jdbc.update("update password_reset_tokens set revoked_at = now() where id = ?", revokedId);
    for (int i = 0; i < 20 && "PENDING".equals(outboxStatus(revokedId)); i++) worker.runOnce();
    assertThat(outboxStatus(revokedId)).isEqualTo("EXPIRED");

    UUID retry = createUser("retry", true, "ACTIVE");
    recovery.request(email(retry));
    UUID retryId = activeTokenId(retry);
    var failedWorker =
        new OutboxWorker(
            claims,
            cipher,
            delivery -> {
              throw new EmailSendException(false);
            },
            eligibility,
            Clock.systemUTC());
    for (int i = 0; i < 20 && outboxAttempts(retryId) == 0; i++) failedWorker.runOnce();
    assertThat(outboxStatus(retryId)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select failure_code from email_outbox where purpose_id = ?",
                String.class,
                retryId))
        .isEqualTo("SENDER_TEMPORARY");
    jdbc.update("update email_outbox set next_attempt_at = now() where purpose_id = ?", retryId);
    var successWorker =
        new OutboxWorker(
            claims,
            cipher,
            delivery -> new com.makemymarriage.notification.EmailSender.SendReceipt("retry-ok"),
            eligibility,
            Clock.systemUTC());
    for (int i = 0; i < 20 && !"SENT".equals(outboxStatus(retryId)); i++) successWorker.runOnce();
    assertThat(outboxStatus(retryId)).isEqualTo("SENT");
  }

  @Test
  void passwordChangedNotificationRetriesWithoutLeakingSensitivePayload() {
    UUID userId = createUser("changed-retry", true, "ACTIVE");
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status ->
                outbox.enqueue(
                    new EmailDraft(
                        email(userId),
                        "PASSWORD_CHANGED",
                        "{}",
                        Instant.now().plus(Duration.ofDays(1)),
                        userId)));
    var failedWorker =
        new OutboxWorker(
            claims,
            cipher,
            delivery -> {
              throw new EmailSendException(false);
            },
            eligibility,
            Clock.systemUTC());
    for (int i = 0; i < 20 && outboxAttempts(userId) == 0; i++) failedWorker.runOnce();
    assertThat(outboxStatus(userId)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select failure_code from email_outbox where purpose_id = ?", String.class, userId))
        .isEqualTo("SENDER_TEMPORARY");
    jdbc.update("update email_outbox set next_attempt_at = now() where purpose_id = ?", userId);
    var successWorker =
        new OutboxWorker(
            claims,
            cipher,
            delivery -> new com.makemymarriage.notification.EmailSender.SendReceipt("changed-ok"),
            eligibility,
            Clock.systemUTC());
    for (int i = 0; i < 20 && !"SENT".equals(outboxStatus(userId)); i++) successWorker.runOnce();
    assertThat(outboxStatus(userId)).isEqualTo("SENT");
    assertThat(
            jdbc.queryForObject(
                "select encrypted_payload from email_outbox where purpose_id = ?",
                byte[].class,
                userId))
        .isNull();
  }

  private UUID createUser(String label, boolean verified, String status) {
    UUID id =
        users
            .saveAndFlush(
                new UserAccount(
                    label + "-" + UUID.randomUUID() + "@example.com", "test-hash", "Test"))
            .getId();
    if (verified) jdbc.update("update users set email_verified_at = now() where id = ?", id);
    if (!"ACTIVE".equals(status))
      jdbc.update("update users set status = ? where id = ?", status, id);
    return id;
  }

  private String email(UUID id) {
    return jdbc.queryForObject("select email from users where id = ?", String.class, id);
  }

  private int tokenCount(UUID id) {
    return jdbc.queryForObject(
        "select count(*) from password_reset_tokens where user_id = ?", Integer.class, id);
  }

  private int activeTokenCount(UUID id) {
    return jdbc.queryForObject(
        "select count(*) from password_reset_tokens"
            + " where user_id = ? and consumed_at is null and revoked_at is null",
        Integer.class,
        id);
  }

  private UUID activeTokenId(UUID id) {
    return jdbc.queryForObject(
        "select id from password_reset_tokens"
            + " where user_id = ? and consumed_at is null and revoked_at is null",
        UUID.class,
        id);
  }

  private String outboxStatus(UUID tokenId) {
    return jdbc.queryForObject(
        "select status from email_outbox where purpose_id = ?", String.class, tokenId);
  }

  private int outboxAttempts(UUID tokenId) {
    return jdbc.queryForObject(
        "select attempt_count from email_outbox where purpose_id = ?", Integer.class, tokenId);
  }

  private void requestAfterLatch(UUID id, CountDownLatch start) {
    try {
      start.await();
      recovery.request(email(id));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private HttpClient browser() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  private Csrf csrf(HttpClient browser) throws Exception {
    var response =
        browser.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return new Csrf(
        JsonPath.read(response.body(), "$.headerName"), JsonPath.read(response.body(), "$.token"));
  }

  private HttpResponse<String> post(HttpClient browser, Csrf csrf, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(uri("/api/v1/auth/password/forgot"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (csrf != null) request.header(csrf.header(), csrf.token());
    return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String testKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return "v1:" + Base64.getEncoder().encodeToString(key);
  }

  private record Csrf(String header, String token) {}

  private static class PlannedRollback extends RuntimeException {}
}
