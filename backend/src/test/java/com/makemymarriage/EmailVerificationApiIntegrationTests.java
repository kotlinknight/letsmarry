package com.makemymarriage;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.makemymarriage.notification.infrastructure.EmailPayloadCipher;
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
      "app.email-outbox.enabled=true"
    })
@ActiveProfiles("test")
@Testcontainers
class EmailVerificationApiIntegrationTests {
  private static final String TEST_KEYS = testKeys();

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
    properties.add("app.email-outbox.active-key-id", () -> "v1");
    properties.add("app.email-outbox.keys", () -> TEST_KEYS);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired EmailPayloadCipher cipher;

  @Test
  void registrationAtomicallyStoresHashOnlyTokenAndEncryptedJob() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    String email = uniqueEmail();
    var response = register(client, csrf, email);
    assertThat(response.statusCode()).isEqualTo(201);
    UUID userId = UUID.fromString(JsonPath.read(response.body(), "$.id"));
    var token =
        jdbc.queryForMap(
            "select id, token_hash, expires_at from email_verification_tokens where user_id = ?",
            userId);
    assertThat((byte[]) token.get("token_hash")).hasSize(32);
    UUID tokenId = (UUID) token.get("id");
    var job = job(tokenId);
    assertThat(job.get("status")).isEqualTo("PENDING");
    assertThat((byte[]) job.get("encrypted_payload")).isNotEmpty();
    String secret = secret(job);
    assertThat(response.body()).doesNotContain(secret);
    assertThat((byte[]) job.get("encrypted_payload")).asString().doesNotContain(secret);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where purpose_id = ?", Integer.class, tokenId))
        .isEqualTo(1);
    assertThat(get(client, "/api/v1/weddings").statusCode()).isEqualTo(401);
  }

  @Test
  void verificationRequiresCsrfAndConsumesOnlyOnceWithoutAuthenticating() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    UUID userId =
        UUID.fromString(JsonPath.read(register(client, csrf, uniqueEmail()).body(), "$.id"));
    UUID tokenId = tokenId(userId);
    String token = secret(job(tokenId));
    var missingCsrf =
        post(client, "/api/v1/auth/email/verify", null, "{\"token\":\"" + token + "\"}");
    assertThat(missingCsrf.statusCode()).isEqualTo(403);
    assertThat(
            post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"" + token + "\"}")
                .statusCode())
        .isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select email_verified_at from users where id = ?", Object.class, userId))
        .isNotNull();
    assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, userId))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "select consumed_at from email_verification_tokens where id = ?",
                Object.class,
                tokenId))
        .isNotNull();
    assertThat(job(tokenId).get("status")).isEqualTo("EXPIRED");
    assertThat(job(tokenId).get("encrypted_payload")).isNull();
    String email =
        jdbc.queryForObject("select email from users where id = ?", String.class, userId);
    assertThat(
            post(
                    client,
                    "/api/v1/auth/email/verification-requests",
                    csrf,
                    "{\"email\":\"" + email + "\"}")
                .statusCode())
        .isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where recipient_email = ?",
                Integer.class,
                email))
        .isEqualTo(1);
    assertProblem(
        post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"" + token + "\"}"),
        "VERIFICATION_TOKEN_INVALID");
    assertThat(get(client, "/api/v1/weddings").statusCode()).isEqualTo(401);
  }

  @Test
  void resendRotatesTokenCancelsPendingJobAndHidesAccountExistence() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    String email = uniqueEmail();
    UUID userId = UUID.fromString(JsonPath.read(register(client, csrf, email).body(), "$.id"));
    UUID oldId = tokenId(userId);
    String oldSecret = secret(job(oldId));
    assertThat(
            post(
                    client,
                    "/api/v1/auth/email/verification-requests",
                    null,
                    "{\"email\":\"" + email + "\"}")
                .statusCode())
        .isEqualTo(403);
    assertThat(
            post(
                    client,
                    "/api/v1/auth/email/verification-requests",
                    csrf,
                    "{\"email\":\" " + email.toUpperCase() + " \"}")
                .statusCode())
        .isEqualTo(202);
    UUID newId = tokenId(userId);
    assertThat(newId).isNotEqualTo(oldId);
    assertThat(job(oldId).get("status")).isEqualTo("EXPIRED");
    assertThat(job(oldId).get("encrypted_payload")).isNull();
    assertProblem(
        post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"" + oldSecret + "\"}"),
        "VERIFICATION_TOKEN_INVALID");
    var unknown =
        post(
            client,
            "/api/v1/auth/email/verification-requests",
            csrf,
            "{\"email\":\"" + uniqueEmail() + "\"}");
    assertThat(unknown.statusCode()).isEqualTo(202);
    assertThat(unknown.body()).isEmpty();
    assertThat(
            post(
                    client,
                    "/api/v1/auth/email/verification-requests",
                    csrf,
                    "{\"email\":\"" + email + "\"}")
                .statusCode())
        .isEqualTo(202);
    assertThat(tokenId(userId)).isNotEqualTo(newId);
  }

  @Test
  void resendLeavesAlreadyClaimedMailInFlightButRevokesItsLink() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    String email = uniqueEmail();
    UUID userId = UUID.fromString(JsonPath.read(register(client, csrf, email).body(), "$.id"));
    UUID oldId = tokenId(userId);
    String oldSecret = secret(job(oldId));
    jdbc.update(
        "update email_outbox set status = 'PROCESSING', claim_id = ?,"
            + " lease_until = now() + interval '2 minutes' where purpose_id = ?",
        UUID.randomUUID(),
        oldId);

    assertThat(
            post(
                    client,
                    "/api/v1/auth/email/verification-requests",
                    csrf,
                    "{\"email\":\"" + email + "\"}")
                .statusCode())
        .isEqualTo(202);
    assertThat(job(oldId).get("status")).isEqualTo("PROCESSING");
    assertProblem(
        post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"" + oldSecret + "\"}"),
        "VERIFICATION_TOKEN_INVALID");
    assertThat(tokenId(userId)).isNotEqualTo(oldId);
  }

  @Test
  void legacyAccountCanRequestFirstTokenAndSuspendedAccountCannot() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    String email = uniqueEmail();
    UUID userId = UUID.randomUUID();
    jdbc.update(
        "insert into users (id, email, password_hash, display_name, status, version)"
            + " values (?, ?, 'legacy-hash', 'Legacy', 'ACTIVE', 0)",
        userId,
        email);
    assertThat(
            post(
                    client,
                    "/api/v1/auth/email/verification-requests",
                    csrf,
                    "{\"email\":\"" + email + "\"}")
                .statusCode())
        .isEqualTo(202);
    assertThat(tokenId(userId)).isNotNull();
    jdbc.update("update users set status = 'SUSPENDED' where id = ?", userId);
    int before =
        jdbc.queryForObject(
            "select count(*) from email_outbox where recipient_email = ?", Integer.class, email);
    assertThat(
            post(
                    client,
                    "/api/v1/auth/email/verification-requests",
                    csrf,
                    "{\"email\":\"" + email + "\"}")
                .statusCode())
        .isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_outbox where recipient_email = ?",
                Integer.class,
                email))
        .isEqualTo(before);
  }

  @Test
  void expiredRevokedAndSuspendedLinksShareTheSameGenericFailure() throws Exception {
    HttpClient client = client();
    Csrf csrf = csrf(client);
    UUID expiredUser =
        UUID.fromString(JsonPath.read(register(client, csrf, uniqueEmail()).body(), "$.id"));
    UUID expiredId = tokenId(expiredUser);
    String expiredSecret = secret(job(expiredId));
    jdbc.update(
        "update email_verification_tokens set created_at = now() - interval '2 days',"
            + " expires_at = now() - interval '1 day' where id = ?",
        expiredId);
    assertProblem(
        post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"" + expiredSecret + "\"}"),
        "VERIFICATION_TOKEN_INVALID");

    UUID revokedUser =
        UUID.fromString(JsonPath.read(register(client, csrf, uniqueEmail()).body(), "$.id"));
    UUID revokedId = tokenId(revokedUser);
    String revokedSecret = secret(job(revokedId));
    jdbc.update("update email_verification_tokens set revoked_at = now() where id = ?", revokedId);
    assertProblem(
        post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"" + revokedSecret + "\"}"),
        "VERIFICATION_TOKEN_INVALID");

    UUID suspendedUser =
        UUID.fromString(JsonPath.read(register(client, csrf, uniqueEmail()).body(), "$.id"));
    String suspendedSecret = secret(job(tokenId(suspendedUser)));
    jdbc.update("update users set status = 'SUSPENDED' where id = ?", suspendedUser);
    assertProblem(
        post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"" + suspendedSecret + "\"}"),
        "VERIFICATION_TOKEN_INVALID");
    assertProblem(
        post(client, "/api/v1/auth/email/verify", csrf, "{\"token\":\"invalid\"}"),
        "VERIFICATION_TOKEN_INVALID");
  }

  @Test
  void verificationAndResendSerializeWithoutTwoValidLinks() throws Exception {
    HttpClient verifyClient = client();
    HttpClient resendClient = client();
    Csrf verifyCsrf = csrf(verifyClient);
    Csrf resendCsrf = csrf(resendClient);
    String email = uniqueEmail();
    UUID userId =
        UUID.fromString(JsonPath.read(register(verifyClient, verifyCsrf, email).body(), "$.id"));
    String originalSecret = secret(job(tokenId(userId)));
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var verification =
          CompletableFuture.supplyAsync(
              () -> {
                await(start);
                return postUnchecked(
                    verifyClient,
                    "/api/v1/auth/email/verify",
                    verifyCsrf,
                    "{\"token\":\"" + originalSecret + "\"}");
              },
              executor);
      var resend =
          CompletableFuture.supplyAsync(
              () -> {
                await(start);
                return postUnchecked(
                    resendClient,
                    "/api/v1/auth/email/verification-requests",
                    resendCsrf,
                    "{\"email\":\"" + email + "\"}");
              },
              executor);
      start.countDown();
      assertThat(resend.join().statusCode()).isEqualTo(202);
      assertThat(verification.join().statusCode()).isIn(204, 400);
      boolean verified =
          jdbc.queryForObject(
              "select email_verified_at is not null from users where id = ?",
              Boolean.class,
              userId);
      int active =
          jdbc.queryForObject(
              "select count(*) from email_verification_tokens where user_id = ?"
                  + " and consumed_at is null and revoked_at is null",
              Integer.class,
              userId);
      assertThat(active).isEqualTo(verified ? 0 : 1);
      assertThat(verification.join().statusCode()).isEqualTo(verified ? 204 : 400);
    }
  }

  private UUID tokenId(UUID userId) {
    return jdbc.queryForObject(
        "select id from email_verification_tokens where user_id = ?"
            + " and consumed_at is null and revoked_at is null",
        UUID.class,
        userId);
  }

  private java.util.Map<String, Object> job(UUID tokenId) {
    return jdbc.queryForMap("select * from email_outbox where purpose_id = ?", tokenId);
  }

  private String secret(java.util.Map<String, Object> job) {
    String payload =
        cipher.decrypt(
            (UUID) job.get("id"),
            (String) job.get("recipient_email"),
            (String) job.get("template_code"),
            ((java.sql.Timestamp) job.get("expires_at")).toInstant(),
            (byte[]) job.get("encrypted_payload"),
            (byte[]) job.get("nonce"),
            (String) job.get("key_version"));
    String url = JsonPath.read(payload, "$.verificationUrl");
    return new URIBuilder(url).token();
  }

  private HttpClient client() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  private Csrf csrf(HttpClient client) throws Exception {
    var response = get(client, "/api/v1/auth/csrf");
    assertThat(response.statusCode()).isEqualTo(200);
    return new Csrf(
        JsonPath.read(response.body(), "$.token"), JsonPath.read(response.body(), "$.headerName"));
  }

  private HttpResponse<String> register(HttpClient client, Csrf csrf, String email)
      throws Exception {
    return post(
        client,
        "/api/v1/auth/register",
        csrf,
        "{\"displayName\":\"Test\",\"email\":\""
            + email
            + "\",\"password\":\"SecurePassword123!\"}");
  }

  private HttpResponse<String> post(HttpClient client, String path, Csrf csrf, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (csrf != null) request.header(csrf.headerName(), csrf.token());
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postUnchecked(
      HttpClient client, String path, Csrf csrf, String body) {
    try {
      return post(client, path, csrf, body);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
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

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  private void assertProblem(HttpResponse<String> response, String code) {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    assertThat(response.body()).doesNotContain("token_hash");
  }

  private static String testKeys() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return "v1:" + Base64.getEncoder().encodeToString(key);
  }

  private record Csrf(String token, String headerName) {}

  private record URIBuilder(String value) {
    String token() {
      return value.substring(value.indexOf("#token=") + 7);
    }
  }
}
