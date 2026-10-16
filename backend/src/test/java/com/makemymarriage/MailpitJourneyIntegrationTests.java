package com.makemymarriage;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.makemymarriage.notification.application.OutboxWorker;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
      "app.registration.enabled=true",
      "app.email-outbox.enabled=true",
      "app.email-outbox.worker.enabled=true",
      "app.email-outbox.worker.initial-delay-ms=600000",
      "app.verification.rate-limit.enabled=true",
      "app.mail.from=hello@makemymarriage.local"
    })
@ActiveProfiles("test")
@Testcontainers
class MailpitJourneyIntegrationTests {
  private static final String TEST_KEY = testKey();
  private static final Pattern TOKEN_LINK =
      Pattern.compile("http://127\\.0\\.0\\.1:4200/verify-email#token=([A-Za-z0-9_-]+)");

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
    properties.add("app.verification.rate-limit.key", () -> TEST_KEY.substring(3));
  }

  @LocalServerPort int port;
  @Autowired OutboxWorker worker;
  @Autowired JdbcTemplate jdbc;

  @Test
  void registrationToMailpitToVerificationUsesEncryptedOutboxAndNoLogin() throws Exception {
    HttpClient browser =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    HttpResponse<String> csrfResponse =
        browser.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(csrfResponse.statusCode()).isEqualTo(200);
    String csrf = JsonPath.read(csrfResponse.body(), "$.token");
    String header = JsonPath.read(csrfResponse.body(), "$.headerName");
    String email = "mailpit-" + UUID.randomUUID() + "@example.com";
    HttpResponse<String> registration =
        post(
            browser,
            "/api/v1/auth/register",
            header,
            csrf,
            "{\"displayName\":\"Anjali\",\"email\":\""
                + email
                + "\",\"password\":\"SecurePassword123!\"}");
    assertThat(registration.statusCode()).isEqualTo(201);
    UUID userId = UUID.fromString(JsonPath.read(registration.body(), "$.id"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_verification_tokens where user_id = ?",
                Integer.class,
                userId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where recipient_email = ? and encrypted_payload is not null",
                Integer.class,
                email))
        .isEqualTo(1);

    runWorkerUntilStatus(email, "SENT");
    assertThat(
            jdbc.queryForObject(
                "select status from email_outbox where recipient_email = ?", String.class, email))
        .isEqualTo("SENT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where recipient_email = ? and encrypted_payload is null",
                Integer.class,
                email))
        .isEqualTo(1);
    HttpResponse<String> message =
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
    assertThat(message.body()).contains("24 hours", "ignore this email");
    var match = TOKEN_LINK.matcher(message.body());
    assertThat(match.find()).isTrue();
    String token = match.group(1);
    assertThat(registration.body()).doesNotContain(token);

    assertThat(
            post(browser, "/api/v1/auth/email/verify", null, null, "{\"token\":\"" + token + "\"}")
                .statusCode())
        .isEqualTo(403);
    assertThat(
            post(
                    browser,
                    "/api/v1/auth/email/verify",
                    header,
                    csrf,
                    "{\"token\":\"" + token + "\"}")
                .statusCode())
        .isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select email_verified_at is not null from users where id = ?",
                Boolean.class,
                userId))
        .isTrue();
    assertThat(
            post(
                    browser,
                    "/api/v1/auth/email/verify",
                    header,
                    csrf,
                    "{\"token\":\"" + token + "\"}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            browser
                .send(
                    HttpRequest.newBuilder(uri("/api/v1/weddings")).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void revokedTokenIsExpiredBeforeSmtpAndNeverAppearsInMailpit() throws Exception {
    HttpClient browser =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    HttpResponse<String> csrfResponse =
        browser.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String email = "revoked-" + UUID.randomUUID() + "@example.com";
    HttpResponse<String> registration =
        post(
            browser,
            "/api/v1/auth/register",
            JsonPath.read(csrfResponse.body(), "$.headerName"),
            JsonPath.read(csrfResponse.body(), "$.token"),
            "{\"displayName\":\"Anjali\",\"email\":\""
                + email
                + "\",\"password\":\"SecurePassword123!\"}");
    assertThat(registration.statusCode()).isEqualTo(201);
    jdbc.update(
        "update email_verification_tokens set revoked_at = now() where user_id = ?",
        UUID.fromString(JsonPath.read(registration.body(), "$.id")));

    runWorkerUntilStatus(email, "EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "select status from email_outbox where recipient_email = ?", String.class, email))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where recipient_email = ? and encrypted_payload is null",
                Integer.class,
                email))
        .isEqualTo(1);
    HttpResponse<String> messages =
        browser.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://"
                            + MAILPIT.getHost()
                            + ":"
                            + MAILPIT.getMappedPort(8025)
                            + "/api/v1/messages"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(messages.statusCode()).isEqualTo(200);
    assertThat(messages.body()).doesNotContain(email);
  }

  @Test
  void repeatedResendKeepsGeneric202WithoutRotatingAgainDuringCooldown() throws Exception {
    HttpClient browser =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    HttpResponse<String> csrfResponse =
        browser.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String header = JsonPath.read(csrfResponse.body(), "$.headerName");
    String csrf = JsonPath.read(csrfResponse.body(), "$.token");
    String email = "cooldown-" + UUID.randomUUID() + "@example.com";
    HttpResponse<String> registration =
        post(
            browser,
            "/api/v1/auth/register",
            header,
            csrf,
            "{\"displayName\":\"Anjali\",\"email\":\""
                + email
                + "\",\"password\":\"SecurePassword123!\"}");
    assertThat(registration.statusCode()).isEqualTo(201);
    String body = "{\"email\":\"" + email + "\"}";
    assertThat(
            post(browser, "/api/v1/auth/email/verification-requests", header, csrf, body)
                .statusCode())
        .isEqualTo(202);
    UUID activeToken =
        jdbc.queryForObject(
            "select id from email_verification_tokens where user_id = ? and revoked_at is null",
            UUID.class,
            UUID.fromString(JsonPath.read(registration.body(), "$.id")));
    assertThat(
            post(browser, "/api/v1/auth/email/verification-requests", header, csrf, body)
                .statusCode())
        .isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "select id from email_verification_tokens where user_id = ? and revoked_at is null",
                UUID.class,
                UUID.fromString(JsonPath.read(registration.body(), "$.id"))))
        .isEqualTo(activeToken);
    assertThat(
            post(
                    browser,
                    "/api/v1/auth/email/verification-requests",
                    header,
                    csrf,
                    "{\"email\":\"missing-" + UUID.randomUUID() + "@example.com\"}")
                .statusCode())
        .isEqualTo(202);
  }

  private HttpResponse<String> post(
      HttpClient client, String path, String header, String csrf, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (header != null) request.header(header, csrf);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void runWorkerUntilStatus(String email, String expected) {
    for (int attempt = 0; attempt < 10; attempt++) {
      if (expected.equals(
          jdbc.queryForObject(
              "select status from email_outbox where recipient_email = ?", String.class, email))) {
        return;
      }
      worker.runOnce();
    }
    assertThat(
            jdbc.queryForObject(
                "select status from email_outbox where recipient_email = ?", String.class, email))
        .isEqualTo(expected);
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String testKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return "v1:" + Base64.getEncoder().encodeToString(key);
  }
}
